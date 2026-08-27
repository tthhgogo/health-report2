package com.example.healthreport.assemble.dishrecommend;

import com.example.healthreport.constants.AllergenGroup;
import com.example.healthreport.constants.AllergenGroups;
import com.example.healthreport.constants.AllergenKey;
import com.example.healthreport.constants.DietRequirementKey;
import com.example.healthreport.constants.NutritionKey;
import com.example.healthreport.dish.Dish;
import com.example.healthreport.dish.DishTagReadService;
import com.example.healthreport.dish.NutritionMatcher;
import com.example.healthreport.dish.TagState;
import com.example.healthreport.dish.TagValue;
import com.example.healthreport.llm.extraction.ValidatedExtractionOutput;
import com.example.healthreport.safety.AllergenKeywordFallback;
import com.example.healthreport.safety.HighRiskAdviceGate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从校验后的报告建议和当日在架菜品建立模块四输入。
 * <p>本工厂是离线标签读取、营养确定性交集和过敏硬兜底的唯一合并点；硬兜底命中
 * 无条件覆盖模型状态，模型不能推翻 Java 安全结果。</p>
 */
@Service
public class DishRecommendInputFactory {

    /** 多段报告原文保持段边界时使用的展示换行符，不改变段内原文。 */
    private static final String RAW_TEXT_SEPARATOR = "\n";

    private final DishTagReadService dishTagReadService;
    private final NutritionMatcher nutritionMatcher;
    private final AllergenKeywordFallback allergenKeywordFallback;
    private final HighRiskAdviceGate highRiskAdviceGate;

    public DishRecommendInputFactory(DishTagReadService dishTagReadService,
                               NutritionMatcher nutritionMatcher,
                               AllergenKeywordFallback allergenKeywordFallback,
                               HighRiskAdviceGate highRiskAdviceGate) {
        this.dishTagReadService = dishTagReadService;
        this.nutritionMatcher = nutritionMatcher;
        this.allergenKeywordFallback = allergenKeywordFallback;
        this.highRiskAdviceGate = highRiskAdviceGate;
    }

    /**
     * 合并本次报告实际生效的维度，产出可由 {@link DishRecommendAssembler} 完整裁决的候选集。
     *
     * @param bizDate 调度入口或在线请求确定的业务日
     * @param dishList 当日在架菜品，只读
     * @param output 已完成 Schema、引用和来源校验的 LLM-A 输出
     * @param suppressDishRecommend 安全降级要求的模块整体抑制开关
     * @return 不含任何在线 LLM-B 调用的模块四输入
     */
    public DishRecommendInput create(LocalDate bizDate, List<Dish> dishList,
                               ValidatedExtractionOutput output, boolean suppressDishRecommend) {
        if (bizDate == null || dishList == null || output == null) {
            throw new IllegalArgumentException("模块四输入工厂参数不能为空");
        }
        if (suppressDishRecommend) {
            return new DishRecommendInput(true, false,
                    Collections.<DishRecommendInput.Candidate>emptyList());
        }

        AdviceDimensions dimensions = dimensions(output);
        Set<String> effectiveEnumKeySet = effectiveEnumKeys(dimensions);
        Map<String, Map<Long, TagValue>> tagByEnumKeyMap = effectiveEnumKeySet.isEmpty()
                ? Collections.<String, Map<Long, TagValue>>emptyMap()
                : dishTagReadService.read(bizDate, dishList, effectiveEnumKeySet);

        List<DishRecommendInput.Candidate> candidateList =
                new ArrayList<DishRecommendInput.Candidate>(dishList.size());
        for (Dish dish : dishList) {
            List<DishRecommendInput.Match> matchList = new ArrayList<DishRecommendInput.Match>();
            addFormalAllergenMatches(matchList, dish, dimensions.allergenRawTextByKeyMap,
                    tagByEnumKeyMap);
            addOtherAllergenMatches(matchList, dish, dimensions.otherAllergenList);
            addNutritionMatches(matchList, dish, dimensions.nutritionRawTextByKeyMap);
            addDietMatches(matchList, dish, dimensions.dietRawTextByKeyMap, tagByEnumKeyMap);
            candidateList.add(new DishRecommendInput.Candidate(dish, matchList));
        }
        return new DishRecommendInput(false, dimensions.formalAdvicePresent, candidateList);
    }

    private AdviceDimensions dimensions(ValidatedExtractionOutput output) {
        AdviceDimensions dimensions = new AdviceDimensions();
        for (ValidatedExtractionOutput.Allergen allergen : output.getAllergenList()) {
            if (allergen.getEnumKey() == AllergenKey.OTHER) {
                if (allergen.isFoodBorne()) {
                    dimensions.otherAllergenList.add(new OtherAllergen(allergen.getRawName(),
                            rawText(output, allergen.getSegmentIdList())));
                }
                continue;
            }
            AllergenGroup group = AllergenGroups.ALL.get(allergen.getEnumKey());
            if (group != null && group.isFoodBorne()) {
                addRawText(dimensions.allergenRawTextByKeyMap, allergen.getEnumKey(),
                        output.rawTextList(allergen.getSegmentIdList()));
                dimensions.formalAdvicePresent = true;
            }
        }
        for (ValidatedExtractionOutput.AdviceItem<NutritionKey> nutrition
                : output.getNutritionSupplementList()) {
            List<String> rawTextList = output.rawTextList(nutrition.getSegmentIdList());
            if (nutrition.getEnumKey() != NutritionKey.OTHER
                    && !highRiskAdviceGate.shouldSuppress(rawTextList)) {
                addRawText(dimensions.nutritionRawTextByKeyMap, nutrition.getEnumKey(),
                        rawTextList);
                dimensions.formalAdvicePresent = true;
            }
        }
        for (ValidatedExtractionOutput.AdviceItem<DietRequirementKey> diet
                : output.getDietRequirementList()) {
            List<String> rawTextList = output.rawTextList(diet.getSegmentIdList());
            if (diet.getEnumKey() != DietRequirementKey.OTHER
                    && !highRiskAdviceGate.shouldSuppress(rawTextList)) {
                addRawText(dimensions.dietRawTextByKeyMap, diet.getEnumKey(), rawTextList);
                dimensions.formalAdvicePresent = true;
            }
        }
        return dimensions;
    }

    private <K> void addRawText(Map<K, LinkedHashSet<String>> rawTextByKeyMap, K key,
                                List<String> rawTextList) {
        LinkedHashSet<String> rawTextSet = rawTextByKeyMap.get(key);
        if (rawTextSet == null) {
            rawTextSet = new LinkedHashSet<String>();
            rawTextByKeyMap.put(key, rawTextSet);
        }
        rawTextSet.addAll(rawTextList);
    }

    private Set<String> effectiveEnumKeys(AdviceDimensions dimensions) {
        Set<String> resultSet = new LinkedHashSet<String>(
                dimensions.allergenRawTextByKeyMap.size()
                        + dimensions.dietRawTextByKeyMap.size());
        for (AllergenKey key : dimensions.allergenRawTextByKeyMap.keySet()) {
            resultSet.add(key.name());
        }
        for (DietRequirementKey key : dimensions.dietRawTextByKeyMap.keySet()) {
            resultSet.add(key.name());
        }
        return resultSet;
    }

    private void addFormalAllergenMatches(List<DishRecommendInput.Match> matchList, Dish dish,
                                          Map<AllergenKey, LinkedHashSet<String>> rawTextByKeyMap,
                                          Map<String, Map<Long, TagValue>> tagByEnumKeyMap) {
        for (Map.Entry<AllergenKey, LinkedHashSet<String>> entry
                : rawTextByKeyMap.entrySet()) {
            AllergenGroup group = AllergenGroups.ALL.get(entry.getKey());
            TagValue modelValue = tagValue(tagByEnumKeyMap, entry.getKey().name(),
                    dish.getDishId());
            TagState finalState = allergenKeywordFallback.matches(group, dish)
                    ? TagState.REJECT : modelValue.getState();
            matchList.add(new DishRecommendInput.Match(finalState, true, true,
                    DishRecommendInput.TagType.ALLERGY, group.getDisplayName() + "过敏",
                    modelValue.getMatchedIngredientList(), joinRawText(entry.getValue())));
        }
    }

    private void addOtherAllergenMatches(List<DishRecommendInput.Match> matchList, Dish dish,
                                         List<OtherAllergen> otherAllergenList) {
        for (OtherAllergen allergen : otherAllergenList) {
            if (allergenKeywordFallback.matchesOther(allergen.rawName, true, dish)) {
                matchList.add(new DishRecommendInput.Match(TagState.REJECT, true, true,
                        DishRecommendInput.TagType.ALLERGY, allergen.rawName + "过敏",
                        Collections.<String>emptyList(), allergen.rawText));
            }
        }
    }

    private void addNutritionMatches(List<DishRecommendInput.Match> matchList, Dish dish,
                                     Map<NutritionKey, LinkedHashSet<String>> rawTextByKeyMap) {
        for (Map.Entry<NutritionKey, LinkedHashSet<String>> entry
                : rawTextByKeyMap.entrySet()) {
            TagValue value = nutritionMatcher.match(dish, entry.getKey());
            matchList.add(new DishRecommendInput.Match(value.getState(), false, false,
                    DishRecommendInput.TagType.NUTRITION, nutritionTagText(entry.getKey()),
                    value.getMatchedIngredientList(), joinRawText(entry.getValue())));
        }
    }

    private void addDietMatches(List<DishRecommendInput.Match> matchList, Dish dish,
                                Map<DietRequirementKey, LinkedHashSet<String>> rawTextByKeyMap,
                                Map<String, Map<Long, TagValue>> tagByEnumKeyMap) {
        for (Map.Entry<DietRequirementKey, LinkedHashSet<String>> entry
                : rawTextByKeyMap.entrySet()) {
            TagValue value = tagValue(tagByEnumKeyMap, entry.getKey().name(), dish.getDishId());
            matchList.add(new DishRecommendInput.Match(value.getState(), true, false,
                    DishRecommendInput.TagType.DIET_AVOID, dietRejectTagText(entry.getKey()),
                    value.getMatchedIngredientList(), joinRawText(entry.getValue())));
        }
    }

    private TagValue tagValue(Map<String, Map<Long, TagValue>> tagByEnumKeyMap,
                              String enumKey, long dishId) {
        Map<Long, TagValue> valueByDishIdMap = tagByEnumKeyMap.get(enumKey);
        if (valueByDishIdMap == null || valueByDishIdMap.get(dishId) == null) {
            // 读取结果缺组合时按缺标签处理，绝不静默补成 NEUTRAL。
            return TagValue.of(TagState.TAG_MISSING);
        }
        return valueByDishIdMap.get(dishId);
    }

    private String rawText(ValidatedExtractionOutput output, List<String> segmentIdList) {
        return joinRawText(new LinkedHashSet<String>(output.rawTextList(segmentIdList)));
    }

    private String joinRawText(Set<String> rawTextSet) {
        StringBuilder builder = new StringBuilder();
        for (String rawText : rawTextSet) {
            if (builder.length() > 0) {
                builder.append(RAW_TEXT_SEPARATOR);
            }
            builder.append(rawText);
        }
        return builder.toString();
    }

    /** 返回营养维度命中时展示的正面标签文字。 */
    private String nutritionTagText(NutritionKey key) {
        switch (key) {
            case IRON:
                return "补铁";
            case CALCIUM:
                return "补钙";
            case PROTEIN:
                return "高蛋白";
            case VITAMIN_D:
                return "补维生素D";
            case VITAMIN_B12:
                return "补维生素B12";
            case FOLATE:
                return "补叶酸";
            case DIETARY_FIBER:
                return "高纤维";
            case ZINC:
                return "补锌";
            case POTASSIUM:
                return "补钾";
            default:
                throw new IllegalArgumentException("非正式营养维度");
        }
    }

    /** 返回违反饮食要求时的固定负面标签，不让模型生成展示文案。 */
    private String dietRejectTagText(DietRequirementKey key) {
        switch (key) {
            case LOW_FAT:
                return "高脂";
            case LOW_SODIUM:
                return "高盐";
            case LOW_ADDED_SUGAR:
                return "高糖";
            case LOW_PURINE:
                return "高嘌呤";
            case LOW_CHOLESTEROL:
                return "高胆固醇";
            case LOW_CALORIE:
                return "高热量";
            case HIGH_FIBER:
                return "低纤维";
            case LIMIT_ALCOHOL:
                return "含酒精";
            case LIGHT_DIET:
                return "不清淡";
            default:
                throw new IllegalArgumentException("非正式饮食注意维度");
        }
    }

    /** 报告中生效维度及其去重后的原文证据。 */
    private static final class AdviceDimensions {
        private final Map<AllergenKey, LinkedHashSet<String>> allergenRawTextByKeyMap =
                new LinkedHashMap<AllergenKey, LinkedHashSet<String>>();
        private final List<OtherAllergen> otherAllergenList = new ArrayList<OtherAllergen>();
        private final Map<NutritionKey, LinkedHashSet<String>> nutritionRawTextByKeyMap =
                new LinkedHashMap<NutritionKey, LinkedHashSet<String>>();
        private final Map<DietRequirementKey, LinkedHashSet<String>> dietRawTextByKeyMap =
                new LinkedHashMap<DietRequirementKey, LinkedHashSet<String>>();
        private boolean formalAdvicePresent;
    }

    /** 一个不进入枚举标签读取、只做食源性原文字面匹配的 OTHER 过敏原。 */
    private static final class OtherAllergen {
        private final String rawName;
        private final String rawText;

        private OtherAllergen(String rawName, String rawText) {
            this.rawName = rawName;
            this.rawText = rawText;
        }
    }
}
