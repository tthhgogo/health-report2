package com.example.healthreport.task;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.BusinessException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** R34：事务提交后的线程池拒绝必须立即写失败终态并返回 SERVER_ERROR。 */
class AnalysisTaskExecutionServiceTest {

    @Test
    void rejectedSubmissionShouldFailQueuedTaskAndReturnServerError() {
        String taskId = "123e4567-e89b-12d3-a456-426614174000";
        ThreadPoolExecutor executor = mock(ThreadPoolExecutor.class);
        AnalysisTaskWorker worker = mock(AnalysisTaskWorker.class);
        TaskStateService stateService = mock(TaskStateService.class);
        doThrow(new RejectedExecutionException()).when(executor).execute(any(Runnable.class));
        AnalysisTaskExecutionService executionService = new AnalysisTaskExecutionService(
                executor, worker, stateService);

        assertThatThrownBy(() -> executionService.submit(taskId))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getFailCode()).isEqualTo(FailCode.SERVER_ERROR));
        verify(stateService).markFailed(taskId, FailCode.SERVER_ERROR);
    }

    @Test
    void successfulSubmissionShouldLogCreationAndQueueCheckpointsSeparately() {
        String taskId = "123e4567-e89b-12d3-a456-426614174000";
        ThreadPoolExecutor executor = mock(ThreadPoolExecutor.class);
        AnalysisTaskExecutionService executionService = new AnalysisTaskExecutionService(
                executor, mock(AnalysisTaskWorker.class), mock(TaskStateService.class));
        Logger logger = (Logger) LoggerFactory.getLogger(AnalysisTaskExecutionService.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            executionService.submit(taskId);

            StringBuilder renderedLog = new StringBuilder();
            for (ILoggingEvent event : appender.list) {
                renderedLog.append(event.getFormattedMessage()).append('\n');
            }
            assertThat(renderedLog.toString())
                    .contains("任务创建成功，taskId=" + taskId)
                    .contains("任务投递成功并已入队，taskId=" + taskId);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }
}
