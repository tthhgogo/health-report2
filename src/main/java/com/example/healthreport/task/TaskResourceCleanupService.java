package com.example.healthreport.task;

import com.example.healthreport.cache.TaskResultCache;
import com.example.healthreport.infra.S3FileStorage;
import com.example.healthreport.persistence.CtHealthReportFileEntity;
import com.example.healthreport.persistence.CtHealthReportFileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 任务原文件、file 行与 Redis 结果的幂等清理服务。
 * <p>对象存储删除失败时保留对应 file 行，让五分钟清理任务可以再次处理。</p>
 */
@Slf4j
@Service
public class TaskResourceCleanupService {

    private final CtHealthReportFileService fileService;
    private final S3FileStorage fileStorage;
    private final TaskResultCache resultCache;

    public TaskResourceCleanupService(CtHealthReportFileService fileService,
                                      S3FileStorage fileStorage,
                                      TaskResultCache resultCache) {
        this.fileService = fileService;
        this.fileStorage = fileStorage;
        this.resultCache = resultCache;
    }

    /**
     * 删除任务关联的原文件和 file 行。
     *
     * <p><b>刻意不放在一个事务里。</b> 循环里每一步都要调对象存储，事务会把数据库行锁的
     * 持有时间绑到 S3 的响应时间上——存储抖动时就是「文件数 × 超时」那么久，
     * 而同一张表上 {@code lockForBinding} 也在用 {@code FOR UPDATE}。</p>
     *
     * <p>而且这个事务原本就<b>什么也没保证</b>：异常在循环内部就被捕获了，不会触发回滚，
     * 提交的本来就是部分删除的结果。真要回滚反而更糟——已经从 S3 删掉的对象回滚不了，
     * 数据库却把行恢复了，留下指向不存在对象的行。</p>
     *
     * <p><b>删对象之前必须先认领。</b>条件删只能事后发现归属变化，发现时 S3 对象已经没了：
     * FAILED 可重析任务被删除的同时用户重新发起分析，同一 fileId 在「查出行」与「删对象」
     * 之间绑上新任务，旧清理却把对象删掉——新任务指向不存在的对象，且不可恢复。
     * {@code claimForCleanup} 用单行 CAS 把行置为 CLEANING（S3 调用期间不持任何数据库锁），
     * 绑定要求 {@code status='UPLOADED'}，认领成功后重绑必然失败；认领 0 行说明归属已变，
     * 跳过、绝不触碰对象存储。S3 删除失败保留 CLEANING 行，下一轮巡检重新认领重试。</p>
     *
     * @return 是否全部清理成功；失败项保留供后续生命周期巡检继续清理
     */
    public boolean deleteFiles(String taskId) {
        final List<CtHealthReportFileEntity> fileEntityList;
        try {
            List<CtHealthReportFileEntity> foundList = fileService.findByTaskId(taskId);
            // 外部返回值边界校验（§0.3.1）：null 视同无待清理行，与 TaskRenderService 口径一致。
            fileEntityList = foundList == null
                    ? java.util.Collections.<CtHealthReportFileEntity>emptyList() : foundList;
        } catch (RuntimeException exception) {
            logCleanupFailure("待清理文件查询失败", exception);
            return false;
        }
        boolean allDeleted = true;
        int deletedCount = 0;
        int skippedCount = 0;
        for (CtHealthReportFileEntity fileEntity : fileEntityList) {
            try {
                int claimedRows = fileService.claimForCleanup(fileEntity.getFileId(), taskId);
                if (claimedRows != 1) {
                    // 行已被重新绑定或已删除：对象归新任务所有，本任务的清理义务对它已消失。
                    skippedCount++;
                    log.info("清理认领落空已跳过，taskId={}，fileId={}", taskId, fileEntity.getFileId());
                    continue;
                }
                fileStorage.delete(fileEntity.getCloudFileKey());
                int affectedRows = fileService.deleteByFileAndTask(fileEntity.getFileId(), taskId);
                if (affectedRows != 1) {
                    // 认领成功后行不可能被改走，这里只剩不变量校验。
                    throw new IllegalStateException("文件归属在清理认领后发生变化");
                }
                deletedCount++;
            } catch (RuntimeException exception) {
                allDeleted = false;
                logCleanupFailure("任务原文件清理失败", exception);
            }
        }
        // 报告原文被删干净了没有，是数据生命周期承诺里唯一可核查的一条（§4.5）。
        // 只有这条日志能证明它发生过——file 行删完之后就再也查不出来了。
        // 【不记 cloudFileKey】它是 health-report/{fileId}，等价于记 fileId，没有必要。
        log.info("任务原文件清理完成，taskId={}，待清理数={}，已删除数={}，认领落空数={}，全部成功={}",
                taskId, fileEntityList.size(), deletedCount, skippedCount, allDeleted);
        return allDeleted;
    }

    /** 删除已过期孤儿上传；失败行保留供下一轮继续处理。认领语义同 {@link #deleteFiles}。 */
    public boolean deleteOrphan(CtHealthReportFileEntity fileEntity) {
        try {
            int claimedRows = fileService.claimOrphanForCleanup(fileEntity.getFileId());
            if (claimedRows != 1) {
                // 过期边界上被绑走或已删：不再是孤儿，绝不触碰对象存储。
                log.info("孤儿清理认领落空已跳过，fileId={}", fileEntity.getFileId());
                return true;
            }
            fileStorage.delete(fileEntity.getCloudFileKey());
            fileService.deleteOrphan(fileEntity.getFileId());
            // 孤儿是「插了 file 行但对象没写成 / 建了任务但没提交」留下的（§4.1）。
            // 它长期为 0 才正常；开始持续出现说明上传路径在稳定地失败。
            log.info("孤儿上传清理完成，fileId={}", fileEntity.getFileId());
            return true;
        } catch (RuntimeException exception) {
            logCleanupFailure("孤儿上传清理失败", exception);
            return false;
        }
    }

    /** 删除 Redis 结果；异常时返回 false，清理任务会保留任务行供后续生命周期巡检处理。 */
    public boolean deleteResult(String taskId) {
        try {
            boolean existed = resultCache.delete(taskId);
            log.info("分析结果缓存清理完成，taskId={}，清理前是否存在={}", taskId, existed);
            return true;
        } catch (RuntimeException exception) {
            logCleanupFailure("分析结果缓存清理失败", exception);
            return false;
        }
    }

    private void logCleanupFailure(String message, RuntimeException exception) {
        // 底层异常可能携带对象键或响应正文；脱敏副本只保留类型和原始调用栈。
        IllegalStateException sanitizedException = new IllegalStateException(
                "清理异常类型:" + exception.getClass().getName());
        sanitizedException.setStackTrace(exception.getStackTrace());
        log.error(message, sanitizedException);
    }
}
