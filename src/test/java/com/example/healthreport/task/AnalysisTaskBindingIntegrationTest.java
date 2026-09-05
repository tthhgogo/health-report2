package com.example.healthreport.task;

import com.example.healthreport.HealthReportApplication;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实事务与行锁验证建任务原子性和并发唯一绑定。
 */
@SpringBootTest(classes = HealthReportApplication.class,
		properties = { "spring.datasource.url=jdbc:h2:mem:binding;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
				"spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
				"spring.datasource.password=", "spring.redis.host=127.0.0.1", "spring.main.web-application-type=none",
				"llm.dishtag.base-url=http://127.0.0.1", "llm.model-version-dishtag=test-dishtag-model",
				"llm.dishtag.api-key=test-dishtag-api-key", "llm.extraction.base-url=http://127.0.0.1",
				"llm.model-version-extraction=test-model", "llm.extraction.api-key=test-api-key" })
class AnalysisTaskBindingIntegrationTest {

	private static final String FILE_ID = "10000000-0000-0000-0000-000000000001";

	private static final String USER_ID = "case-sensitive-user";

	private static final String COMPANY_ID = "company-a";

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private AnalysisTaskCreateService taskCreateService;

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
	void shouldRollbackInsertedTaskWhenFileExpiresBetweenPrecheckAndBinding() {
		insertFile(LocalDateTime.now().plusMinutes(10), 1);
		taskCreateService.precheck(Collections.singletonList(FILE_ID), USER_ID, COMPANY_ID);
		jdbcTemplate.update("UPDATE ct_health_report_file SET expire_at=? WHERE file_id=?",
				LocalDateTime.now().minusSeconds(1), FILE_ID);

		assertThatThrownBy(
				() -> taskCreateService.createInTransaction(Collections.singletonList(FILE_ID), USER_ID, COMPANY_ID))
			.isInstanceOfSatisfying(HealthReportException.class,
					exception -> assertThat(exception.getFailCode()).isEqualTo(FailCode.FILE_EXPIRED));
		assertThat(count("ct_health_report_task")).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT task_id FROM ct_health_report_file WHERE file_id=?",
				String.class, FILE_ID))
			.isNull();
	}

	@Test
	void shouldRejectThirtyOnePagesBeforeCreatingTaskOrBindingFile() {
		insertFile(LocalDateTime.now().plusMinutes(10), 31);

		assertThatThrownBy(() -> taskCreateService.precheck(Collections.singletonList(FILE_ID), USER_ID, COMPANY_ID))
			.isInstanceOfSatisfying(HealthReportException.class,
					exception -> assertThat(exception.getFailCode()).isEqualTo(FailCode.PAGE_LIMIT_EXCEEDED));
		assertThat(count("ct_health_report_task")).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT task_id FROM ct_health_report_file WHERE file_id=?",
				String.class, FILE_ID))
			.isNull();
	}

	@Test
	void shouldAllowOnlyOneConcurrentTaskAndReturnWinningTaskId() throws Exception {
		insertFile(LocalDateTime.now().plusMinutes(10), 1);
		taskCreateService.precheck(Collections.singletonList(FILE_ID), USER_ID, COMPANY_ID);
		CountDownLatch startLatch = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<AttemptResult> first = executor.submit(() -> attemptCreate(startLatch));
			Future<AttemptResult> second = executor.submit(() -> attemptCreate(startLatch));
			startLatch.countDown();
			AttemptResult firstResult = first.get();
			AttemptResult secondResult = second.get();

			AttemptResult success = firstResult.taskId == null ? secondResult : firstResult;
			AttemptResult failure = firstResult.taskId == null ? firstResult : secondResult;
			assertThat(success.taskId).isNotNull();
			assertThat(failure.failCode).isEqualTo(FailCode.FILE_ALREADY_BOUND);
			assertThat(failure.boundTaskId).isEqualTo(success.taskId);
			assertThat(count("ct_health_report_task")).isEqualTo(1);
			assertThat(jdbcTemplate.queryForObject("SELECT task_id FROM ct_health_report_file WHERE file_id=?",
					String.class, FILE_ID))
				.isEqualTo(success.taskId);
		}
		finally {
			executor.shutdownNow();
		}
	}

	private AttemptResult attemptCreate(CountDownLatch startLatch) throws InterruptedException {
		startLatch.await();
		try {
			return AttemptResult.success(
					taskCreateService.createInTransaction(Collections.singletonList(FILE_ID), USER_ID, COMPANY_ID));
		}
		catch (HealthReportException exception) {
			return AttemptResult.failure(exception.getFailCode(), exception.getTaskId());
		}
	}

	private void insertFile(LocalDateTime expireAt, int pages) {
		jdbcTemplate.update("INSERT INTO ct_health_report_file "
				+ "(file_id,company_id,user_id,status,display_name,content_type,size_bytes,precheck_pages,content_hash,"
				+ "cloud_file_key,expire_at,create_by,update_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", FILE_ID,
				COMPANY_ID, USER_ID, FileStatus.UPLOADED.name(), "体检报告-00000000.pdf", "PDF", 1024L, pages,
				"0000000000000000000000000000000000000000000000000000000000000000", "health-report/synthetic", expireAt,
				"HEALTH_REPORT_API", "HEALTH_REPORT_API");
	}

	private int count(String tableName) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
	}

	/** 并发调用的最小安全结果，不携带任何文件元数据。 */
	private static class AttemptResult {

		private final String taskId;

		private final FailCode failCode;

		private final String boundTaskId;

		private AttemptResult(String taskId, FailCode failCode, String boundTaskId) {
			this.taskId = taskId;
			this.failCode = failCode;
			this.boundTaskId = boundTaskId;
		}

		private static AttemptResult success(String taskId) {
			return new AttemptResult(taskId, null, null);
		}

		private static AttemptResult failure(FailCode failCode, String boundTaskId) {
			return new AttemptResult(null, failCode, boundTaskId);
		}

	}

}
