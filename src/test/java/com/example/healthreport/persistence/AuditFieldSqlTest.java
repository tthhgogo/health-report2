package com.example.healthreport.persistence;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.healthreport.HealthReportApplication;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * R52：捕获 MyBatis-Plus 实际生成 SQL，确保数据库维护的时间列不被 Java 写入。
 */
@SpringBootTest(classes = HealthReportApplication.class,
		properties = { "spring.datasource.url=jdbc:h2:mem:audit_sql;MODE=MySQL;DB_CLOSE_DELAY=-1",
				"spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
				"spring.datasource.password=", "spring.sql.init.mode=never", "ocr.base-url=http://127.0.0.1",
				"ocr.model=test-ocr-model", "ocr.api-key=test-ocr-api-key", "ocr.max-encoded-image-bytes=8388608",
				"ocr.max-request-body-bytes=12582912", "ocr.request-encoding=JSON_BASE64",
				"ocr.accepts-encoded-bytes=true", "ocr.applies-exif-orientation=true",
				"ocr.returns-image-dimensions=true", "llm.dishtag.base-url=http://127.0.0.1",
				"llm.model-version-dishtag=test-dishtag-model", "llm.dishtag.api-key=test-dishtag-api-key",
				"llm.extraction.base-url=http://127.0.0.1", "llm.model-version-extraction=test-model",
				"llm.extraction.api-key=test-api-key" })
@Import(AuditFieldSqlTest.SqlCaptureConfiguration.class)
class AuditFieldSqlTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private CtHealthReportTaskMapper taskMapper;

	@Autowired
	private CtHealthReportFileMapper fileMapper;

	@Autowired
	private CtDishTagMapper dishTagMapper;

	@Autowired
	private SqlCaptureInterceptor sqlCaptureInterceptor;

	@BeforeEach
	void createTestTables() {
		jdbcTemplate.execute("DROP TABLE IF EXISTS ct_health_report_task");
		jdbcTemplate.execute("DROP TABLE IF EXISTS ct_health_report_file");
		jdbcTemplate.execute("DROP TABLE IF EXISTS ct_dish_tag");
		jdbcTemplate.execute("CREATE TABLE ct_health_report_task ("
				+ "task_id VARCHAR(36) PRIMARY KEY, create_time DATETIME DEFAULT CURRENT_TIMESTAMP, "
				+ "create_by VARCHAR(50), update_time DATETIME DEFAULT CURRENT_TIMESTAMP, update_by VARCHAR(50))");
		jdbcTemplate.execute("CREATE TABLE ct_health_report_file ("
				+ "file_id VARCHAR(36) PRIMARY KEY, create_time DATETIME DEFAULT CURRENT_TIMESTAMP, "
				+ "create_by VARCHAR(50), update_time DATETIME DEFAULT CURRENT_TIMESTAMP, update_by VARCHAR(50))");
		jdbcTemplate.execute("CREATE TABLE ct_dish_tag ("
				+ "company_id VARCHAR(64), dishes_id BIGINT, tag_hash VARCHAR(64), " + "enum_key VARCHAR(32), "
				+ "create_time DATETIME DEFAULT CURRENT_TIMESTAMP, create_by VARCHAR(50), "
				+ "update_time DATETIME DEFAULT CURRENT_TIMESTAMP, update_by VARCHAR(50))");
		sqlCaptureInterceptor.clear();
	}

	@Test
	void everyEntityInsertAndUpdateShouldExcludeDatabaseMaintainedTimeFields() throws Exception {
		LocalDateTime markerTime = LocalDateTime.of(2026, 1, 1, 0, 0);

		CtHealthReportTaskEntity taskEntity = new CtHealthReportTaskEntity();
		taskEntity.setTaskId("123e4567-e89b-12d3-a456-426614174000");
		taskEntity.setCreateTime(markerTime);
		taskEntity.setUpdateTime(markerTime);
		taskMapper.insert(taskEntity);
		taskEntity.setCreateBy("external-value");
		taskEntity.setUpdateBy("SYSTEM");
		taskMapper.updateById(taskEntity);

		CtHealthReportFileEntity fileEntity = new CtHealthReportFileEntity();
		fileEntity.setFileId("123e4567-e89b-12d3-a456-426614174001");
		fileEntity.setCreateTime(markerTime);
		fileEntity.setUpdateTime(markerTime);
		fileMapper.insert(fileEntity);
		fileEntity.setCreateBy("external-value");
		fileEntity.setUpdateBy("SYSTEM");
		fileMapper.updateById(fileEntity);

		CtDishTagEntity dishTagEntity = new CtDishTagEntity();
		dishTagEntity.setDishId(1L);
		dishTagEntity.setTagHash("hash");
		dishTagEntity.setEnumKey("ENUM_KEY");
		dishTagEntity.setCreateTime(markerTime);
		dishTagEntity.setUpdateTime(markerTime);
		dishTagMapper.insert(dishTagEntity);
		dishTagEntity.setCreateBy("external-value");
		dishTagEntity.setUpdateBy("SYSTEM");
		LambdaUpdateWrapper<CtDishTagEntity> updateWrapper = new LambdaUpdateWrapper<>();
		updateWrapper.eq(CtDishTagEntity::getDishId, 1L)
			.eq(CtDishTagEntity::getTagHash, "hash")
			.eq(CtDishTagEntity::getEnumKey, "ENUM_KEY");
		dishTagMapper.update(dishTagEntity, updateWrapper);

		List<String> generatedSqlList = sqlCaptureInterceptor.copySqlList();
		assertEquals(6, generatedSqlList.size());
		for (String generatedSql : generatedSqlList) {
			String lowerCaseSql = generatedSql.toLowerCase(Locale.ROOT);
			assertFalse(lowerCaseSql.contains("create_time"), generatedSql);
			assertFalse(lowerCaseSql.contains("update_time"), generatedSql);
			if (lowerCaseSql.trim().startsWith("update")) {
				assertFalse(lowerCaseSql.contains("create_by"), generatedSql);
			}
		}

		assertNeverStrategy(CtHealthReportTaskEntity.class);
		assertNeverStrategy(CtHealthReportFileEntity.class);
		assertNeverStrategy(CtDishTagEntity.class);
	}

	private static void assertNeverStrategy(Class<?> entityType) throws Exception {
		for (String fieldName : new String[] { "createTime", "updateTime" }) {
			Field field = entityType.getDeclaredField(fieldName);
			TableField tableField = field.getAnnotation(TableField.class);
			assertEquals(FieldStrategy.NEVER, tableField.insertStrategy());
			assertEquals(FieldStrategy.NEVER, tableField.updateStrategy());
		}
	}

	/**
	 * 将 SQL 捕获器作为测试插件交给 MyBatis，生产代码不新增任何配置类。
	 */
	@TestConfiguration
	static class SqlCaptureConfiguration {

		@Bean
		SqlCaptureInterceptor sqlCaptureInterceptor() {
			return new SqlCaptureInterceptor();
		}

	}

	/**
	 * 只记录 SQL 模板，不记录参数值，避免测试日志接触业务数据。
	 */
	@Intercepts(@Signature(type = StatementHandler.class, method = "prepare",
			args = { Connection.class, Integer.class }))
	static class SqlCaptureInterceptor implements Interceptor {

		private final List<String> sqlList = new ArrayList<>();

		@Override
		public Object intercept(Invocation invocation) throws Throwable {
			StatementHandler statementHandler = (StatementHandler) invocation.getTarget();
			synchronized (sqlList) {
				sqlList.add(statementHandler.getBoundSql().getSql());
			}
			return invocation.proceed();
		}

		@Override
		public Object plugin(Object target) {
			return Plugin.wrap(target, this);
		}

		@Override
		public void setProperties(Properties properties) {
			// 无配置项。
		}

		void clear() {
			synchronized (sqlList) {
				sqlList.clear();
			}
		}

		List<String> copySqlList() {
			synchronized (sqlList) {
				return new ArrayList<>(sqlList);
			}
		}

	}

}
