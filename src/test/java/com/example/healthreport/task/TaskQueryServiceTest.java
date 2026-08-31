package com.example.healthreport.task;

import com.example.healthreport.api.dto.TaskStatusResponse;
import com.example.healthreport.cache.AnalysisModules;
import com.example.healthreport.cache.AnalysisResult;
import com.example.healthreport.cache.TaskResultCache;
import com.example.healthreport.persistence.CtHealthReportTaskEntity;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import com.example.healthreport.support.OwnershipException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** R33a/R40a：轮询与结果读取顺序。 */
class TaskQueryServiceTest {

	private static final String TASK_ID = "123e4567-e89b-12d3-a456-426614174000";

	private static final String USER_ID = "CaseSensitiveUser";

	private static final String COMPANY_ID = "company-a";

	private TaskOwnershipGuard ownershipGuard;

	private TaskResultCache resultCache;

	private TaskQueryService queryService;

	@BeforeEach
	void setUp() {
		ownershipGuard = mock(TaskOwnershipGuard.class);
		resultCache = mock(TaskResultCache.class);
		queryService = new TaskQueryService(ownershipGuard, resultCache);
	}

	@Test
	void queuedTaskShouldReturnUploadingAtZero() {
		CtHealthReportTaskEntity taskEntity = task(TaskStatus.QUEUED, TaskStage.UPLOADING, 0);
		when(ownershipGuard.assertOwned(TASK_ID, USER_ID, COMPANY_ID)).thenReturn(taskEntity);

		TaskStatusResponse response = queryService.getStatus(TASK_ID, USER_ID, COMPANY_ID);

		assertThat(response.getStatus()).isEqualTo(TaskStatus.QUEUED.name());
		assertThat(response.getStage()).isEqualTo(TaskStage.UPLOADING.name());
		assertThat(response.getProgress()).isZero();
	}

	@Test
	void unfinishedTaskShouldReturnConflictWithoutReadingRedis() {
		when(ownershipGuard.assertOwned(TASK_ID, USER_ID, COMPANY_ID))
			.thenReturn(task(TaskStatus.EXTRACTING, TaskStage.PARSING, 30));

		assertThatThrownBy(() -> queryService.getResult(TASK_ID, USER_ID, COMPANY_ID)).isInstanceOfSatisfying(
				HealthReportException.class,
				exception -> assertThat(exception.getFailCode()).isEqualTo(FailCode.TASK_NOT_FINISHED));
		verify(resultCache, never()).read(TASK_ID);
	}

	@Test
	void succeededTaskShouldReturnCachedResult() {
		CtHealthReportTaskEntity taskEntity = task(TaskStatus.SUCCEEDED, TaskStage.ASSEMBLING, 100);
		taskEntity.setExpireAt(LocalDateTime.of(2026, 8, 26, 14, 0));
		AnalysisResult expectedResult = AnalysisResult.create(new DegradeAccumulator(), 0, 0, AnalysisModules.empty());
		when(ownershipGuard.assertOwned(TASK_ID, USER_ID, COMPANY_ID)).thenReturn(taskEntity);
		when(resultCache.read(TASK_ID)).thenReturn(expectedResult);

		AnalysisResult actualResult = queryService.getResult(TASK_ID, USER_ID, COMPANY_ID);

		assertThat(actualResult).isSameAs(expectedResult);
		verify(resultCache).read(TASK_ID);
	}

	@Test
	void succeededTaskWithMissingCacheShouldReturnResultExpired() {
		CtHealthReportTaskEntity taskEntity = task(TaskStatus.SUCCEEDED, TaskStage.ASSEMBLING, 100);
		taskEntity.setExpireAt(LocalDateTime.of(2026, 8, 26, 14, 0));
		when(ownershipGuard.assertOwned(TASK_ID, USER_ID, COMPANY_ID)).thenReturn(taskEntity);
		when(resultCache.read(TASK_ID)).thenReturn(null);

		assertThatThrownBy(() -> queryService.getResult(TASK_ID, USER_ID, COMPANY_ID))
			.isInstanceOfSatisfying(OwnershipException.class, exception -> {
				assertThat(exception.getHttpStatus()).isEqualTo(404);
				assertThat(exception.getFailCode()).isEqualTo(FailCode.RESULT_EXPIRED);
			});
		verify(resultCache).read(TASK_ID);
	}

	private CtHealthReportTaskEntity task(TaskStatus status, TaskStage stage, int progress) {
		CtHealthReportTaskEntity taskEntity = new CtHealthReportTaskEntity();
		taskEntity.setTaskId(TASK_ID);
		taskEntity.setStatus(status.name());
		taskEntity.setStage(stage.name());
		taskEntity.setProgress(progress);
		return taskEntity;
	}

}
