package com.example.healthreport.assemble.dishrecommend;

import com.example.healthreport.cache.DishRecommendSetCache;
import com.example.healthreport.cache.DishSetMemberCodec;
import com.example.healthreport.cache.DishTagSetRef;
import com.example.healthreport.llm.extraction.DietAdviceResult;
import com.example.healthreport.safety.HighRiskAdviceGate;
import com.example.healthreport.support.text.TextNormalizer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R5/R11：非食入性过敏原与 OTHER 不拼 Redis Key；高危 quote 不进结构化链路。
 */
class DishRecommendInputFactoryTest {

    private static final LocalDate BIZ_DATE = LocalDate.of(2026, 9, 3);

    private final DishRecommendSetCache setCache = mock(DishRecommendSetCache.class);
    private final DishRecommendInputFactory factory = new DishRecommendInputFactory(
            setCache, new DishSetMemberCodec(), new HighRiskAdviceGate(new TextNormalizer()));

    /** 尘螨、OTHER 只进模块三展示，绝不选择任何 Redis 集合；食入性过敏原正常拼 Key。 */
    @Test
    void nonFoodAllergenAndOtherMustNotSelectAnyRedisSet() {
        when(setCache.read(eq("company-a"), eq(BIZ_DATE), anyList()))
                .thenReturn(Collections.<DishTagSetRef, java.util.Set<String>>emptyMap());
        DietAdviceResult dietTags = new DietAdviceResult("OK",
                Collections.<DietAdviceResult.DietTag>emptyList(),
                Arrays.asList(
                        tag("ALLERGEN", "DUST_MITE", "尘螨 阳性(+)"),
                        tag("ALLERGEN", "OTHER", "芹菜 阳性(+)"),
                        tag("ALLERGEN", "SHRIMP_CRAB", "虾蟹类 阳性(+)")));

        factory.create("company-a", BIZ_DATE, dietTags, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DishTagSetRef>> refCaptor =
                (ArgumentCaptor<List<DishTagSetRef>>) (ArgumentCaptor<?>)
                        ArgumentCaptor.forClass(List.class);
        verify(setCache).read(eq("company-a"), eq(BIZ_DATE), refCaptor.capture());
        List<String> enumKeys = new ArrayList<String>();
        for (DishTagSetRef ref : refCaptor.getValue()) {
            enumKeys.add(ref.getEnumKey());
        }
        assertThat(enumKeys).containsExactly("SHRIMP_CRAB");
    }

    /** quote 命中高危词的营养/饮食条目按 OTHER 路径处理：不选择任何集合。 */
    @Test
    void highRiskQuoteMustSuppressStructuredDimensions() {
        DietAdviceResult dietTags = new DietAdviceResult("OK",
                Collections.singletonList(tag("NUTRITION", "PROTEIN", "建议优质低蛋白饮食")),
                Collections.<DietAdviceResult.DietTag>emptyList());

        DishRecommendInput input = factory.create("company-a", BIZ_DATE, dietTags, false);

        verify(setCache, never()).read(eq("company-a"), eq(BIZ_DATE), anyList());
        // 有正式枚举内容（formalAdvicePresent），但没有任何可执行维度。
        assertThat(input.isFormalAdvicePresent()).isTrue();
        assertThat(input.getCandidateList()).isEmpty();
    }

    /** 抑制开关为真时不访问 Redis，也不产出候选。 */
    @Test
    void suppressedInputMustNotTouchRedis() {
        DietAdviceResult dietTags = new DietAdviceResult("OK",
                Collections.<DietAdviceResult.DietTag>emptyList(),
                Collections.singletonList(tag("ALLERGEN", "SHRIMP_CRAB", "虾蟹类 阳性(+)")));

        DishRecommendInput input = factory.create("company-a", BIZ_DATE, dietTags, true);

        verify(setCache, never()).read(eq("company-a"), eq(BIZ_DATE), anyList());
        assertThat(input.isSuppressDishRecommend()).isTrue();
        assertThat(input.getCandidateList()).isEmpty();
    }

    private DietAdviceResult.DietTag tag(String dimension, String enumKey, String quote) {
        return new DietAdviceResult.DietTag(dimension, enumKey, 1, "过敏原筛查", null,
                quote, quote + " 参考值：阴性");
    }
}
