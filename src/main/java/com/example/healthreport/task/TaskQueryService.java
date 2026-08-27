package com.example.healthreport.task;

import com.example.healthreport.api.dto.TaskStatusResponse;
import com.example.healthreport.cache.AnalysisResult;
import com.example.healthreport.cache.TaskResultCache;
import com.example.healthreport.persistence.CtHealthReportTaskEntity;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import com.example.healthreport.support.OwnershipException;
import org.springframework.stereotype.Service;

/**
 * 任务状态与结果查询服务。
 * <p>结果读取先由 MySQL 判断可见性，未成功任务绝不访问 Redis。</p>
 */
@Service
public class TaskQueryService {

    private final TaskOwnershipGuard ownershipGuard;
    private final TaskResultCache resultCache;

    public TaskQueryService(TaskOwnershipGuard ownershipGuard, TaskResultCache resultCache) {
        this.ownershipGuard = ownershipGuard;
        this.resultCache = resultCache;
    }

    /** 查询可见任务的轮询状态，QUEUED 正常返回 UPLOADING/0。 */
    public TaskStatusResponse getStatus(String taskId, String currentUserId) {
        CtHealthReportTaskEntity taskEntity = ownershipGuard.assertOwned(taskId, currentUserId);
        FailCode failCode = taskEntity.getFailCode();
        return new TaskStatusResponse(taskEntity.getStatus(), taskEntity.getStage(),
                taskEntity.getProgress() == null ? 0 : taskEntity.getProgress(),
                failCode, Boolean.TRUE.equals(taskEntity.getReanalyzable()));
    }

    /**
     * 按 MySQL → 状态 → Redis 的固定顺序读取结果。
     */
    public AnalysisResult getResult(String taskId, String currentUserId) {
        CtHealthReportTaskEntity taskEntity = ownershipGuard.assertOwned(taskId, currentUserId);
        if (!TaskStatus.SUCCEEDED.name().equals(taskEntity.getStatus())) {
            throw new HealthReportException(FailCode.TASK_NOT_FINISHED, 409);
        }
        AnalysisResult result = resultCache.read(taskId);
        if (result == null) {
            throw new OwnershipException();
        }
        return result;
    }
}
