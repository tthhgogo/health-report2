package com.example.healthreport.llm.extraction;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.healthreport.llm.schema.ModelOutputSchemaRegistry;
import com.example.healthreport.support.HealthReportException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R20/R21e/R21j/R21k：真实生产 Schema 上的条目剔除、整章剔除与预算断言。
 */
class ExtractionSchemaValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExtractionSchemaValidator validator = new ExtractionSchemaValidator(
            objectMapper, new ModelOutputSchemaRegistry(objectMapper));

    /** 章节 page=0 违反 Schema 最小值：整章剔除（R20），不是整阶段 SERVER_ERROR。 */
    @Test
    void sectionWithInvalidPageShouldBeDroppedAsAWholeSection() throws Exception {
        String content = "{\"reportStatus\":\"OK\",\"patients\":[],\"overview\":null,"
                + "\"sections\":["
                + section("血脂检查", 1, 5)
                + "," + section("尿常规", 0, 1)
                + "]}";

        SchemaValidationOutcome outcome = validator.validate(ExtractionCall.INDICATORS, content);

        JsonNode sections = outcome.getValidatedNode().path("sections");
        assertThat(sections.size()).isEqualTo(1);
        assertThat(sections.path(0).path("section").asText()).isEqualTo("血脂检查");
        // 整章剔除按该章条目数入账，不是按 1 条。
        assertThat(outcome.getDroppedItemCount()).isEqualTo(1);
    }

    /** 单条指标缺必填字段：只剔那一条，其余保留。 */
    @Test
    void indicatorItemViolationShouldDropOnlyThatItem() throws Exception {
        String badItem = "{\"name\":\"白细胞\",\"value\":\"6.2\",\"unit\":null,\"refRange\":\"4.0~10.0\","
                + "\"conclusionGenerated\":false}"; // 缺 status
        String content = "{\"reportStatus\":\"OK\",\"patients\":[],\"overview\":null,"
                + "\"sections\":[{\"section\":\"血常规\",\"page\":1,\"indicators\":["
                + indicator("血红蛋白") + "," + badItem + "," + indicator("血小板")
                + "," + indicator("红细胞") + "," + indicator("中性粒细胞")
                + "]}]}";

        SchemaValidationOutcome outcome = validator.validate(ExtractionCall.INDICATORS, content);

        assertThat(outcome.getValidatedNode().path("sections").path(0).path("indicators").size())
                .isEqualTo(4);
        assertThat(outcome.getDroppedItemCount()).isEqualTo(1);
    }

    /** 性别未按「男/女/null」归一化：按 patients 条目剔除，不失败整阶段（P1 修复的回归）。 */
    @Test
    void patientWithUnnormalizedGenderShouldBeDroppedAsAnItem() throws Exception {
        String content = "{\"reportStatus\":\"OK\","
                + "\"patients\":[{\"page\":1,\"name\":\"张三\",\"gender\":\"M\"},"
                + "{\"page\":1,\"name\":\"张三\",\"gender\":\"男\"}],"
                + "\"overview\":null,"
                + "\"sections\":[" + section("血脂检查", 1, 5) + "]}";

        SchemaValidationOutcome outcome = validator.validate(ExtractionCall.INDICATORS, content);

        assertThat(outcome.getValidatedNode().path("patients").size()).isEqualTo(1);
        assertThat(outcome.getValidatedNode().path("patients").path(0).path("gender").asText())
                .isEqualTo("男");
        assertThat(outcome.getDroppedItemCount()).isEqualTo(1);
    }

    /** 顶层字段坏了剔除救不回来：整阶段失败（R21k）。 */
    @Test
    void topLevelViolationMustFailTheWholeStage() {
        String content = "{\"reportStatus\":\"MAYBE\",\"patients\":[],\"overview\":null,\"sections\":[]}";

        assertThatThrownBy(() -> validator.validate(ExtractionCall.INDICATORS, content))
                .isInstanceOf(HealthReportException.class);
    }

    /** 剔除超过 20% 预算：整阶段失败而不是残缺放行（R21j）。 */
    @Test
    void exceedingDropBudgetMustFailTheWholeStage() {
        // 一章 5 条里整章 page=0 全灭（5 条），另一章只有 2 条：7 条剔 5 条远超预算。
        String content = "{\"reportStatus\":\"OK\",\"patients\":[],\"overview\":null,"
                + "\"sections\":["
                + section("尿常规", 0, 5)
                + "," + section("血脂检查", 1, 2)
                + "]}";

        assertThatThrownBy(() -> validator.validate(ExtractionCall.INDICATORS, content))
                .isInstanceOf(HealthReportException.class);
    }

    /** 第三阶段条目违规按条剔除并入账，供 DIET_TAG_DROPPED 抑制模块四。 */
    @Test
    void dietTagItemViolationShouldDropAndCount() throws Exception {
        String badTag = "{\"dimension\":\"ALLERGEN\",\"enumKey\":\"SHRIMP_CRAB\",\"page\":1,"
                + "\"section\":null,\"itemNo\":null,\"quote\":\"虾蟹类 阳性\"}"; // 缺 rawText
        String content = "{\"reportStatus\":\"OK\",\"recommend\":[],\"reject\":["
                + dietTag("FISH") + "," + badTag + "," + dietTag("MILK")
                + "," + dietTag("EGG") + "," + dietTag("SOY") + "]}";

        Logger logger = (Logger) LoggerFactory.getLogger(ExtractionSchemaValidator.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        try {
            SchemaValidationOutcome outcome = validator.validate(ExtractionCall.DIET_TAGS, content);

            assertThat(outcome.getValidatedNode().path("reject").size()).isEqualTo(4);
            assertThat(outcome.getDroppedItemCount()).isEqualTo(1);
            assertThat(renderedLog(appender))
                    .contains("模型输出 Schema 条目不合格", "路径=$.reject[1]", "关键字=required")
                    .doesNotContain("SHRIMP_CRAB", "虾蟹类 阳性");
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    private String section(String name, int page, int indicatorCount) {
        StringBuilder items = new StringBuilder();
        for (int index = 0; index < indicatorCount; index++) {
            if (items.length() > 0) {
                items.append(',');
            }
            items.append(indicator("指标" + index));
        }
        return "{\"section\":\"" + name + "\",\"page\":" + page + ",\"indicators\":[" + items + "]}";
    }

    private String indicator(String name) {
        return "{\"name\":\"" + name + "\",\"value\":\"1.0\",\"unit\":null,"
                + "\"refRange\":\"0.5~2.0\",\"conclusionGenerated\":false,\"status\":\"NORMAL\"}";
    }

    private String dietTag(String enumKey) {
        return "{\"dimension\":\"ALLERGEN\",\"enumKey\":\"" + enumKey + "\",\"page\":1,"
                + "\"section\":\"过敏原筛查\",\"itemNo\":null,\"quote\":\"" + enumKey + " 阳性\","
                + "\"rawText\":\"" + enumKey + " 阳性 参考值：阴性\"}";
    }

    private String renderedLog(ListAppender<ILoggingEvent> appender) {
        StringBuilder renderedLog = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            renderedLog.append(event.getFormattedMessage()).append('\n');
        }
        return renderedLog.toString();
    }
}
