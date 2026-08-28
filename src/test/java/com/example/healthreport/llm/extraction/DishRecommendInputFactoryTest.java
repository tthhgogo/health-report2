package com.example.healthreport.llm.extraction;

import com.example.healthreport.assemble.dishrecommend.DishRecommendAssembler;
import com.example.healthreport.assemble.dishrecommend.DishRecommendInput;
import com.example.healthreport.assemble.dishrecommend.DishRecommendInputFactory;
import com.example.healthreport.assemble.sort.DisplayOrder;
import com.example.healthreport.constants.AllergenGroup;
import com.example.healthreport.constants.AllergenKey;
import com.example.healthreport.constants.DietRequirementKey;
import com.example.healthreport.constants.NutritionKey;
import com.example.healthreport.dish.DietPositiveMatcher;
import com.example.healthreport.dish.Dish;
import com.example.healthreport.dish.DishIngredient;
import com.example.healthreport.dish.DishTagReadService;
import com.example.healthreport.dish.MainIngredientResolver;
import com.example.healthreport.dish.NutritionMatcher;
import com.example.healthreport.dish.TagState;
import com.example.healthreport.dish.TagStateResolver;
import com.example.healthreport.dish.TagValue;
import com.example.healthreport.parse.segment.Segment;
import com.example.healthreport.parse.segment.TextSource;
import com.example.healthreport.safety.AllergenKeywordFallback;
import com.example.healthreport.safety.StructuredAdmission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 模块四真实输入工厂的过敏并集、五态门槛、营养原文、饮食正向匹配与 OTHER 边界回归。 */
class DishRecommendInputFactoryTest {

    /** 固定业务日用于断言整条在线读取链路不自行获取系统日期。 */
    private static final LocalDate BIZ_DATE = LocalDate.of(2026, 8, 26);

    private DishTagReadService dishTagReadService;
    private NutritionMatcher nutritionMatcher;
    private AllergenKeywordFallback allergenKeywordFallback;
    private StructuredAdmission structuredAdmission;
    private DietPositiveMatcher dietPositiveMatcher;
    private DishRecommendInputFactory factory;
    private DishRecommendAssembler assembler;

    @BeforeEach
    void setUp() {
        dishTagReadService = mock(DishTagReadService.class);
        nutritionMatcher = mock(NutritionMatcher.class);
        allergenKeywordFallback = mock(AllergenKeywordFallback.class);
        structuredAdmission = mock(StructuredAdmission.class);
        // 饮食正向匹配用真实实现：这条链路的价值就在于能对着真实内容常量穷举断言。
        dietPositiveMatcher = new DietPositiveMatcher(
                new NutritionMatcher(new MainIngredientResolver()));
        factory = new DishRecommendInputFactory(dishTagReadService, nutritionMatcher,
                dietPositiveMatcher, allergenKeywordFallback, structuredAdmission);
        assembler = new DishRecommendAssembler(new TagStateResolver(), new DisplayOrder());
        when(structuredAdmission.shouldSuppress(any(), any(), any(), any())).thenReturn(false);
    }

    @Test
    void hardFallbackShouldOverrideModelNeutralAndRemovePositiveNutritionLabels() {
        Dish dish = dish(1L, "菠菜虾仁", "菠菜");
        ValidatedExtractionOutput output = output(
                Collections.singletonList(allergen(AllergenKey.SHRIMP_CRAB, true,
                        "f0-p1-s0", "虾蟹")),
                Collections.singletonList(nutrition(NutritionKey.IRON, "f0-p1-s1")),
                Collections.<ValidatedExtractionOutput.AdviceItem<DietRequirementKey>>emptyList(),
                segments("f0-p1-s0", "过敏检查阳性", "f0-p1-s1", "建议补充铁"));
        when(dishTagReadService.read(eq(BIZ_DATE), eq(Collections.singletonList(dish)), any()))
                .thenReturn(tags(AllergenKey.SHRIMP_CRAB.name(), dish.getDishId(),
                        TagValue.of(TagState.NEUTRAL)));
        when(allergenKeywordFallback.matches(any(AllergenGroup.class), same(dish)))
                .thenReturn(true);
        when(nutritionMatcher.match(dish, NutritionKey.IRON)).thenReturn(
                new TagValue(TagState.RECOMMEND, Collections.singletonList("菠菜")));

        DishRecommendInput input = factory.create(BIZ_DATE, Collections.singletonList(dish),
                output, false);
        DishRecommendAssembler.Result result = assembler.assemble(input);

        assertThat(input.isFormalAdvicePresent()).isTrue();
        assertThat(result.getRejectList()).hasSize(1);
        assertThat(result.getRejectList().get(0).getTagList()).extracting("text")
                .containsExactly("虾蟹类过敏");
        assertThat(result.getRejectList().get(0).getSupplementalTagList()).isEmpty();
        assertThat(result.getRecommendList()).isEmpty();
        verify(dishTagReadService).read(eq(BIZ_DATE), eq(Collections.singletonList(dish)),
                eq(Collections.singleton(AllergenKey.SHRIMP_CRAB.name())));
    }

    @Test
    void modelRejectShouldRemainRejectWhenHardFallbackDoesNotMatch() {
        Dish dish = dish(2L, "清蒸菜", "蔬菜");
        ValidatedExtractionOutput output = output(
                Collections.singletonList(allergen(AllergenKey.FISH, true,
                        "f0-p1-s0", "鱼类")),
                Collections.<ValidatedExtractionOutput.AdviceItem<NutritionKey>>emptyList(),
                Collections.<ValidatedExtractionOutput.AdviceItem<DietRequirementKey>>emptyList(),
                segments("f0-p1-s0", "过敏检查阳性"));
        when(dishTagReadService.read(eq(BIZ_DATE), anyList(), any())).thenReturn(
                tags(AllergenKey.FISH.name(), dish.getDishId(), TagValue.of(TagState.REJECT)));
        when(allergenKeywordFallback.matches(any(AllergenGroup.class), same(dish)))
                .thenReturn(false);

        DishRecommendInput input = factory.create(BIZ_DATE, Collections.singletonList(dish),
                output, false);
        DishRecommendAssembler.Result result = assembler.assemble(input);

        assertThat(input.isFormalAdvicePresent()).isTrue();
        assertThat(result.getRejectList()).hasSize(1);
        assertThat(result.getRejectList().get(0).getTagList()).extracting("text")
                .containsExactly("鱼类过敏");
    }

    @Test
    void nonFoodOtherShouldNotEnterDishMatchingOrBlockNutritionRecommendation() {
        Dish dish = dish(3L, "艾草青团", "艾草");
        ValidatedExtractionOutput output = output(
                Collections.singletonList(allergen(AllergenKey.OTHER, false,
                        "f0-p1-s0", "艾草")),
                Collections.singletonList(nutrition(NutritionKey.IRON, "f0-p1-s1")),
                Collections.<ValidatedExtractionOutput.AdviceItem<DietRequirementKey>>emptyList(),
                segments("f0-p1-s0", "非食源性过敏检查阳性",
                        "f0-p1-s1", "建议补充铁"));
        when(nutritionMatcher.match(dish, NutritionKey.IRON)).thenReturn(
                new TagValue(TagState.RECOMMEND, Collections.singletonList("艾草")));

        DishRecommendAssembler.Result result = assembler.assemble(
                factory.create(BIZ_DATE, Collections.singletonList(dish), output, false));

        assertThat(result.getRecommendList()).hasSize(1);
        assertThat(result.getRejectList()).isEmpty();
        verify(allergenKeywordFallback, never()).matchesOther(any(String.class), anyBoolean(),
                any(Dish.class));
        verify(dishTagReadService, never()).read(any(LocalDate.class), anyList(), any());
    }

    @Test
    void foodBorneOtherShouldUseLiteralFallbackAndProduceAllergyReject() {
        Dish dish = dish(8L, "芹菜炒肉", "芹菜");
        ValidatedExtractionOutput output = output(
                Collections.singletonList(allergen(AllergenKey.OTHER, true,
                        "f0-p1-s0", "芹菜")),
                Collections.<ValidatedExtractionOutput.AdviceItem<NutritionKey>>emptyList(),
                Collections.<ValidatedExtractionOutput.AdviceItem<DietRequirementKey>>emptyList(),
                segments("f0-p1-s0", "食源性过敏检查阳性"));
        when(allergenKeywordFallback.matchesOther("芹菜", true, dish)).thenReturn(true);

        DishRecommendInput input = factory.create(BIZ_DATE, Collections.singletonList(dish),
                output, false);
        DishRecommendAssembler.Result result = assembler.assemble(input);

        assertThat(input.isFormalAdvicePresent()).isFalse();
        assertThat(result.getRejectList()).hasSize(1);
        assertThat(result.getRejectList().get(0).getTagList()).extracting("text")
                .containsExactly("芹菜过敏");
        verify(dishTagReadService, never()).read(any(LocalDate.class), anyList(), any());
    }

    @Test
    void dietRejectShouldBeReadByFactoryAndEnterRejectList() {
        Dish dish = dish(7L, "油炸菜品", "食材");
        ValidatedExtractionOutput output = output(
                Collections.<ValidatedExtractionOutput.Allergen>emptyList(),
                Collections.<ValidatedExtractionOutput.AdviceItem<NutritionKey>>emptyList(),
                Collections.singletonList(diet(DietRequirementKey.LOW_FAT, "f0-p1-s0")),
                segments("f0-p1-s0", "建议低脂饮食"));
        when(dishTagReadService.read(eq(BIZ_DATE), anyList(), any())).thenReturn(
                tags(DietRequirementKey.LOW_FAT.name(), dish.getDishId(),
                        TagValue.of(TagState.REJECT)));

        DishRecommendAssembler.Result result = assembler.assemble(
                factory.create(BIZ_DATE, Collections.singletonList(dish), output, false));

        assertThat(result.getRejectList()).hasSize(1);
        assertThat(result.getRejectList().get(0).getTagList()).extracting("text")
                .containsExactly("高脂");
    }

    @Test
    void neutralLowPurineDishWithWhitelistedMainIngredientShouldBeRecommended() {
        Dish dish = weighted(9L, "冬瓜炖蛋", "冬瓜", "200", "鸡蛋", "100");
        ValidatedExtractionOutput output = output(
                Collections.<ValidatedExtractionOutput.Allergen>emptyList(),
                Collections.<ValidatedExtractionOutput.AdviceItem<NutritionKey>>emptyList(),
                Collections.singletonList(diet(DietRequirementKey.LOW_PURINE, "f0-p1-s0")),
                segments("f0-p1-s0", "建议低嘌呤饮食"));
        when(dishTagReadService.read(eq(BIZ_DATE), anyList(), any())).thenReturn(
                tags(DietRequirementKey.LOW_PURINE.name(), dish.getDishId(),
                        TagValue.of(TagState.NEUTRAL)));

        DishRecommendInput input = factory.create(BIZ_DATE, Collections.singletonList(dish),
                output, false);
        DishRecommendAssembler.Result result = assembler.assemble(input);

        assertThat(input.getCandidateList().get(0).getMatchList()).hasSize(2);
        assertThat(result.getRecommendList()).hasSize(1);
        assertThat(result.getRecommendList().get(0).getTagList()).extracting("text")
                .containsExactly("低嘌呤");
        assertThat(result.getRecommendList().get(0).getReasonList())
                .containsExactly("冬瓜炖蛋——含冬瓜、鸡蛋；报告原文：「建议低嘌呤饮食」");
    }

    @Test
    void modelRejectOrUnknownShouldNeverProduceDietRecommendation() {
        Dish dish = weighted(10L, "内脏冬瓜汤", "冬瓜", "200", "动物内脏", "100");
        ValidatedExtractionOutput output = output(
                Collections.<ValidatedExtractionOutput.Allergen>emptyList(),
                Collections.<ValidatedExtractionOutput.AdviceItem<NutritionKey>>emptyList(),
                Collections.singletonList(diet(DietRequirementKey.LOW_PURINE, "f0-p1-s0")),
                segments("f0-p1-s0", "建议低嘌呤饮食"));
        when(dishTagReadService.read(eq(BIZ_DATE), anyList(), any())).thenReturn(
                tags(DietRequirementKey.LOW_PURINE.name(), dish.getDishId(),
                        TagValue.of(TagState.REJECT)));

        DishRecommendAssembler.Result rejectResult = assembler.assemble(
                factory.create(BIZ_DATE, Collections.singletonList(dish), output, false));

        assertThat(rejectResult.getRecommendList()).isEmpty();
        assertThat(rejectResult.getRejectList()).hasSize(1);
        assertThat(rejectResult.getRejectList().get(0).getTagList()).extracting("text")
                .containsExactly("高嘌呤");
        assertThat(rejectResult.getRejectList().get(0).getSupplementalTagList()).isEmpty();

        when(dishTagReadService.read(eq(BIZ_DATE), anyList(), any())).thenReturn(
                tags(DietRequirementKey.LOW_PURINE.name(), dish.getDishId(),
                        TagValue.of(TagState.UNKNOWN)));

        DishRecommendAssembler.Result unknownResult = assembler.assemble(
                factory.create(BIZ_DATE, Collections.singletonList(dish), output, false));

        assertThat(unknownResult.getRecommendList()).isEmpty();
        assertThat(unknownResult.getRejectList()).isEmpty();
    }

    @Test
    void dimensionWithoutPositivePolicyShouldStayRejectOnly() {
        Dish dish = weighted(11L, "清蒸鲈鱼", "鲜鱼", "200", "北豆腐", "100");
        ValidatedExtractionOutput output = output(
                Collections.<ValidatedExtractionOutput.Allergen>emptyList(),
                Collections.<ValidatedExtractionOutput.AdviceItem<NutritionKey>>emptyList(),
                Collections.singletonList(diet(DietRequirementKey.LOW_FAT, "f0-p1-s0")),
                segments("f0-p1-s0", "建议低脂饮食"));
        when(dishTagReadService.read(eq(BIZ_DATE), anyList(), any())).thenReturn(
                tags(DietRequirementKey.LOW_FAT.name(), dish.getDishId(),
                        TagValue.of(TagState.NEUTRAL)));

        DishRecommendInput input = factory.create(BIZ_DATE, Collections.singletonList(dish),
                output, false);
        DishRecommendAssembler.Result result = assembler.assemble(input);

        assertThat(input.getCandidateList().get(0).getMatchList()).hasSize(1);
        assertThat(result.getRecommendList()).isEmpty();
        assertThat(result.getRejectList()).isEmpty();
    }

    @Test
    void fiberDimensionAndFiberSupplementShouldProduceOneTagAndOneReason() {
        Dish dish = weighted(12L, "燕麦粥", "燕麦", "150");
        ValidatedExtractionOutput output = output(
                Collections.<ValidatedExtractionOutput.Allergen>emptyList(),
                Collections.singletonList(nutrition(NutritionKey.DIETARY_FIBER, "f0-p1-s0")),
                Collections.singletonList(diet(DietRequirementKey.HIGH_FIBER, "f0-p1-s1")),
                segments("f0-p1-s0", "建议补充膳食纤维", "f0-p1-s1", "建议高纤维饮食"));
        when(dishTagReadService.read(eq(BIZ_DATE), anyList(), any())).thenReturn(
                tags(DietRequirementKey.HIGH_FIBER.name(), dish.getDishId(),
                        TagValue.of(TagState.NEUTRAL)));
        when(nutritionMatcher.match(dish, NutritionKey.DIETARY_FIBER)).thenReturn(
                new TagValue(TagState.RECOMMEND, Collections.singletonList("燕麦")));

        DishRecommendAssembler.Result result = assembler.assemble(
                factory.create(BIZ_DATE, Collections.singletonList(dish), output, false));

        assertThat(result.getRecommendList()).hasSize(1);
        assertThat(result.getRecommendList().get(0).getTagList()).extracting("text")
                .containsExactly("高纤维");
        assertThat(result.getRecommendList().get(0).getReasonList())
                .containsExactly("燕麦粥——含燕麦；报告原文：「建议补充膳食纤维」");
    }

    @Test
    void missingRejectCapableTagShouldHideFactoryNutritionRecommendation() {
        Dish dish = dish(4L, "菠菜菜品", "菠菜");
        ValidatedExtractionOutput output = output(
                Collections.singletonList(allergen(AllergenKey.SHRIMP_CRAB, true,
                        "f0-p1-s0", "虾蟹")),
                Collections.singletonList(nutrition(NutritionKey.IRON, "f0-p1-s1")),
                Collections.<ValidatedExtractionOutput.AdviceItem<DietRequirementKey>>emptyList(),
                segments("f0-p1-s0", "过敏检查阳性", "f0-p1-s1", "建议补充铁"));
        when(dishTagReadService.read(eq(BIZ_DATE), anyList(), any())).thenReturn(
                tags(AllergenKey.SHRIMP_CRAB.name(), dish.getDishId(),
                        TagValue.of(TagState.TAG_MISSING)));
        when(allergenKeywordFallback.matches(any(AllergenGroup.class), same(dish)))
                .thenReturn(false);
        when(nutritionMatcher.match(dish, NutritionKey.IRON)).thenReturn(
                new TagValue(TagState.RECOMMEND, Collections.singletonList("菠菜")));

        DishRecommendAssembler.Result result = assembler.assemble(
                factory.create(BIZ_DATE, Collections.singletonList(dish), output, false));

        assertThat(result.getRecommendList()).isEmpty();
        assertThat(result.getRejectList()).isEmpty();
    }

    @Test
    void nutritionMatchShouldCarryMatchedIngredientsAndValidatedRawText() {
        Dish dish = dish(5L, "菠菜菜品", "菠菜");
        ValidatedExtractionOutput output = output(
                Collections.<ValidatedExtractionOutput.Allergen>emptyList(),
                Collections.singletonList(nutrition(NutritionKey.IRON, "f0-p1-s0")),
                Collections.<ValidatedExtractionOutput.AdviceItem<DietRequirementKey>>emptyList(),
                segments("f0-p1-s0", "建议补充铁"));
        when(nutritionMatcher.match(dish, NutritionKey.IRON)).thenReturn(
                new TagValue(TagState.RECOMMEND, Collections.singletonList("菠菜")));

        DishRecommendInput input = factory.create(BIZ_DATE, Collections.singletonList(dish),
                output, false);
        DishRecommendInput.Match match = input.getCandidateList().get(0).getMatchList().get(0);
        DishRecommendAssembler.Result result = assembler.assemble(input);

        assertThat(match.getMatchedIngredientList()).containsExactly("菠菜");
        assertThat(match.getRawText()).isEqualTo("建议补充铁");
        assertThat(input.isFormalAdvicePresent()).isTrue();
        assertThat(result.getRecommendList().get(0).getReasonList())
                .containsExactly("菠菜菜品——含菠菜；报告原文：「建议补充铁」");
    }

    @Test
    void suppressionShouldShortCircuitWithoutReadingTagsOrMatchingHealthAdvice() {
        DishRecommendInput input = factory.create(BIZ_DATE,
                Collections.singletonList(dish(6L, "普通菜品", "蔬菜")), emptyOutput(), true);

        assertThat(input.isSuppressDishRecommend()).isTrue();
        assertThat(input.getCandidateList()).isEmpty();
        verify(dishTagReadService, never()).read(any(LocalDate.class), anyList(), any());
        verify(nutritionMatcher, never()).match(any(Dish.class), any(NutritionKey.class));
    }

    private ValidatedExtractionOutput.Allergen allergen(AllergenKey key, boolean foodBorne,
                                                   String segmentId, String rawName) {
        return new ValidatedExtractionOutput.Allergen(0, 0, 0, 0, 0, 1,
                Collections.singletonList(segmentId), key, foodBorne, rawName, "阳性",
                AllergenResultStatus.POSITIVE);
    }

    private ValidatedExtractionOutput.AdviceItem<NutritionKey> nutrition(NutritionKey key,
                                                                   String segmentId) {
        return new ValidatedExtractionOutput.AdviceItem<NutritionKey>(0, 0, 0, 0, null, 0, 1,
                Collections.singletonList(segmentId), key, "建议原文",
                AdviceApplicability.CURRENT_PATIENT, AdviceStructuredSafety.NORMAL);
    }

    private ValidatedExtractionOutput.AdviceItem<DietRequirementKey> diet(DietRequirementKey key,
                                                                    String segmentId) {
        return new ValidatedExtractionOutput.AdviceItem<DietRequirementKey>(0, 0, 0, 0, null, 0, 1,
                Collections.singletonList(segmentId), key, "建议原文",
                AdviceApplicability.CURRENT_PATIENT, AdviceStructuredSafety.NORMAL);
    }

    private ValidatedExtractionOutput output(List<ValidatedExtractionOutput.Allergen> allergenList,
                                       List<ValidatedExtractionOutput.AdviceItem<NutritionKey>> nutritionList,
                                       List<ValidatedExtractionOutput.AdviceItem<DietRequirementKey>> dietList,
                                       Map<String, Segment> segmentByIdMap) {
        return new ValidatedExtractionOutput(
                Collections.<ValidatedExtractionOutput.ReportOverview>emptyList(),
                Collections.<ValidatedExtractionOutput.Section>emptyList(),
                Collections.<ValidatedExtractionOutput.Indicator>emptyList(),
                Collections.<ValidatedExtractionOutput.TextualFinding>emptyList(),
                Collections.<ValidatedExtractionOutput.SummaryConclusion>emptyList(),
                allergenList, nutritionList, dietList, Collections.<String>emptySet(),
                Collections.<String>emptySet(), segmentByIdMap);
    }

    private ValidatedExtractionOutput emptyOutput() {
        return output(Collections.<ValidatedExtractionOutput.Allergen>emptyList(),
                Collections.<ValidatedExtractionOutput.AdviceItem<NutritionKey>>emptyList(),
                Collections.<ValidatedExtractionOutput.AdviceItem<DietRequirementKey>>emptyList(),
                Collections.<String, Segment>emptyMap());
    }

    private Map<String, Segment> segments(String... idAndTextArray) {
        Map<String, Segment> segmentByIdMap = new LinkedHashMap<String, Segment>();
        for (int index = 0; index < idAndTextArray.length; index += 2) {
            String segmentId = idAndTextArray[index];
            String rawText = idAndTextArray[index + 1];
            segmentByIdMap.put(segmentId, new Segment(segmentId, rawText, rawText,
                    TextSource.NATIVE, null));
        }
        return segmentByIdMap;
    }

    private Map<String, Map<Long, TagValue>> tags(String enumKey, long dishId, TagValue value) {
        Map<Long, TagValue> valueByDishIdMap = new LinkedHashMap<Long, TagValue>();
        valueByDishIdMap.put(dishId, value);
        Map<String, Map<Long, TagValue>> resultMap =
                new LinkedHashMap<String, Map<Long, TagValue>>();
        resultMap.put(enumKey, valueByDishIdMap);
        return resultMap;
    }

    /** 构造带重量的菜品，让真实主料推导规则能生效。 */
    private Dish weighted(long dishId, String dishName, String... nameAndWeightArray) {
        List<DishIngredient> ingredientList =
                new ArrayList<DishIngredient>(nameAndWeightArray.length / 2);
        for (int index = 0; index < nameAndWeightArray.length; index += 2) {
            ingredientList.add(new DishIngredient(nameAndWeightArray[index],
                    new BigDecimal(nameAndWeightArray[index + 1])));
        }
        return new Dish(dishId, dishName, ingredientList);
    }

    private Dish dish(long dishId, String dishName, String ingredientName) {
        return new Dish(dishId, dishName,
                Arrays.asList(new DishIngredient(ingredientName, null)));
    }
}
