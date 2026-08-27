package com.example.healthreport.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.healthreport.support.SystemActor;
import com.example.healthreport.task.TaskStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code ct_health_report_task} 的唯一数据库操作 Service。
 */
@Service
public class CtHealthReportTaskService {

    private final CtHealthReportTaskMapper taskMapper;

    public CtHealthReportTaskService(CtHealthReportTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    /**
     * 按任务主键读取，不附带大小写不敏感的 user_id 条件；精确归属比较由 Guard 执行。
     */
    public CtHealthReportTaskEntity findByTaskId(String taskId) {
        return taskMapper.selectById(taskId);
    }

    /**
     * 在线接口创建任务，强制覆盖两个审计身份，绝不接受用户标识。
     */
    public int insertFromApi(CtHealthReportTaskEntity taskEntity) {
        taskEntity.setCreateBy(SystemActor.HEALTH_REPORT_API);
        taskEntity.setUpdateBy(SystemActor.HEALTH_REPORT_API);
        return taskMapper.insert(taskEntity);
    }

    /**
     * 工作线程写回任务，强制使用固定工作线程身份。
     */
    public int updateFromWorker(CtHealthReportTaskEntity taskEntity) {
        taskEntity.setUpdateBy(SystemActor.HEALTH_REPORT_WORKER);
        return taskMapper.updateById(taskEntity);
    }

    /** 领取任务并进入 PARSING；影响零行时调用方必须直接结束。 */
    public int claim(String taskId, LocalDateTime heartbeatAt, LocalDateTime deadlineAt) {
        return taskMapper.claim(taskId, heartbeatAt, deadlineAt, SystemActor.HEALTH_REPORT_WORKER);
    }

    /** 执行纯单向阶段迁移。 */
    public int transition(String taskId, String expectedStatus, String nextStatus,
                          String stage, int progress) {
        return taskMapper.transition(taskId, expectedStatus, nextStatus, stage, progress,
                SystemActor.HEALTH_REPORT_WORKER);
    }

    /** 独立更新心跳，绝不更新硬截止。 */
    public int heartbeat(String taskId) {
        return taskMapper.heartbeat(taskId);
    }

    /** 条件写入失败终态。 */
    public int failActive(String taskId, String failCode, boolean reanalyzable) {
        return taskMapper.failActive(taskId, failCode, reanalyzable,
                SystemActor.HEALTH_REPORT_WORKER);
    }

    /** 成功 CAS 失败后收敛已超时的 ASSEMBLING 任务。 */
    public int failExpiredAssembling(String taskId, LocalDateTime currentTime) {
        return taskMapper.failExpiredAssembling(taskId, currentTime, SystemActor.HEALTH_REPORT_WORKER);
    }

    /** 成对写入部分结果标志与主原因。 */
    public int markPartial(String taskId, String partialReason) {
        return taskMapper.markPartial(taskId, partialReason, SystemActor.HEALTH_REPORT_WORKER);
    }

    /** 提交成功终态并顺延两小时有效期。 */
    public int succeed(String taskId, LocalDateTime currentTime, LocalDateTime expireAt) {
        return taskMapper.succeed(taskId, currentTime, expireAt, SystemActor.HEALTH_REPORT_WORKER);
    }

    /** API 侧不可逆地设置删除时间。 */
    public int markDeleted(String taskId, String userId) {
        return taskMapper.markDeleted(taskId, userId, SystemActor.HEALTH_REPORT_API);
    }

    /** 三条巡检之一：心跳超时。 */
    public int failHeartbeatTimeout(LocalDateTime heartbeatThreshold) {
        return taskMapper.failHeartbeatTimeout(heartbeatThreshold, SystemActor.HEALTH_REPORT_WORKER);
    }

    /** 三条巡检之一：硬截止超时。 */
    public int failDeadlineTimeout(LocalDateTime currentTime) {
        return taskMapper.failDeadlineTimeout(currentTime, SystemActor.HEALTH_REPORT_WORKER);
    }

    /** 三条巡检之一：QUEUED 等待超时。 */
    public int failQueuedTimeout(LocalDateTime queuedThreshold) {
        return taskMapper.failQueuedTimeout(queuedThreshold, SystemActor.HEALTH_REPORT_WORKER);
    }

    /** 查询清理矩阵中的当前候选任务，单轮截断且最旧优先。 */
    public List<CtHealthReportTaskEntity> findCleanupCandidates(LocalDateTime currentTime, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("清理批量必须大于零");
        }
        return taskMapper.selectCleanupCandidates(currentTime, batchSize);
    }

    /** 仅删除已经过期的任务行。 */
    public int deleteExpired(String taskId, LocalDateTime currentTime) {
        LambdaQueryWrapper<CtHealthReportTaskEntity> deleteWrapper =
                new LambdaQueryWrapper<CtHealthReportTaskEntity>();
        deleteWrapper.eq(CtHealthReportTaskEntity::getTaskId, taskId)
                .le(CtHealthReportTaskEntity::getExpireAt, currentTime)
                .and(terminalWrapper -> terminalWrapper
                        .isNotNull(CtHealthReportTaskEntity::getDeletedAt)
                        .or()
                        .in(CtHealthReportTaskEntity::getStatus,
                                TaskStatus.SUCCEEDED.name(), TaskStatus.FAILED.name()));
        return taskMapper.delete(deleteWrapper);
    }
}
