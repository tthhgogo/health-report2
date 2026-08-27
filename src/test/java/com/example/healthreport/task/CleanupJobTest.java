package com.example.healthreport.task;

import com.example.healthreport.persistence.CtHealthReportFileService;
import com.example.healthreport.persistence.CtHealthReportTaskEntity;
import com.example.healthreport.persistence.CtHealthReportTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** R50/R51：成功立即删文件，可重解析失败在过期前保留。 */
class CleanupJobTest {

    private static final String TASK_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 26, 12, 0);
    private static final int BATCH_SIZE = 3;

    private CtHealthReportTaskService taskService;
    private CtHealthReportFileService fileService;
    private TaskResourceCleanupService cleanupService;
    private CleanupProperties properties;
    private CleanupJob cleanupJob;

    @BeforeEach
    void setUp() {
        taskService = mock(CtHealthReportTaskService.class);
        fileService = mock(CtHealthReportFileService.class);
        cleanupService = mock(TaskResourceCleanupService.class);
        properties = new CleanupProperties();
        properties.setBatchSize(BATCH_SIZE);
        Clock clock = Clock.fixed(Instant.parse("2026-08-26T12:00:00Z"), ZoneOffset.UTC);
        cleanupJob = new CleanupJob(taskService, fileService, cleanupService, properties, clock);
        when(fileService.findExpiredOrphans(NOW, BATCH_SIZE)).thenReturn(Collections.emptyList());
    }

    @Test
    void succeededTaskShouldDeleteFilesButKeepResultAndTaskUntilExpiry() {
        CtHealthReportTaskEntity taskEntity = task(TaskStatus.SUCCEEDED, false, NOW.plusHours(1));
        when(taskService.findCleanupCandidates(NOW, BATCH_SIZE)).thenReturn(Collections.singletonList(taskEntity));
        when(cleanupService.deleteFiles(TASK_ID)).thenReturn(true);

        cleanupJob.cleanup();

        verify(cleanupService).deleteFiles(TASK_ID);
        verify(cleanupService, never()).deleteResult(TASK_ID);
        verify(taskService, never()).deleteExpired(TASK_ID, NOW);
    }

    @Test
    void reanalyzableFailureBeforeExpiryShouldNotBeSelectedOrCleaned() {
        when(taskService.findCleanupCandidates(NOW, BATCH_SIZE)).thenReturn(Collections.emptyList());

        cleanupJob.cleanup();

        verify(cleanupService, never()).deleteFiles(TASK_ID);
        verify(cleanupService, never()).deleteResult(TASK_ID);
    }

    @Test
    void reanalyzableFailureAtExpiryShouldDeleteResourcesThenTaskRow() {
        CtHealthReportTaskEntity taskEntity = task(TaskStatus.FAILED, true, NOW);
        when(taskService.findCleanupCandidates(NOW, BATCH_SIZE)).thenReturn(Collections.singletonList(taskEntity));
        when(cleanupService.deleteFiles(TASK_ID)).thenReturn(true);
        when(cleanupService.deleteResult(TASK_ID)).thenReturn(true);

        cleanupJob.cleanup();

        verify(cleanupService).deleteFiles(TASK_ID);
        verify(cleanupService).deleteResult(TASK_ID);
        verify(taskService).deleteExpired(TASK_ID, NOW);
    }

    @Test
    void runningTaskPastExpiryMustKeepFilesResultAndTaskRow() {
        CtHealthReportTaskEntity taskEntity = task(TaskStatus.PARSING, false, NOW.minusMinutes(1L));
        when(taskService.findCleanupCandidates(NOW, BATCH_SIZE)).thenReturn(Collections.singletonList(taskEntity));

        cleanupJob.cleanup();

        verify(cleanupService, never()).deleteFiles(TASK_ID);
        verify(cleanupService, never()).deleteResult(TASK_ID);
        verify(taskService, never()).deleteExpired(TASK_ID, NOW);
    }

    @Test
    void queuedTaskPastExpiryMustKeepFilesResultAndTaskRow() {
        CtHealthReportTaskEntity taskEntity = task(TaskStatus.QUEUED, false, NOW.minusMinutes(1L));
        when(taskService.findCleanupCandidates(NOW, BATCH_SIZE)).thenReturn(Collections.singletonList(taskEntity));

        cleanupJob.cleanup();

        verify(cleanupService, never()).deleteFiles(TASK_ID);
        verify(cleanupService, never()).deleteResult(TASK_ID);
        verify(taskService, never()).deleteExpired(TASK_ID, NOW);
    }

    private CtHealthReportTaskEntity task(TaskStatus status, boolean reanalyzable, LocalDateTime expireAt) {
        CtHealthReportTaskEntity taskEntity = new CtHealthReportTaskEntity();
        taskEntity.setTaskId(TASK_ID);
        taskEntity.setStatus(status.name());
        taskEntity.setReanalyzable(reanalyzable);
        taskEntity.setExpireAt(expireAt);
        return taskEntity;
    }
}
