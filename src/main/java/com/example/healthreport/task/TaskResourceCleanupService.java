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
     * <p>并发安全由 {@code deleteByFileAndTask} 的条件删返回行数保证，与事务无关。
     * 单个文件失败只影响它自己，其余照删，剩下的留给下一轮巡检。</p>
     *
     * @return 是否全部清理成功；失败项保留供后续生命周期巡检继续清理
     */
    public boolean deleteFiles(String taskId) {
        final List<CtHealthReportFileEntity> fileEntityList;
        try {
            fileEntityList = fileService.findByTaskId(taskId);
        } catch (RuntimeException exception) {
            logCleanupFailure("待清理文件查询失败", exception);
            return false;
        }
        boolean allDeleted = true;
        int deletedCount = 0;
        for (CtHealthReportFileEntity fileEntity : fileEntityList) {
            try {
                fileStorage.delete(fileEntity.getCloudFileKey());
                int affectedRows = fileService.deleteByFileAndTask(fileEntity.getFileId(), taskId);
                if (affectedRows != 1) {
                    throw new IllegalStateException("文件归属在清理事务内发生变化");
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
        log.info("任务原文件清理完成，taskId={}，待清理数={}，已删除数={}，全部成功={}",
                taskId, fileEntityList.size(), deletedCount, allDeleted);
        return allDeleted;
    }

    /** 删除已过期孤儿上传；失败行保留供下一轮继续处理。 */
    public boolean deleteOrphan(CtHealthReportFileEntity fileEntity) {
        try {
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
