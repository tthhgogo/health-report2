package com.example.healthreport.task;

import com.example.healthreport.persistence.CtHealthReportFileEntity;
import com.example.healthreport.persistence.CtHealthReportFileService;
import com.example.healthreport.persistence.CtHealthReportTaskEntity;
import com.example.healthreport.persistence.CtHealthReportTaskService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 五分钟数据生命周期清理任务。
 * <p>按状态矩阵逐类判断，绝不把全部 FAILED 任务笼统当成可立即删除。</p>
 * <p>单轮候选有上限：未清完的部分留给下一轮，最终一致而非单轮一致
 * （理由见 {@link CleanupProperties#getBatchSize()}）。</p>
 */
@Slf4j
@Component
public class CleanupJob {

    private final CtHealthReportTaskService taskService;
    private final CtHealthReportFileService fileService;
    private final TaskResourceCleanupService resourceCleanupService;
    private final CleanupProperties properties;
    private final Clock clock;

    @Autowired
    public CleanupJob(CtHealthReportTaskService taskService,
                      CtHealthReportFileService fileService,
                      TaskResourceCleanupService resourceCleanupService,
                      CleanupProperties properties) {
        this(taskService, fileService, resourceCleanupService, properties, Clock.systemDefaultZone());
    }

    /** 可注入时钟的构造器，仅用于确定性测试。 */
    public CleanupJob(CtHealthReportTaskService taskService,
                      CtHealthReportFileService fileService,
                      TaskResourceCleanupService resourceCleanupService,
                      CleanupProperties properties,
                      Clock clock) {
        this.taskService = taskService;
        this.fileService = fileService;
        this.resourceCleanupService = resourceCleanupService;
        this.properties = properties;
        this.clock = clock;
    }

    /** 执行清理矩阵并返回本轮扫描任务数。 */
    @XxlJob("healthReportCleanupJob")
    public int cleanup() {
        LocalDateTime currentTime = LocalDateTime.now(clock);
        int batchSize = properties.getBatchSize();
        List<CtHealthReportTaskEntity> taskEntityList =
                taskService.findCleanupCandidates(currentTime, batchSize);
        for (CtHealthReportTaskEntity taskEntity : taskEntityList) {
            cleanupTask(taskEntity, currentTime);
        }
        List<CtHealthReportFileEntity> orphanFileList =
                fileService.findExpiredOrphans(currentTime, batchSize);
        int orphanDeletedCount = 0;
        for (CtHealthReportFileEntity orphanFile : orphanFileList) {
            if (resourceCleanupService.deleteOrphan(orphanFile)) {
                orphanDeletedCount++;
            }
        }
        // 候选数打满说明本轮被截断，还有积压——存储故障时这条是最早能看到的信号。
        boolean truncated = taskEntityList.size() >= batchSize || orphanFileList.size() >= batchSize;
        // 候选数和实际删除数必须分开记：两者持续背离（候选一直有、删除一直是 0）
        // 说明清理在原地空转，而「候选数没打满」会让人误以为一切正常。
        log.info("数据清理巡检完成：任务候选数={}，孤儿文件候选数={}，孤儿实际删除数={}，"
                        + "单轮上限={}，是否截断={}",
                taskEntityList.size(), orphanFileList.size(), orphanDeletedCount,
                batchSize, truncated);
        return taskEntityList.size() + orphanFileList.size();
    }

    private void cleanupTask(CtHealthReportTaskEntity taskEntity, LocalDateTime currentTime) {
        boolean expired = taskEntity.getExpireAt() != null
                && !taskEntity.getExpireAt().isAfter(currentTime);
        boolean deleted = taskEntity.getDeletedAt() != null;
        boolean succeeded = TaskStatus.SUCCEEDED.name().equals(taskEntity.getStatus());
        boolean failed = TaskStatus.FAILED.name().equals(taskEntity.getStatus());
        boolean reanalyzable = Boolean.TRUE.equals(taskEntity.getReanalyzable());

        // 未删除的执行中任务即使 expire_at 已到也必须完整保留，等待三条巡检先收敛到失败终态。
        if (!deleted && !succeeded && !failed) {
            if (!isRunning(taskEntity.getStatus())) {
                log.warn("跳过状态不符合清理矩阵的任务，taskId={}，status={}",
                        taskEntity.getTaskId(), taskEntity.getStatus());
            }
            return;
        }

        boolean shouldDeleteFiles = deleted || succeeded || (failed && (!reanalyzable || expired));
        boolean shouldDeleteResult = deleted || expired || failed;
        boolean filesDeleted = !shouldDeleteFiles
                || resourceCleanupService.deleteFiles(taskEntity.getTaskId());
        boolean resultDeleted = !shouldDeleteResult
                || resourceCleanupService.deleteResult(taskEntity.getTaskId());

        // 只有终态或已删除任务会到达这里；资源未清干净时保留 task 行供下一轮重试。
        if (expired && filesDeleted && resultDeleted) {
            taskService.deleteExpired(taskEntity.getTaskId(), currentTime);
        }
    }

    /** 判断任务是否仍处于清理矩阵要求完整保留的执行中状态。 */
    private boolean isRunning(String status) {
        return TaskStatus.QUEUED.name().equals(status)
                || TaskStatus.PARSING.name().equals(status)
                || TaskStatus.EXTRACTING.name().equals(status)
                || TaskStatus.ASSEMBLING.name().equals(status);
    }
}
