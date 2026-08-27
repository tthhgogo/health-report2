package com.example.healthreport.task;

import com.example.healthreport.cache.AnalysisModules;
import com.example.healthreport.cache.AnalysisResult;
import com.example.healthreport.support.PartialReason;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;

/** R43d/R43e：多个降级原因的 OR 累积与模块抑制回归。 */
class DegradeAccumulatorTest {

    @Test
    void laterAllergenReasonMustNotReenableDietAdvice() {
        DegradeAccumulator accumulator = new DegradeAccumulator();
        accumulator.recordPageTruncated();
        accumulator.recordAllergenSuspectMiss();

        AnalysisResult result = AnalysisResult.create(accumulator, 30, 45, populatedModules());

        assertThat(result.isPartial()).isTrue();
        assertThat(result.getPartialReason()).isEqualTo(PartialReason.PAGE_TRUNCATED);
        assertThat(result.isSuppressDietAdvice()).isTrue();
        assertThat(result.isSuppressDishRecommend()).isTrue();
        assertThat(result.getModules().getDietAdvice()).isNull();
        assertThat(result.getModules().getDishRecommendations()).isNull();
    }

    @Test
    void allThreeReasonsShouldRemainTrueAndUseHighestSeverityReason() {
        DegradeAccumulator accumulator = new DegradeAccumulator();
        accumulator.recordAllergenSuspectMiss();
        accumulator.recordBatchUnreadable();
        accumulator.recordPageTruncated();

        assertThat(accumulator.partial()).isTrue();
        assertThat(accumulator.suppressDietAdvice()).isTrue();
        assertThat(accumulator.suppressDishRecommend()).isTrue();
        assertThat(accumulator.primaryReason()).isEqualTo(PartialReason.PAGE_TRUNCATED);
    }

    @Test
    void allergenReasonShouldSuppressOnlyDishRecommend() {
        DegradeAccumulator accumulator = new DegradeAccumulator();
        accumulator.recordAllergenSuspectMiss();

        AnalysisResult result = AnalysisResult.create(accumulator, 1, 1, populatedModules());

        assertThat(result.isSuppressDietAdvice()).isFalse();
        assertThat(result.isSuppressDishRecommend()).isTrue();
        assertThat(result.getModules().getDietAdvice()).isEqualTo("advice");
        assertThat(result.getModules().getDishRecommendations()).isNull();
    }

    /** 只需四个模块非空即可验证降级清空逻辑；模块内容正确性由各自的装配用例覆盖。 */
    private AnalysisModules populatedModules() {
        return new AnalysisModules("indicator", "problem", "advice", "dish");
    }
}
