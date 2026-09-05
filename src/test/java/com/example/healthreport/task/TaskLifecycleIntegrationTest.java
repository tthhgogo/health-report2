package com.example.healthreport.task;

import com.example.healthreport.HealthReportApplication;
import com.example.healthreport.cache.AnalysisModules;
import com.example.healthreport.cache.AnalysisResult;
import com.example.healthreport.cache.TaskResultCache;
import com.example.healthreport.persistence.CtHealthReportTaskEntity;
import com.example.healthreport.persistence.CtHealthReportTaskService;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.IdCanonicalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 使用真实 MyBatis/H2 验证状态 SQL、巡检错误码、成功有效期和清理候选矩阵。
 */
@SpringBootTest(classes = HealthReportApplication.class,
		properties = { "spring.datasource.url=jdbc:h2:mem:lifecycle;MODE=MySQL;DB_CLOSE_DELAY=-1",
				"spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
				"spring.datasource.password=", "spring.main.web-application-type=none",
				"llm.dishtag.base-url=http://127.0.0.1",
				"llm.model-version-dishtag=test-dishtag-model", "llm.dishtag.api-key=test-dishtag-api-key",
				"llm.extraction.base-url=http://127.0.0.1", "llm.model-version-extraction=test-model",
				"llm.extraction.api-key=test-api-key" })
class TaskLifecycleIntegrationTest {

	private static final String TASK_ID = "20000000-0000-0000-0000-000000000001";

	private static final String USER_ID = "CaseSensitiveUser";

	private static final String COMPANY_ID = "company-a";

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TaskStateService stateService;

	@Autowired
	private CtHealthReportTaskService taskService;

	@Autowired
	private TaskDeleteService deleteService;

	@MockBean
	private TaskResultCache resultCache;

	@BeforeEach
	void createSchema() {
		jdbcTemplate.execute("DROP TABLE IF EXISTS ct_health_report_file");
		jdbcTemplate.execute("DROP TABLE IF EXISTS ct_health_report_task");
		jdbcTemplate.execute("CREATE TABLE ct_health_report_task ("
				+ "task_id VARCHAR(36) PRIMARY KEY, company_id VARCHAR(64) NOT NULL, user_id VARCHAR(64) NOT NULL, status VARCHAR(16) NOT NULL, "
				+ "stage VARCHAR(16), progress TINYINT NOT NULL, fail_code VARCHAR(32), "
				+ "reanalyzable TINYINT NOT NULL, partial TINYINT NOT NULL, partial_reason VARCHAR(32), "
				+ "heartbeat_at DATETIME, deadline_at DATETIME, expire_at DATETIME NOT NULL, deleted_at DATETIME, "
				+ "version INT NOT NULL, create_time DATETIME DEFAULT CURRENT_TIMESTAMP, create_by VARCHAR(50), "
				+ "update_time DATETIME DEFAULT CURRENT_TIMESTAMP, update_by VARCHAR(50))");
		jdbcTemplate.execute("CREATE TABLE ct_health_report_file ("
				+ "file_id VARCHAR(36) PRIMARY KEY, company_id VARCHAR(64) NOT NULL, user_id VARCHAR(64) NOT NULL, task_id VARCHAR(36), "
				+ "file_index INT, status VARCHAR(16) NOT NULL, display_name VARCHAR(64) NOT NULL, "
				+ "content_type VARCHAR(64) NOT NULL, size_bytes BIGINT NOT NULL, precheck_pages INT NOT NULL, "
				+ "content_hash CHAR(64) NOT NULL, cloud_file_key VARCHAR(255) NOT NULL, expire_at DATETIME NOT NULL, "
				+ "create_time DATETIME DEFAULT CURRENT_TIMESTAMP, create_by VARCHAR(50), "
				+ "update_time DATETIME DEFAULT CURRENT_TIMESTAMP, update_by VARCHAR(50))");
	}

	@Test
	void shouldClaimOnceAndExposeOnlyThreeStages() {
		insertTask(TASK_ID, TaskStatus.QUEUED, TaskStage.UPLOADING, 0, LocalDateTime.now().plusMinutes(30), null, null,
				false, LocalDateTime.now());

		assertThat(stateService.claim(TASK_ID)).isTrue();
		assertThat(stateService.claim(TASK_ID)).isFalse();
		assertTask(TASK_ID, TaskStatus.PARSING, TaskStage.PARSING, 30);
		assertThat(stateService.enterExtracting(TASK_ID)).isTrue();
		assertTask(TASK_ID, TaskStatus.EXTRACTING, TaskStage.PARSING, 30);
		assertThat(stateService.enterAssembling(TASK_ID)).isTrue();
		assertTask(TASK_ID, TaskStatus.ASSEMBLING, TaskStage.ASSEMBLING, 80);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT deadline_at IS NOT NULL FROM ct_health_report_task " + "WHERE task_id=?", Boolean.class,
				TASK_ID))
			.isTrue();
	}

	@Test
	void successShouldExtendExpiryAndExpiredCasShouldFailWithExecutionTimeout() {
		LocalDateTime initialExpiry = LocalDateTime.now().plusMinutes(30);
		insertTask(TASK_ID, TaskStatus.ASSEMBLING, TaskStage.ASSEMBLING, 80, initialExpiry, LocalDateTime.now(),
				LocalDateTime.now().plusMinutes(2), false, LocalDateTime.now());
		AnalysisResult result = AnalysisResult.create(new DegradeAccumulator(), 0, 0, AnalysisModules.empty());

		assertThat(stateService.markSucceeded(TASK_ID, result)).isTrue();
		CtHealthReportTaskEntity succeeded = taskService.findByTaskId(TASK_ID);
		assertThat(succeeded.getStatus()).isEqualTo(TaskStatus.SUCCEEDED.name());
		assertThat(succeeded.getProgress()).isEqualTo(100);
		assertThat(succeeded.getExpireAt()).isAfter(initialExpiry.plusMinutes(60));

		String expiredId = "20000000-0000-0000-0000-000000000002";
		insertTask(expiredId, TaskStatus.ASSEMBLING, TaskStage.ASSEMBLING, 80, initialExpiry, LocalDateTime.now(),
				LocalDateTime.now().minusSeconds(1), false, LocalDateTime.now());
		assertThat(stateService.markSucceeded(expiredId, result)).isFalse();
		CtHealthReportTaskEntity failed = taskService.findByTaskId(expiredId);
		assertThat(failed.getStatus()).isEqualTo(TaskStatus.FAILED.name());
		assertThat(failed.getFailCode()).isEqualTo(com.example.healthreport.support.FailCode.EXECUTION_TIMEOUT);
		assertThat(failed.getReanalyzable()).isTrue();
	}

	@Test
	void succeededTaskShouldRemainReadableAtMinuteThirtyOne() {
		Instant successInstant = Instant.parse("2026-08-26T04:00:00Z");
		Clock successClock = Clock.fixed(successInstant, ZoneOffset.UTC);
		LocalDateTime successTime = LocalDateTime.now(successClock);
		insertTask(TASK_ID, TaskStatus.ASSEMBLING, TaskStage.ASSEMBLING, 80, successTime.plusMinutes(30L), successTime,
				successTime.plusMinutes(2L), false, successTime);
		AnalysisResult expectedResult = AnalysisResult.create(new DegradeAccumulator(), 0, 0, AnalysisModules.empty());
		TaskStateService fixedStateService = new TaskStateService(taskService, resultCache,
				mock(TaskResourceCleanupService.class), successClock);
		when(resultCache.read(TASK_ID)).thenReturn(expectedResult);

		assertThat(fixedStateService.markSucceeded(TASK_ID, expectedResult)).isTrue();

		Clock minuteThirtyOneClock = Clock.fixed(successInstant.plusSeconds(31L * 60L), ZoneOffset.UTC);
		TaskOwnershipGuard ownershipGuard = new TaskOwnershipGuard(taskService, new IdCanonicalizer(),
				minuteThirtyOneClock);
		TaskQueryService queryService = new TaskQueryService(ownershipGuard, resultCache);
		assertThat(queryService.getResult(TASK_ID, USER_ID, COMPANY_ID)).isSameAs(expectedResult);
	}

	@Test
	void sweepShouldKeepHeartbeatAndDeadlineFailureCodesDistinct() {
		LocalDateTime now = LocalDateTime.now();
		String heartbeatId = "20000000-0000-0000-0000-000000000003";
		String deadlineId = "20000000-0000-0000-0000-000000000004";
		String queuedId = "20000000-0000-0000-0000-000000000005";
		insertTask(heartbeatId, TaskStatus.PARSING, TaskStage.PARSING, 30, now.plusMinutes(30), now.minusMinutes(16),
				now.plusMinutes(1), false, now);
		insertTask(deadlineId, TaskStatus.EXTRACTING, TaskStage.PARSING, 30, now.plusMinutes(30), now,
				now.minusSeconds(1), false, now);
		insertTask(queuedId, TaskStatus.QUEUED, TaskStage.UPLOADING, 0, now.plusMinutes(30), null, null, false,
				now.minusMinutes(6));

		taskService.failHeartbeatTimeout(now.minusMinutes(15));
		taskService.failDeadlineTimeout(now);
		taskService.failQueuedTimeout(now.minusMinutes(5));

		assertFailure(heartbeatId, "SERVER_ERROR");
		assertFailure(deadlineId, "EXECUTION_TIMEOUT");
		assertFailure(queuedId, "SERVER_ERROR");
	}

	@Test
	void cleanupCandidatesShouldRetainReanalyzableFailureUntilExpiry() {
		LocalDateTime now = LocalDateTime.now();
		String retainedId = "20000000-0000-0000-0000-000000000006";
		String expiredId = "20000000-0000-0000-0000-000000000007";
		insertTask(retainedId, TaskStatus.FAILED, TaskStage.PARSING, 30, now.plusMinutes(1), now, now.plusMinutes(1),
				true, now);
		insertTask(expiredId, TaskStatus.FAILED, TaskStage.PARSING, 30, now.minusSeconds(1), now, now.minusMinutes(1),
				true, now);

		List<CtHealthReportTaskEntity> candidateList = taskService.findCleanupCandidates(now, 500);

		assertThat(candidateList).extracting(CtHealthReportTaskEntity::getTaskId)
			.contains(expiredId)
			.doesNotContain(retainedId);
	}

	@Test
	void cleanupSqlShouldKeepExpiredRunningTaskRows() {
		LocalDateTime now = LocalDateTime.now();
		String parsingId = "20000000-0000-0000-0000-000000000008";
		String queuedId = "20000000-0000-0000-0000-000000000009";
		insertTask(parsingId, TaskStatus.PARSING, TaskStage.PARSING, 30, now.minusSeconds(1L), now, now.plusMinutes(1L),
				false, now);
		insertTask(queuedId, TaskStatus.QUEUED, TaskStage.UPLOADING, 0, now.minusSeconds(1L), null, null, false, now);

		List<CtHealthReportTaskEntity> candidateList = taskService.findCleanupCandidates(now, 500);

		assertThat(candidateList).extracting(CtHealthReportTaskEntity::getTaskId).doesNotContain(parsingId, queuedId);
		assertThat(taskService.deleteExpired(parsingId, now)).isZero();
		assertThat(taskService.deleteExpired(queuedId, now)).isZero();
		assertThat(taskService.findByTaskId(parsingId)).isNotNull();
		assertThat(taskService.findByTaskId(queuedId)).isNotNull();
	}

	@Test
	void concurrentDeleteAndSuccessWriteBackShouldNeverRestoreVisibleResult() throws Exception {
		LocalDateTime now = LocalDateTime.now();
		insertTask(TASK_ID, TaskStatus.ASSEMBLING, TaskStage.ASSEMBLING, 80, now.plusMinutes(30), now,
				now.plusMinutes(2), false, now);
		AnalysisResult result = AnalysisResult.create(new DegradeAccumulator(), 0, 0, AnalysisModules.empty());
		AtomicReference<AnalysisResult> cachedResult = new AtomicReference<AnalysisResult>();
		doAnswer(invocation -> {
			cachedResult.set(invocation.getArgument(1));
			return null;
		}).when(resultCache).write(any(String.class), any(AnalysisResult.class));
		doAnswer(invocation -> {
			cachedResult.set(null);
			return null;
		}).when(resultCache).delete(any(String.class));

		ExecutorService raceExecutor = Executors.newFixedThreadPool(2);
		CountDownLatch startLatch = new CountDownLatch(1);
		try {
			Future<?> successFuture = raceExecutor.submit(() -> {
				awaitStart(startLatch);
				stateService.markSucceeded(TASK_ID, result);
			});
			Future<?> deleteFuture = raceExecutor.submit(() -> {
				awaitStart(startLatch);
				deleteService.delete(TASK_ID, USER_ID, COMPANY_ID);
			});
			startLatch.countDown();
			successFuture.get();
			deleteFuture.get();
		}
		finally {
			raceExecutor.shutdown();
		}

		CtHealthReportTaskEntity taskEntity = taskService.findByTaskId(TASK_ID);
		assertThat(taskEntity.getDeletedAt()).isNotNull();
		assertThat(cachedResult.get()).isNull();
	}

	private void assertTask(String taskId, TaskStatus status, TaskStage stage, int progress) {
		CtHealthReportTaskEntity taskEntity = taskService.findByTaskId(taskId);
		assertThat(taskEntity.getStatus()).isEqualTo(status.name());
		assertThat(taskEntity.getStage()).isEqualTo(stage.name());
		assertThat(taskEntity.getProgress()).isEqualTo(progress);
		assertThat(taskEntity.getStage()).isNotEqualTo(TaskStatus.EXTRACTING.name());
	}

	private void assertFailure(String taskId, String failCode) {
		CtHealthReportTaskEntity taskEntity = taskService.findByTaskId(taskId);
		assertThat(taskEntity.getStatus()).isEqualTo(TaskStatus.FAILED.name());
		assertThat(taskEntity.getFailCode().name()).isEqualTo(failCode);
		assertThat(taskEntity.getReanalyzable()).isTrue();
	}

	private void awaitStart(CountDownLatch startLatch) {
		try {
			startLatch.await();
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("并发测试线程被中断");
		}
	}

	@Test
	void cleanupCandidatesShouldBeCappedAndPutExpiredWorkFirst() {
		LocalDateTime now = LocalDateTime.now();
		// 三个非过期 SUCCEEDED：文件早在成功那一刻就删掉了，本轮无事可做。
		for (int index = 0; index < 3; index++) {
			insertTask("20000000-0000-0000-0000-0000000001" + (10 + index), TaskStatus.SUCCEEDED, TaskStage.ASSEMBLING,
					100, now.plusHours(2L), now, now.plusMinutes(1L), false, now);
		}
		// 两个已过期：真正需要删结果与任务行的。
		String expiredOne = "20000000-0000-0000-0000-000000000120";
		String expiredTwo = "20000000-0000-0000-0000-000000000121";
		insertTask(expiredOne, TaskStatus.SUCCEEDED, TaskStage.ASSEMBLING, 100, now.minusMinutes(2L), now,
				now.minusMinutes(1L), false, now);
		insertTask(expiredTwo, TaskStatus.SUCCEEDED, TaskStage.ASSEMBLING, 100, now.minusMinutes(1L), now,
				now.minusMinutes(1L), false, now);

		List<CtHealthReportTaskEntity> candidateList = taskService.findCleanupCandidates(now, 2);

		// 单轮上限生效；且 ORDER BY expire_at 让有活干的排在无所事事的前面，
		// 否则积压时真正该清的会被一批空转任务永久挤出候选。
		assertThat(candidateList).hasSize(2);
		assertThat(candidateList).extracting(CtHealthReportTaskEntity::getTaskId)
			.containsExactly(expiredOne, expiredTwo);
	}

	@Test
	void truncatedRoundShouldStillDrainTheBacklogAcrossRounds() {
		LocalDateTime now = LocalDateTime.now();
		String first = "20000000-0000-0000-0000-000000000130";
		String second = "20000000-0000-0000-0000-000000000131";
		insertTask(first, TaskStatus.FAILED, TaskStage.PARSING, 30, now.minusMinutes(3L), now, now.minusMinutes(2L),
				false, now);
		insertTask(second, TaskStatus.FAILED, TaskStage.PARSING, 30, now.minusMinutes(2L), now, now.minusMinutes(2L),
				false, now);

		// 第一轮只拿到一条，删掉它之后第二轮拿到另一条：截断只推迟，不丢。
		List<CtHealthReportTaskEntity> firstRound = taskService.findCleanupCandidates(now, 1);
		assertThat(firstRound).extracting(CtHealthReportTaskEntity::getTaskId).containsExactly(first);
		assertThat(taskService.deleteExpired(first, now)).isEqualTo(1);

		List<CtHealthReportTaskEntity> secondRound = taskService.findCleanupCandidates(now, 1);
		assertThat(secondRound).extracting(CtHealthReportTaskEntity::getTaskId).containsExactly(second);
	}

	@Test
	void nonPositiveBatchSizeShouldBeRejectedInsteadOfSilentlyScanningEverything() {
		LocalDateTime now = LocalDateTime.now();

		assertThatThrownBy(() -> taskService.findCleanupCandidates(now, 0))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private void insertTask(String taskId, TaskStatus status, TaskStage stage, int progress, LocalDateTime expireAt,
			LocalDateTime heartbeatAt, LocalDateTime deadlineAt, boolean reanalyzable, LocalDateTime createTime) {
		jdbcTemplate.update("INSERT INTO ct_health_report_task "
				+ "(task_id,company_id,user_id,status,stage,progress,reanalyzable,partial,heartbeat_at,deadline_at,"
				+ "expire_at,version,create_time,create_by,update_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", taskId,
				COMPANY_ID, USER_ID, status.name(), stage.name(), progress, reanalyzable, false, heartbeatAt,
				deadlineAt, expireAt, 0, createTime, "HEALTH_REPORT_API", "HEALTH_REPORT_API");
	}

}
