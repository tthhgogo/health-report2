package com.example.healthreport.assemble.dietadvice;

import com.example.healthreport.constants.AllergenGroup;
import com.example.healthreport.constants.AllergenGroups;
import com.example.healthreport.constants.AllergenKey;
import com.example.healthreport.constants.AllergenWord;
import com.example.healthreport.constants.DietRequirementContents;
import com.example.healthreport.constants.DietRequirementKey;
import com.example.healthreport.constants.DietRequirementRule;
import com.example.healthreport.constants.DisclaimerConstants;
import com.example.healthreport.constants.EmptyStateConstants;
import com.example.healthreport.constants.NutritionContents;
import com.example.healthreport.constants.NutritionKey;
import com.example.healthreport.constants.NutritionRule;
import com.example.healthreport.constants.ReviewStatus;
import com.example.healthreport.llm.extraction.DietTagsResult;
import com.example.healthreport.safety.HighRiskAdviceGate;
import com.example.healthreport.support.text.TextNormalizer;
import lombok.Getter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 模块三饮食建议组装器：「适宜多吃」与「忌吃少吃」两个食材清单（设计方案 §7.6）。
 *
 * <pre>
 * 忌吃少吃 = allergenSet(avoid ∪ hidden) ∪ dietAvoidSet
 * 适宜多吃 = (nutritionSet ∪ dietRecommendSet) − 忌吃少吃
 * </pre>
 *
 * <p>三条硬约束：差集放在最后统一减一次；差集用宽松匹配（包含任一忌口 matchWord 即移除，
 * 宁可多减）；hiddenFoods 一并进入忌吃少吃。{@code OTHER} 与被安全闸抑制的条目
 * 不产生任何食材，但仍随 {@code entryList} 下发（带 quote 与 section），前端当前不渲染。</p>
 */
@Service
public class DietAdviceAssembler {

    private final HighRiskAdviceGate highRiskAdviceGate;
    private final TextNormalizer textNormalizer = new TextNormalizer();

    public DietAdviceAssembler(HighRiskAdviceGate highRiskAdviceGate) {
        this.highRiskAdviceGate = highRiskAdviceGate;
    }

    /** 组装模块三；输入是第三次调用的已校验标签。 */
    public Result assemble(DietTagsResult dietTags) {
        if (dietTags == null) {
            throw new IllegalArgumentException("模块三输入不能为空");
        }
        Set<String> recommendFoodSet = new LinkedHashSet<String>();
        Set<String> avoidFoodSet = new LinkedHashSet<String>();
        Set<String> avoidMatchWordSet = new LinkedHashSet<String>();
        List<Entry> entryList = new ArrayList<Entry>();

        for (DietTagsResult.DietTag tag : dietTags.getReject()) {
            boolean suppressed = collectReject(tag, avoidFoodSet, avoidMatchWordSet);
            entryList.add(toEntry(tag, "REJECT", suppressed));
        }
        for (DietTagsResult.DietTag tag : dietTags.getRecommend()) {
            boolean suppressed = collectRecommend(tag, recommendFoodSet);
            entryList.add(toEntry(tag, "RECOMMEND", suppressed));
        }

        // 差集必须放在最后统一减一次：报告同时说「补铁」和「低脂」时，
        // 猪肝在营养推荐里也在饮食忌口里，只在维度内部判断会漏掉另一个维度的禁忌。
        subtractLoosely(recommendFoodSet, avoidMatchWordSet);

        List<String> recommendList = new ArrayList<String>(recommendFoodSet);
        List<String> avoidList = new ArrayList<String>(avoidFoodSet);
        return new Result(recommendList, avoidList,
                recommendList.isEmpty() ? EmptyStateConstants.MODULE_THREE_RECOMMEND : null,
                avoidList.isEmpty() ? EmptyStateConstants.MODULE_THREE_AVOID : null,
                entryList, DisclaimerConstants.MODULE_THREE);
    }

    /**
     * 收集忌吃少吃侧食材。
     *
     * @return 该条是否被按 OTHER 路径抑制（不产生食材）
     */
    private boolean collectReject(DietTagsResult.DietTag tag, Set<String> avoidFoodSet,
                                  Set<String> avoidMatchWordSet) {
        if ("OTHER".equals(tag.getEnumKey())) {
            return true;
        }
        if ("ALLERGEN".equals(tag.getDimension())) {
            // 过敏原不过安全闸（忌口本身就是要展示的安全信息，方向不能反）。
            AllergenKey allergenKey = AllergenKey.valueOf(tag.getEnumKey());
            AllergenGroup group = AllergenGroups.ALL.get(allergenKey);
            if (group == null || !AllergenGroups.FOOD_BORNE_KEYS.contains(allergenKey)) {
                // 非食入性过敏原：展示原文，不生成需避免食材（§7.2）。
                return true;
            }
            for (AllergenWord word : group.getWordList()) {
                if (word.getReviewStatus() != ReviewStatus.REVIEWED) {
                    continue;
                }
                // avoid 与 hidden 两桶都进清单：需求 §7-3 的「易忽略食物」没有别的地方可去。
                avoidFoodSet.add(word.getDisplayName());
                avoidMatchWordSet.add(word.getMatchWord());
            }
            return false;
        }
        // DIET 反向：过安全闸后取需避免食材。
        if (highRiskAdviceGate.shouldSuppress(tag.getQuote())) {
            return true;
        }
        DietRequirementRule rule = DietRequirementContents.ALL.get(
                DietRequirementKey.valueOf(tag.getEnumKey()));
        if (rule == null || rule.getReviewStatus() != ReviewStatus.REVIEWED) {
            return true;
        }
        for (String food : rule.getAvoidFoodList()) {
            avoidFoodSet.add(food);
            avoidMatchWordSet.add(food);
        }
        return false;
    }

    /**
     * 收集适宜多吃侧食材。
     *
     * @return 该条是否被按 OTHER 路径抑制（不产生食材）
     */
    private boolean collectRecommend(DietTagsResult.DietTag tag, Set<String> recommendFoodSet) {
        if ("OTHER".equals(tag.getEnumKey())) {
            return true;
        }
        if (highRiskAdviceGate.shouldSuppress(tag.getQuote())) {
            return true;
        }
        if ("NUTRITION".equals(tag.getDimension())) {
            NutritionRule rule = NutritionContents.ALL.get(NutritionKey.valueOf(tag.getEnumKey()));
            if (rule == null || rule.getReviewStatus() != ReviewStatus.REVIEWED) {
                return true;
            }
            recommendFoodSet.addAll(rule.getRecommendableFoodList());
            recommendFoodSet.addAll(rule.getDisplayOnlyFoodList());
            return false;
        }
        if ("DIET".equals(tag.getDimension())) {
            DietRequirementRule rule = DietRequirementContents.ALL.get(
                    DietRequirementKey.valueOf(tag.getEnumKey()));
            if (rule == null || rule.getReviewStatus() != ReviewStatus.REVIEWED) {
                return true;
            }
            recommendFoodSet.addAll(rule.getRecommendableFoodList());
            recommendFoodSet.addAll(rule.getDisplayOnlyFoodList());
            return false;
        }
        return true;
    }

    /**
     * 宽松差集：适宜多吃里的食材只要【包含】忌吃少吃侧任意一个 matchWord 就移除。
     * <p>误判代价不对称——少推荐一条只是少条信息，把过敏原推给用户是一级红线，宁可多减。</p>
     */
    private void subtractLoosely(Set<String> recommendFoodSet, Set<String> avoidMatchWordSet) {
        List<String> normalizedAvoidList = new ArrayList<String>(avoidMatchWordSet.size());
        for (String avoidWord : avoidMatchWordSet) {
            String normalized = textNormalizer.normalize(avoidWord);
            if (!normalized.isEmpty()) {
                normalizedAvoidList.add(normalized);
            }
        }
        Iterator<String> iterator = recommendFoodSet.iterator();
        while (iterator.hasNext()) {
            String normalizedFood = textNormalizer.normalize(iterator.next());
            for (String avoidWord : normalizedAvoidList) {
                if (normalizedFood.contains(avoidWord)) {
                    iterator.remove();
                    break;
                }
            }
        }
    }

    private Entry toEntry(DietTagsResult.DietTag tag, String direction, boolean suppressed) {
        return new Entry(tag.getDimension(), tag.getEnumKey(), direction, tag.getSection(),
                tag.getItemNo(), tag.getQuote(), tag.getRawText(), suppressed);
    }

    /** 模块三完整返回结构。 */
    @Getter
    public static final class Result {
        private final List<String> recommendFoodList;
        private final List<String> avoidFoodList;
        private final String recommendEmptyState;
        private final String avoidEmptyState;
        private final List<Entry> entryList;
        private final String disclaimer;

        Result(List<String> recommendFoodList, List<String> avoidFoodList,
               String recommendEmptyState, String avoidEmptyState,
               List<Entry> entryList, String disclaimer) {
            this.recommendFoodList = Collections.unmodifiableList(
                    new ArrayList<String>(recommendFoodList));
            this.avoidFoodList = Collections.unmodifiableList(new ArrayList<String>(avoidFoodList));
            this.recommendEmptyState = recommendEmptyState;
            this.avoidEmptyState = avoidEmptyState;
            this.entryList = Collections.unmodifiableList(new ArrayList<Entry>(entryList));
            this.disclaimer = disclaimer;
        }
    }

    /**
     * 一条已校验建议的来源与抑制状态；前端当前不渲染，保留用于排障与恢复来源标注
     * （设计方案 §7.6：section / itemNo / quote 不要因为当前不展示就删掉）。
     */
    @Getter
    public static final class Entry {
        private final String dimension;
        private final String enumKey;
        private final String direction;
        private final String section;
        private final Integer itemNo;
        private final String quote;
        private final String rawText;
        private final boolean structuredOutputSuppressed;

        Entry(String dimension, String enumKey, String direction, String section, Integer itemNo,
              String quote, String rawText, boolean structuredOutputSuppressed) {
            this.dimension = dimension;
            this.enumKey = enumKey;
            this.direction = direction;
            this.section = section;
            this.itemNo = itemNo;
            this.quote = quote;
            this.rawText = rawText;
            this.structuredOutputSuppressed = structuredOutputSuppressed;
        }
    }
}
