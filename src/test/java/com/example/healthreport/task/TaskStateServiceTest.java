package com.example.healthreport.task;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.healthreport.cache.AnalysisModules;
import com.example.healthreport.cache.AnalysisResult;
import com.example.healthreport.cache.TaskResultCache;
import com.example.healthreport.persistence.CtHealthReportTaskService;
import com.example.healthreport.support.FailCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R35~R37：状态 CAS 与 Redis/MySQL 固定提交顺序。 */
class TaskStateServiceTest {

    private static final String TASK_ID = "123e4567-e89b-12d3-a456-426614174000";

    private CtHealthReportTaskService taskService;
    private TaskResultCache resultCache;
    private TaskStateService stateService;

    @BeforeEach
    void setUp() {
        taskService = mock(CtHealthReportTaskService.class);
        resultCache = mock(TaskResultCache.class);
        stateService = new TaskStateService(taskService, resultCache);
    }

    @Test
    void shouldWriteRedisBeforeSuccessCas() {
        AnalysisResult result = emptyResult();
        when(taskService.succeed(eq(TASK_ID), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);

        assertThat(stateService.markSucceeded(TASK_ID, result)).isTrue();

        InOrder order = inOrder(resultCache, taskService);
        order.verify(resultCache).write(TASK_ID, result);
        order.verify(taskService).succeed(eq(TASK_ID), any(LocalDateTime.class), any(LocalDateTime.class));
        order.verifyNoMoreInteractions();
    }

    @Test
    void expiredSuccessCasShouldDeleteRedisThenWriteTimeoutFailure() {
        AnalysisResult result = emptyResult();
        when(taskService.succeed(eq(TASK_ID), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(0);
        when(taskService.failExpiredAssembling(eq(TASK_ID), any(LocalDateTime.class))).thenReturn(1);

        assertThat(stateService.markSucceeded(TASK_ID, result)).isFalse();

        InOrder order = inOrder(resultCache, taskService);
        order.verify(resultCache).write(TASK_ID, result);
        order.verify(taskService).succeed(eq(TASK_ID), any(LocalDateTime.class), any(LocalDateTime.class));
        order.verify(resultCache).delete(TASK_ID);
        order.verify(taskService).failExpiredAssembling(eq(TASK_ID), any(LocalDateTime.class));
    }

    @Test
    void databaseExceptionAfterRedisWriteShouldDeleteDraftAndPropagate() {
        AnalysisResult result = emptyResult();
        when(taskService.succeed(eq(TASK_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenThrow(new IllegalStateException("synthetic database failure"));

        assertThatThrownBy(() -> stateService.markSucceeded(TASK_ID, result))
                .isInstanceOf(IllegalStateException.class);

        InOrder order = inOrder(resultCache, taskService);
        order.verify(resultCache).write(TASK_ID, result);
        order.verify(taskService).succeed(eq(TASK_ID), any(LocalDateTime.class), any(LocalDateTime.class));
        order.verify(resultCache).delete(TASK_ID);
    }

    @Test
    void serverFailureShouldBeReanalyzableButInputFailureShouldNot() {
        when(taskService.failActive(TASK_ID, FailCode.SERVER_ERROR.name(), true)).thenReturn(1);
        when(taskService.failActive(TASK_ID, FailCode.UNREADABLE.name(), false)).thenReturn(1);
        when(taskService.failActive(TASK_ID, FailCode.IMAGE_TOO_LARGE.name(), false)).thenReturn(1);

        assertThat(stateService.markFailed(TASK_ID, FailCode.SERVER_ERROR)).isTrue();
        assertThat(stateService.markFailed(TASK_ID, FailCode.UNREADABLE)).isTrue();
        assertThat(stateService.markFailed(TASK_ID, FailCode.IMAGE_TOO_LARGE)).isTrue();

        verify(taskService).failActive(TASK_ID, FailCode.SERVER_ERROR.name(), true);
        verify(taskService).failActive(TASK_ID, FailCode.UNREADABLE.name(), false);
        verify(taskService).failActive(TASK_ID, FailCode.IMAGE_TOO_LARGE.name(), false);
    }

    @Test
    void nonReanalyzableFailureShouldImmediatelyCleanFilesAndResult() {
        TaskResourceCleanupService cleanupService = mock(TaskResourceCleanupService.class);
        stateService = new TaskStateService(taskService, resultCache, cleanupService);
        when(taskService.failActive(TASK_ID, FailCode.UNREADABLE.name(), false)).thenReturn(1);

        assertThat(stateService.markFailed(TASK_ID, FailCode.UNREADABLE)).isTrue();

        InOrder order = inOrder(taskService, cleanupService);
        order.verify(taskService).failActive(TASK_ID, FailCode.UNREADABLE.name(), false);
        order.verify(cleanupService).deleteFiles(TASK_ID);
        order.verify(cleanupService).deleteResult(TASK_ID);
    }

    @Test
    void partialPersistenceShouldUseAccumulatorSeverityInsteadOfLastHit() {
        DegradeAccumulator accumulator = new DegradeAccumulator();
        accumulator.recordPageTruncated();
        accumulator.recordAllergenSuspectMiss();
        when(taskService.markPartial(TASK_ID,
                com.example.healthreport.support.PartialReason.PAGE_TRUNCATED.name())).thenReturn(1);

        assertThat(stateService.markPartial(TASK_ID, accumulator)).isTrue();

        verify(taskService).markPartial(TASK_ID,
                com.example.healthreport.support.PartialReason.PAGE_TRUNCATED.name());
    }

    @Test
    void successfulLifecycleShouldLogEveryStateAndCrossStoreCheckpoint() {
        when(taskService.claim(eq(TASK_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1);
        when(taskService.transition(TASK_ID, TaskStatus.PARSING.name(),
                TaskStatus.EXTRACTING.name(), TaskStage.PARSING.name(), 30)).thenReturn(1);
        when(taskService.transition(TASK_ID, TaskStatus.EXTRACTING.name(),
                TaskStatus.ASSEMBLING.name(), TaskStage.ASSEMBLING.name(), 80)).thenReturn(1);
        DegradeAccumulator accumulator = new DegradeAccumulator();
        accumulator.recordPageTruncated();
        when(taskService.markPartial(TASK_ID,
                com.example.healthreport.support.PartialReason.PAGE_TRUNCATED.name())).thenReturn(1);
        when(taskService.succeed(eq(TASK_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1);

        Logger logger = (Logger) LoggerFactory.getLogger(TaskStateService.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            assertThat(stateService.claim(TASK_ID)).isTrue();
            assertThat(stateService.enterExtracting(TASK_ID)).isTrue();
            assertThat(stateService.enterAssembling(TASK_ID)).isTrue();
            assertThat(stateService.markPartial(TASK_ID, accumulator)).isTrue();
            assertThat(stateService.markSucceeded(TASK_ID, emptyResult())).isTrue();

            String renderedLog = renderedLog(appender);
            assertThat(renderedLog)
                    .contains("任务领取 CAS 成功", "原状态=QUEUED，新状态=PARSING")
                    .contains("原状态=PARSING，新状态=EXTRACTING")
                    .contains("原状态=EXTRACTING，新状态=ASSEMBLING")
                    .contains("partialReason=PAGE_TRUNCATED")
                    .contains("分析结果草稿写入缓存完成")
                    .contains("status=SUCCEEDED")
                    .contains(TASK_ID);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    private String renderedLog(ListAppender<ILoggingEvent> appender) {
        StringBuilder renderedLog = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            renderedLog.append(event.getFormattedMessage()).append('\n');
        }
        return renderedLog.toString();
    }

    private AnalysisResult emptyResult() {
        return AnalysisResult.create(new DegradeAccumulator(), 0, 0, AnalysisModules.empty());
    }
}
