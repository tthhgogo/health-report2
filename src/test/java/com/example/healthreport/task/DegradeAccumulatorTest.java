package com.example.healthreport.task;

import com.example.healthreport.cache.AnalysisModules;
import com.example.healthreport.cache.AnalysisResult;
import com.example.healthreport.support.PartialReason;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** R43d/R43e：两类降级原因的 OR 累积、主原因优先级与模块四抑制。 */
class DegradeAccumulatorTest {

    @Test
    void dietTagDropShouldSuppressDishRecommendAndWinAsPrimaryReason() {
        DegradeAccumulator accumulator = new DegradeAccumulator();
        accumulator.recordSchemaItemDropped();
        accumulator.recordDietTagDropped();

        AnalysisResult result = AnalysisResult.create(accumulator, 30, 30, populatedModules());

        assertThat(result.isPartial()).isTrue();
        // 单值列：两类同时发生时取 DIET_TAG_DROPPED——它携带模块四已抑制的行为后果。
        assertThat(result.getPartialReason()).isEqualTo(PartialReason.DIET_TAG_DROPPED);
        assertThat(result.isSuppressDishRecommend()).isTrue();
        assertThat(result.getModules().getDietAdvice()).isEqualTo("advice");
        assertThat(result.getModules().getDishRecommendations()).isNull();
    }

    @Test
    void schemaItemDropAloneShouldNotSuppressAnyModule() {
        DegradeAccumulator accumulator = new DegradeAccumulator();
        accumulator.recordSchemaItemDropped();

        AnalysisResult result = AnalysisResult.create(accumulator, 1, 1, populatedModules());

        assertThat(result.isPartial()).isTrue();
        assertThat(result.getPartialReason()).isEqualTo(PartialReason.SCHEMA_ITEM_DROPPED);
        assertThat(result.isSuppressDishRecommend()).isFalse();
        assertThat(result.getModules().getDietAdvice()).isEqualTo("advice");
        assertThat(result.getModules().getDishRecommendations()).isEqualTo("dish");
    }

    @Test
    void cleanRunShouldNotBePartial() {
        DegradeAccumulator accumulator = new DegradeAccumulator();

        assertThat(accumulator.partial()).isFalse();
        assertThat(accumulator.suppressDishRecommend()).isFalse();
        assertThat(accumulator.primaryReason()).isNull();
    }

    /** 只需四个模块非空即可验证降级清空逻辑；模块内容正确性由各自的装配用例覆盖。 */
    private AnalysisModules populatedModules() {
        return new AnalysisModules("indicator", "problem", "advice", "dish");
    }
}
