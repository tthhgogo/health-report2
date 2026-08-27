package com.example.healthreport.task;

import com.example.healthreport.cache.AnalysisResult;
import com.example.healthreport.cache.TaskResultCache;
import com.example.healthreport.persistence.CtHealthReportTaskService;
import com.example.healthreport.support.FailCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 任务状态机与跨存储成功提交的唯一业务执行点。
 * <p>所有迁移纯单向，工作线程写回均受 {@code deleted_at IS NULL} 保护。</p>
 */
@Slf4j
@Service
public class TaskStateService {

    private final CtHealthReportTaskService taskService;
    private final TaskResultCache resultCache;
    private final TaskResourceCleanupService resourceCleanupService;
    private final Clock clock;

    public TaskStateService(CtHealthReportTaskService taskService, TaskResultCache resultCache) {
        this(taskService, resultCache, null, Clock.systemDefaultZone());
    }

    /** 生产构造器接入失败后的即时资源清理。 */
    @Autowired
    public TaskStateService(CtHealthReportTaskService taskService, TaskResultCache resultCache,
                            TaskResourceCleanupService resourceCleanupService) {
        this(taskService, resultCache, resourceCleanupService, Clock.systemDefaultZone());
    }

    /** 可注入时钟的构造器，仅用于确定性测试。 */
    public TaskStateService(CtHealthReportTaskService taskService, TaskResultCache resultCache,
                            TaskResourceCleanupService resourceCleanupService, Clock clock) {
        this.taskService = taskService;
        this.resultCache = resultCache;
        this.resourceCleanupService = resourceCleanupService;
        this.clock = clock;
    }

    /**
     * 领取 QUEUED 任务并进入 PARSING。
     *
     * @return 是否领取成功；false 时工作线程必须无副作用退出
     */
    public boolean claim(String taskId) {
        LocalDateTime currentTime = LocalDateTime.now(clock);
        boolean claimed = taskService.claim(taskId, currentTime, currentTime.plusMinutes(10L)) == 1;
        if (claimed) {
            log.info("任务领取 CAS 成功，taskId={}，原状态={}，新状态={}，stage={}，progress={}",
                    taskId, TaskStatus.QUEUED, TaskStatus.PARSING, TaskStage.PARSING, 10);
        }
        return claimed;
    }

    /** 从 PARSING 单向进入 EXTRACTING，stage 仍为 PARSING。 */
    public boolean enterExtracting(String taskId) {
        return transition(taskId, TaskStatus.PARSING, TaskStatus.EXTRACTING,
                TaskStage.PARSING, 30);
    }

    /** 从 EXTRACTING 单向进入 ASSEMBLING，写入第三阶段区间起点。 */
    public boolean enterAssembling(String taskId) {
        return transition(taskId, TaskStatus.EXTRACTING, TaskStatus.ASSEMBLING,
                TaskStage.ASSEMBLING, 80);
    }

    /** 独立调度线程更新心跳；SQL 不包含 deadline_at。 */
    public boolean heartbeat(String taskId) {
        return taskService.heartbeat(taskId) == 1;
    }

    /**
     * B4 唯一执行点之一：失败终态必须写 fail_code。DDL 无兜底。
     * <p>只有服务端故障与执行超时允许重新解析，输入问题不得自动改变口径。</p>
     */
    public boolean markFailed(String taskId, FailCode failCode) {
        if (failCode == null || failCode == FailCode.TASK_NOT_FINISHED
                || failCode == FailCode.RESULT_EXPIRED || failCode == FailCode.FILE_ALREADY_BOUND) {
            throw new IllegalArgumentException("该错误码不能写入任务失败终态");
        }
        boolean reanalyzable = failCode == FailCode.SERVER_ERROR
                || failCode == FailCode.EXECUTION_TIMEOUT;
        int affectedRows = taskService.failActive(taskId, failCode.name(), reanalyzable);
        if (affectedRows == 1) {
            log.info("任务进入失败终态，taskId={}，status={}，failCode={}，可重新解析={}",
                    taskId, TaskStatus.FAILED, failCode, reanalyzable);
            if (!reanalyzable && resourceCleanupService != null) {
                try {
                    resourceCleanupService.deleteFiles(taskId);
                    resourceCleanupService.deleteResult(taskId);
                } catch (RuntimeException cleanupException) {
                    log.error("不可重新解析任务的即时清理异常",
                            sanitizedException("失败任务清理异常类型:", cleanupException));
                }
            }
        }
        return affectedRows == 1;
    }

    /**
     * B5 唯一执行点：partial_reason 仅在 partial=1 时写入。DDL 无兜底。
     */
    public boolean markPartial(String taskId, DegradeAccumulator accumulator) {
        if (accumulator == null || !accumulator.partial() || accumulator.primaryReason() == null) {
            throw new IllegalArgumentException("部分结果累加器必须至少包含一个原因");
        }
        int affectedRows = taskService.markPartial(taskId, accumulator.primaryReason().name());
        if (affectedRows == 1) {
            log.info("任务部分结果标记完成，taskId={}，partialReason={}",
                    taskId, accumulator.primaryReason());
        }
        return affectedRows == 1;
    }

    /**
     * B4 与 B6 唯一执行点：先写 Redis，再由 MySQL CAS 提交可见性并顺延 expire_at。DDL 无兜底。
     *
     * @return 是否成功提交；false 表示删除、巡检或硬截止已经胜出
     */
    public boolean markSucceeded(String taskId, AnalysisResult result) {
        if (result == null) {
            throw new IllegalArgumentException("分析结果不能为空");
        }
        resultCache.write(taskId, result);
        log.info("分析结果草稿写入缓存完成，taskId={}", taskId);
        LocalDateTime currentTime = LocalDateTime.now(clock);
        final int affectedRows;
        try {
            affectedRows = taskService.succeed(taskId, currentTime, currentTime.plusHours(2L));
        } catch (RuntimeException exception) {
            deleteDraftAfterDatabaseException(taskId);
            throw exception;
        }
        if (affectedRows == 1) {
            log.info("任务进入成功终态，taskId={}，status={}", taskId, TaskStatus.SUCCEEDED);
            return true;
        }

        // Redis 草稿在 MySQL 提交失败时必须立刻删除，避免删除后结果重新出现。
        int timeoutRows;
        try {
            resultCache.delete(taskId);
            log.info("任务成功提交未生效，结果草稿已清理，taskId={}", taskId);
        } finally {
            // Redis 删除异常也不能阻止数据库把已过硬截止的 ASSEMBLING 任务收敛为超时。
            timeoutRows = taskService.failExpiredAssembling(taskId, currentTime);
        }
        if (timeoutRows == 1) {
            log.info("任务在成功提交前超过硬截止，taskId={}", taskId);
        }
        return false;
    }

    private void deleteDraftAfterDatabaseException(String taskId) {
        try {
            resultCache.delete(taskId);
        } catch (RuntimeException deleteException) {
            log.error("数据库提交异常后的结果草稿清理失败",
                    sanitizedException("结果草稿清理异常类型:", deleteException));
        }
    }

    /** 敏感异常仅保留类型和不含业务数据的原始调用栈，供日志安全定位。 */
    private IllegalStateException sanitizedException(String messagePrefix,
                                                      RuntimeException exception) {
        IllegalStateException sanitizedException = new IllegalStateException(
                messagePrefix + exception.getClass().getName());
        sanitizedException.setStackTrace(exception.getStackTrace());
        return sanitizedException;
    }

    private boolean transition(String taskId, TaskStatus expectedStatus, TaskStatus nextStatus,
                               TaskStage stage, int progress) {
        int affectedRows = taskService.transition(taskId, expectedStatus.name(), nextStatus.name(),
                stage.name(), progress);
        if (affectedRows == 1) {
            log.info("任务状态迁移完成，taskId={}，原状态={}，新状态={}，stage={}，progress={}",
                    taskId, expectedStatus, nextStatus, stage, progress);
        }
        return affectedRows == 1;
    }
}
