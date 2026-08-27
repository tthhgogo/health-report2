package com.example.healthreport.task;

import com.example.healthreport.persistence.CtHealthReportTaskService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** R41：心跳、deadline、QUEUED 三条巡检保持独立。 */
class TaskSweepJobTest {

    @Test
    void shouldRunThreeIndependentSweepUpdatesWithDifferentThresholds() {
        CtHealthReportTaskService taskService = mock(CtHealthReportTaskService.class);
        LocalDateTime now = LocalDateTime.of(2026, 8, 26, 12, 0);
        when(taskService.failHeartbeatTimeout(now.minusMinutes(15))).thenReturn(2);
        when(taskService.failDeadlineTimeout(now)).thenReturn(3);
        when(taskService.failQueuedTimeout(now.minusMinutes(5))).thenReturn(4);
        Clock clock = Clock.fixed(Instant.parse("2026-08-26T12:00:00Z"), ZoneOffset.UTC);
        TaskSweepJob sweepJob = new TaskSweepJob(taskService, clock);

        TaskSweepJob.SweepResult result = sweepJob.sweep();

        assertThat(result.getHeartbeatFailed()).isEqualTo(2);
        assertThat(result.getDeadlineFailed()).isEqualTo(3);
        assertThat(result.getQueuedFailed()).isEqualTo(4);
        verify(taskService).failHeartbeatTimeout(now.minusMinutes(15));
        verify(taskService).failDeadlineTimeout(now);
        verify(taskService).failQueuedTimeout(now.minusMinutes(5));
    }
}
