package com.example.healthreport.task;

import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import org.junit.jupiter.api.Test;

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
                .isInstanceOfSatisfying(HealthReportException.class,
                        exception -> assertThat(exception.getFailCode()).isEqualTo(FailCode.SERVER_ERROR));
        verify(stateService).markFailed(taskId, FailCode.SERVER_ERROR);
    }
}
