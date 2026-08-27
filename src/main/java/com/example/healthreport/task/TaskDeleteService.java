package com.example.healthreport.task;

import com.example.healthreport.persistence.CtHealthReportTaskEntity;
import com.example.healthreport.persistence.CtHealthReportTaskService;
import com.example.healthreport.support.OwnershipException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 用户删除任务的业务执行点。
 * <p>删除只设置正交标志并清理存储，不中断已经运行的工作线程。</p>
 */
@Service
@Slf4j
public class TaskDeleteService {

    private final TaskOwnershipGuard ownershipGuard;
    private final CtHealthReportTaskService taskService;
    private final TaskResourceCleanupService resourceCleanupService;

    public TaskDeleteService(TaskOwnershipGuard ownershipGuard,
                             CtHealthReportTaskService taskService,
                             TaskResourceCleanupService resourceCleanupService) {
        this.ownershipGuard = ownershipGuard;
        this.taskService = taskService;
        this.resourceCleanupService = resourceCleanupService;
    }

    /**
     * B7 唯一执行点：deleted_at 一旦非空不可恢复为空。DDL 无兜底。
     */
    public void delete(String taskId, String currentUserId) {
        CtHealthReportTaskEntity ownedTask = ownershipGuard.assertOwned(taskId, currentUserId);
        int affectedRows = taskService.markDeleted(ownedTask.getTaskId(), currentUserId);
        if (affectedRows != 1) {
            throw new OwnershipException();
        }

        // 用户主动删除是数据生命周期里唯一由用户触发的终态，必须留痕：
        // 「我删了但结果还在」这类申诉，第一件事就是确认这条在不在、在什么时候。
        log.info("用户删除任务成功，taskId={}", taskId);

        // 不调用 Future.cancel、不发中断；后续工作线程写回由 deleted_at IS NULL 拦截。
        try {
            resourceCleanupService.deleteFiles(taskId);
        } catch (RuntimeException exception) {
            IllegalStateException sanitizedException = new IllegalStateException(
                    "删除清理异常类型:" + exception.getClass().getName());
            sanitizedException.setStackTrace(exception.getStackTrace());
            log.error("用户删除后的原文件清理异常", sanitizedException);
        } finally {
            // 原文件清理异常不能阻止结果缓存删除；MySQL deleted_at 已先保证结果不可见。
            resourceCleanupService.deleteResult(taskId);
        }
    }
}
