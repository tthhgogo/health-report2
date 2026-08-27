package com.example.healthreport.task;

import com.example.healthreport.persistence.CtHealthReportTaskService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 五分钟任务巡检入口。
 * <p>心跳、硬截止与 QUEUED 超时是三条独立判据，错误码不得合并。</p>
 */
@Slf4j
@Component
public class TaskSweepJob {

    private final CtHealthReportTaskService taskService;
    private final Clock clock;

    @Autowired
    public TaskSweepJob(CtHealthReportTaskService taskService) {
        this(taskService, Clock.systemDefaultZone());
    }

    /** 可注入时钟的构造器，仅用于确定性测试。 */
    public TaskSweepJob(CtHealthReportTaskService taskService, Clock clock) {
        this.taskService = taskService;
        this.clock = clock;
    }

    /** xxl-job 每五分钟调用一次；三条更新互不替代。 */
    @XxlJob("healthReportTaskSweepJob")
    public SweepResult sweep() {
        LocalDateTime currentTime = LocalDateTime.now(clock);
        int heartbeatFailed = taskService.failHeartbeatTimeout(currentTime.minusMinutes(15L));
        int deadlineFailed = taskService.failDeadlineTimeout(currentTime);
        int queuedFailed = taskService.failQueuedTimeout(currentTime.minusMinutes(5L));
        log.info("任务巡检完成：心跳超时={}，执行超时={}，排队超时={}",
                heartbeatFailed, deadlineFailed, queuedFailed);
        return new SweepResult(heartbeatFailed, deadlineFailed, queuedFailed);
    }

    /** 不含任务或用户标识的巡检计数结果。 */
    @Getter
    public static class SweepResult {
        private final int heartbeatFailed;
        private final int deadlineFailed;
        private final int queuedFailed;

        public SweepResult(int heartbeatFailed, int deadlineFailed, int queuedFailed) {
            this.heartbeatFailed = heartbeatFailed;
            this.deadlineFailed = deadlineFailed;
            this.queuedFailed = queuedFailed;
        }

    }
}
