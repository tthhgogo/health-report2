package com.example.healthreport.persistence;

import com.example.healthreport.HealthReportApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** R18a：通过真实 Mapper SQL 验证 30 天清理边界，而不是只检查注解字符串。 */
@SpringBootTest(classes = HealthReportApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:dish_tag_cleanup;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never",
        "llm.dishtag.base-url=http://127.0.0.1",
        "llm.model-version-dishtag=test-dishtag-model",
        "llm.dishtag.api-key=test-dishtag-api-key",
        "llm.extraction.base-url=http://127.0.0.1",
        "llm.model-version-extraction=test-model",
        "llm.extraction.api-key=test-api-key"
})
class DishTagCleanupBoundaryIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CtDishTagService dishTagService;

    @BeforeEach
    void createTable() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS ct_dish_tag");
        jdbcTemplate.execute("CREATE TABLE ct_dish_tag ("
                + "dish_id BIGINT PRIMARY KEY, last_seen_date DATE NOT NULL)");
    }

    /** 保留期 7 天：边界日当天不删，只删严格早于边界的行。 */
    @Test
    void shouldDeleteOnlyRowsStrictlyOlderThanRetentionBoundary() {
        LocalDate bizDate = LocalDate.of(2026, 8, 26);
        insert(8L, bizDate.minusDays(8L));
        insert(7L, bizDate.minusDays(7L));
        insert(6L, bizDate.minusDays(6L));

        int deleted = dishTagService.deleteExpiredBatch(bizDate);

        List<Long> remainingDishIdList = jdbcTemplate.queryForList(
                "SELECT dish_id FROM ct_dish_tag ORDER BY dish_id", Long.class);
        assertThat(deleted).isEqualTo(1);
        assertThat(remainingDishIdList).containsExactly(6L, 7L);
    }

    private void insert(long dishId, LocalDate lastSeenDate) {
        jdbcTemplate.update("INSERT INTO ct_dish_tag (dish_id, last_seen_date) VALUES (?, ?)",
                dishId, lastSeenDate);
    }
}
