package com.example.healthreport.llm.extraction;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** R21h/R21i/P0-18f：第三阶段方向固定表与 reject 优先的机械校验。 */
class StructuralValidatorTest {

    private final StructuralValidator validator = new StructuralValidator();

    @Test
    void dietOtherInRecommendMustBeDroppedAndFlagDietTagDropped() {
        // DIET 只有 LOW_PURINE、HIGH_FIBER 可进 recommend；OTHER 放进 recommend 是放反了方向。
        DietTagsResult result = new DietTagsResult("OK",
                Arrays.asList(
                        tag("DIET", "OTHER"),
                        tag("DIET", "LOW_PURINE"),
                        tag("NUTRITION", "IRON")),
                Collections.<DietTagsResult.DietTag>emptyList());

        StructuralValidator.DietTagsValidationResult validation =
                validator.validateDietTags(result, 5, 0);

        List<String> keptKeys = keys(validation.getResult().getRecommend());
        assertThat(keptKeys).containsExactly("LOW_PURINE", "IRON");
        // 占预算的剔除必须触发 DIET_TAG_DROPPED（模块四整体抑制）。
        assertThat(validation.isDroppedAnyTag()).isTrue();
    }

    @Test
    void allergenInRecommendMustBeDroppedWithinBudget() {
        // 6 条中 1 条放反：在 20% 预算（至少 1 条）内剔除，其余保留。
        DietTagsResult result = new DietTagsResult("OK",
                Arrays.asList(tag("ALLERGEN", "SHRIMP_CRAB"), tag("NUTRITION", "IRON"),
                        tag("DIET", "HIGH_FIBER")),
                Arrays.asList(tag("ALLERGEN", "DUST_MITE"), tag("DIET", "LOW_SODIUM"),
                        tag("ALLERGEN", "OTHER")));

        StructuralValidator.DietTagsValidationResult validation =
                validator.validateDietTags(result, 5, 0);

        // recommend：过敏原恒不许进；reject：非食入性过敏原与 OTHER 是合法的仅展示条目。
        assertThat(keys(validation.getResult().getRecommend()))
                .containsExactly("IRON", "HIGH_FIBER");
        assertThat(keys(validation.getResult().getReject()))
                .containsExactly("DUST_MITE", "LOW_SODIUM", "OTHER");
        assertThat(validation.isDroppedAnyTag()).isTrue();
    }

    @Test
    void tooManyDirectionViolationsMustFailTheWholeStage() {
        // 8 条中 3 条放反：超出 20% 修复预算，整阶段失败而不是残缺放行。
        DietTagsResult result = new DietTagsResult("OK",
                Arrays.asList(tag("ALLERGEN", "SHRIMP_CRAB"), tag("NUTRITION", "IRON"),
                        tag("DIET", "HIGH_FIBER"), tag("DIET", "LOW_FAT")),
                Arrays.asList(tag("NUTRITION", "CALCIUM"), tag("ALLERGEN", "DUST_MITE"),
                        tag("DIET", "LOW_SODIUM"), tag("ALLERGEN", "OTHER")));

        Logger logger = (Logger) LoggerFactory.getLogger(StructuralValidator.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> validator.validateDietTags(result, 5, 0))
                    .isInstanceOf(com.example.healthreport.support.HealthReportException.class);

            String renderedLog = renderedLog(appender);
            assertThat(renderedLog)
                    .contains("路径=$.recommend[0]", "原因=TAG_DIRECTION_INVALID")
                    .contains("路径=$.reject[0]", "结构校验剔除超预算")
                    .doesNotContain("SHRIMP_CRAB", "CALCIUM", "建议内容原文一句",
                            "承载该建议的整条原文");
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    @Test
    void rejectWinsOverRecommendWithoutConsumingBudget() {
        // 同一正式枚举同时出现正反方向：reject 优先，确定性归一化不算剔除。
        DietTagsResult result = new DietTagsResult("OK",
                Collections.singletonList(tag("DIET", "LOW_PURINE")),
                Collections.singletonList(tag("DIET", "LOW_PURINE")));

        StructuralValidator.DietTagsValidationResult validation =
                validator.validateDietTags(result, 5, 0);

        assertThat(keys(validation.getResult().getRecommend())).isEmpty();
        assertThat(keys(validation.getResult().getReject())).containsExactly("LOW_PURINE");
        assertThat(validation.isDroppedAnyTag()).isFalse();
    }

    @Test
    void schemaDropsAndStructuralDropsMustShareTheSameBudget() {
        // Schema 层已剔 1 条 + 结构层再剔 1 条，条目总数 6（含已剔）：2/6 > 20%，整阶段失败。
        DietTagsResult result = new DietTagsResult("OK",
                Collections.singletonList(tag("ALLERGEN", "SHRIMP_CRAB")),
                Arrays.asList(tag("ALLERGEN", "DUST_MITE"), tag("DIET", "LOW_SODIUM"),
                        tag("ALLERGEN", "FISH"), tag("NUTRITION", "IRON")));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> validator.validateDietTags(result, 5, 1))
                .isInstanceOf(com.example.healthreport.support.HealthReportException.class);
    }

    private DietTagsResult.DietTag tag(String dimension, String enumKey) {
        return new DietTagsResult.DietTag(dimension, enumKey, 1, "总检结论", null,
                "建议内容原文一句", "承载该建议的整条原文");
    }

    private List<String> keys(List<DietTagsResult.DietTag> tagList) {
        List<String> keyList = new ArrayList<String>(tagList.size());
        for (DietTagsResult.DietTag tag : tagList) {
            keyList.add(tag.getEnumKey());
        }
        return keyList;
    }

    private String renderedLog(ListAppender<ILoggingEvent> appender) {
        StringBuilder renderedLog = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            renderedLog.append(event.getFormattedMessage()).append('\n');
        }
        return renderedLog.toString();
    }
}
