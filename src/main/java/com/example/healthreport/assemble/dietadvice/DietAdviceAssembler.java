package com.example.healthreport.assemble.dietadvice;

import com.example.healthreport.constants.AllergenGroup;
import com.example.healthreport.constants.AllergenGroups;
import com.example.healthreport.constants.AllergenKey;
import com.example.healthreport.constants.AllergenWord;
import com.example.healthreport.constants.Bucket;
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
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模块三饮食建议组装器：按需求 §7-3 的三维度卡片分区
 * （过敏提醒 / 营养补充 / 饮食注意，设计方案 §7.6，2026-09-04 改版）。
 *
 * <p>三条硬约束：</p>
 * <ul>
 * <li>三个维度按需求 §7-5 各自独立展示，维度之间不再做食材差集——
 * 「补铁」推荐的猪肝与「低脂」避免的动物内脏允许同时出现；</li>
 * <li>唯一保留的跨维度运算是过敏原红线差集：把过敏原食材推荐给用户是一级红线（§0-6），
 * 食入性过敏原全部 matchWord 必须从营养补充与饮食注意的<b>全部正向内容</b>里宽松减掉
 * （包含即移除，宁可多减）——不只 recommendFoodList，摄入量说明、搭配小贴士、烹饪建议
 * 这些说明文案同样会把过敏原推给用户（如牛奶过敏时的「每天300~500ml液态奶」）；
 * 方向为「避免」的清单不减；</li>
 * <li>{@code OTHER}、安全闸命中与内容未过审的条目按「仅原文」卡片展示
 * （只有来源引用，不生成食材，设计方案 §7.4），同一枚举先「仅原文」后完整时用完整卡片替换，反向不降级。</li>
 * </ul>
 *
 * <p>卡片顺序为 reject、recommend 数组的原始顺序，同一枚举去重取首次出现。
 * {@code entryList} 逐条保留来源与抑制状态，语义不变，仍用于排障。</p>
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
        // 过敏词必须在任何推荐卡片生成之前收齐：过敏原全部在 reject 数组，但与 DIET 条目交错出现。
        List<String> normalizedAllergenWordList = collectAllergenMatchWords(dietTags.getReject());

        Map<String, AllergyReminder> allergyCardMap = new LinkedHashMap<String, AllergyReminder>();
        Map<String, NutritionSupplement> nutritionCardMap =
                new LinkedHashMap<String, NutritionSupplement>();
        Map<String, DietAttention> dietCardMap = new LinkedHashMap<String, DietAttention>();
        Set<String> suppressedCardKeySet = new HashSet<String>();
        List<Entry> entryList = new ArrayList<Entry>();

        for (DietTagsResult.DietTag tag : dietTags.getReject()) {
            boolean suppressed = collect(tag, allergyCardMap, nutritionCardMap, dietCardMap,
                    suppressedCardKeySet, normalizedAllergenWordList);
            entryList.add(toEntry(tag, "REJECT", suppressed));
        }
        for (DietTagsResult.DietTag tag : dietTags.getRecommend()) {
            boolean suppressed = collect(tag, allergyCardMap, nutritionCardMap, dietCardMap,
                    suppressedCardKeySet, normalizedAllergenWordList);
            entryList.add(toEntry(tag, "RECOMMEND", suppressed));
        }

        List<AllergyReminder> allergyList =
                new ArrayList<AllergyReminder>(allergyCardMap.values());
        List<NutritionSupplement> nutritionList =
                new ArrayList<NutritionSupplement>(nutritionCardMap.values());
        List<DietAttention> dietList = new ArrayList<DietAttention>(dietCardMap.values());
        return new Result(allergyList,
                allergyList.isEmpty() ? EmptyStateConstants.MODULE_THREE_ALLERGY : null,
                nutritionList,
                nutritionList.isEmpty() ? EmptyStateConstants.MODULE_THREE_NUTRITION : null,
                dietList,
                dietList.isEmpty() ? EmptyStateConstants.MODULE_THREE_DIET : null,
                entryList, DisclaimerConstants.MODULE_THREE);
    }

    /**
     * 一级红线差集的词源：食入性过敏原全部已审核词条的 matchWord（avoid 与 hidden 两桶都算），
     * 规范化后返回。非食入性与 {@code OTHER} 不产生词。
     */
    private List<String> collectAllergenMatchWords(List<DietTagsResult.DietTag> rejectList) {
        List<String> normalizedWordList = new ArrayList<String>();
        for (DietTagsResult.DietTag tag : rejectList) {
            if (!"ALLERGEN".equals(tag.getDimension()) || "OTHER".equals(tag.getEnumKey())) {
                continue;
            }
            AllergenKey allergenKey = AllergenKey.valueOf(tag.getEnumKey());
            AllergenGroup group = AllergenGroups.ALL.get(allergenKey);
            if (group == null || !AllergenGroups.FOOD_BORNE_KEYS.contains(allergenKey)) {
                continue;
            }
            for (AllergenWord word : group.getWordList()) {
                if (word.getReviewStatus() != ReviewStatus.REVIEWED) {
                    continue;
                }
                String normalized = textNormalizer.normalize(word.getMatchWord());
                if (!normalized.isEmpty()) {
                    normalizedWordList.add(normalized);
                }
            }
        }
        return normalizedWordList;
    }

    /**
     * 把一条已校验标签落到所属维度的卡片区。
     *
     * @return 该条是否不产生食材（OTHER 路径 / 安全闸 / 未过审 / 非食入性）
     */
    private boolean collect(DietTagsResult.DietTag tag,
                            Map<String, AllergyReminder> allergyCardMap,
                            Map<String, NutritionSupplement> nutritionCardMap,
                            Map<String, DietAttention> dietCardMap,
                            Set<String> suppressedCardKeySet,
                            List<String> normalizedAllergenWordList) {
        if ("ALLERGEN".equals(tag.getDimension())) {
            return collectAllergy(tag, allergyCardMap);
        }
        if ("NUTRITION".equals(tag.getDimension())) {
            return collectNutrition(tag, nutritionCardMap, suppressedCardKeySet,
                    normalizedAllergenWordList);
        }
        if ("DIET".equals(tag.getDimension())) {
            return collectDiet(tag, dietCardMap, suppressedCardKeySet, normalizedAllergenWordList);
        }
        return true;
    }

    /**
     * 过敏提醒卡片。过敏原不过安全闸：忌口本身就是要展示的安全信息，方向不能反。
     */
    private boolean collectAllergy(DietTagsResult.DietTag tag,
                                   Map<String, AllergyReminder> cardMap) {
        if ("OTHER".equals(tag.getEnumKey())) {
            // 枚举外过敏原只展示原文与来源，Java 不猜其是否食入性（设计方案 §7.2）。
            putIfAbsent(cardMap, "OTHER:" + tag.getQuote(), new AllergyReminder("OTHER", null,
                    null, Collections.<String>emptyList(), Collections.<String>emptyList(),
                    toSource(tag)));
            return true;
        }
        AllergenKey allergenKey = AllergenKey.valueOf(tag.getEnumKey());
        AllergenGroup group = AllergenGroups.ALL.get(allergenKey);
        if (group == null) {
            return true;
        }
        if (!AllergenGroups.FOOD_BORNE_KEYS.contains(allergenKey)) {
            // 非食入性过敏原：展示过敏原名与来源，不生成需避免食材（§7.2）。
            putIfAbsent(cardMap, tag.getEnumKey(), new AllergyReminder(tag.getEnumKey(),
                    group.getDisplayName(), Boolean.FALSE, Collections.<String>emptyList(),
                    Collections.<String>emptyList(), toSource(tag)));
            return true;
        }
        List<String> avoidFoodList = new ArrayList<String>();
        List<String> hiddenFoodList = new ArrayList<String>();
        for (AllergenWord word : group.getWordList()) {
            if (word.getReviewStatus() != ReviewStatus.REVIEWED) {
                continue;
            }
            if (word.getBucket() == Bucket.HIDDEN) {
                hiddenFoodList.add(word.getDisplayName());
            } else {
                avoidFoodList.add(word.getDisplayName());
            }
        }
        putIfAbsent(cardMap, tag.getEnumKey(), new AllergyReminder(tag.getEnumKey(),
                group.getDisplayName(), Boolean.TRUE, avoidFoodList, hiddenFoodList,
                toSource(tag)));
        return false;
    }

    /** 营养补充卡片。 */
    private boolean collectNutrition(DietTagsResult.DietTag tag,
                                     Map<String, NutritionSupplement> cardMap,
                                     Set<String> suppressedCardKeySet,
                                     List<String> normalizedAllergenWordList) {
        if ("OTHER".equals(tag.getEnumKey())) {
            putCard(cardMap, suppressedCardKeySet, "NUTRITION:OTHER:" + tag.getQuote(),
                    sourceOnlyNutrition("OTHER", tag), true);
            return true;
        }
        NutritionRule rule = NutritionContents.ALL.get(NutritionKey.valueOf(tag.getEnumKey()));
        if (highRiskAdviceGate.shouldSuppress(tag.getQuote())
                || rule == null || rule.getReviewStatus() != ReviewStatus.REVIEWED) {
            putCard(cardMap, suppressedCardKeySet, "NUTRITION:" + tag.getEnumKey(),
                    sourceOnlyNutrition(tag.getEnumKey(), tag), true);
            return true;
        }
        Set<String> recommendFoodSet = new LinkedHashSet<String>();
        recommendFoodSet.addAll(rule.getRecommendableFoodList());
        recommendFoodSet.addAll(rule.getDisplayOnlyFoodList());
        NutritionSupplement card = new NutritionSupplement(tag.getEnumKey(), rule.getDisplayName(),
                removeAllergenConflicts(recommendFoodSet, normalizedAllergenWordList),
                removeAllergenConflicts(rule.getIntakeNoteList(), normalizedAllergenWordList),
                removeAllergenConflicts(rule.getPairingTipList(), normalizedAllergenWordList),
                toSource(tag));
        putCard(cardMap, suppressedCardKeySet, "NUTRITION:" + tag.getEnumKey(), card, false);
        return false;
    }

    /** 饮食注意卡片。无论条目来自 recommend 还是 reject 数组，卡片都同时带推荐与需避免两侧。 */
    private boolean collectDiet(DietTagsResult.DietTag tag,
                                Map<String, DietAttention> cardMap,
                                Set<String> suppressedCardKeySet,
                                List<String> normalizedAllergenWordList) {
        if ("OTHER".equals(tag.getEnumKey())) {
            putCard(cardMap, suppressedCardKeySet, "DIET:OTHER:" + tag.getQuote(),
                    sourceOnlyDiet("OTHER", tag), true);
            return true;
        }
        DietRequirementRule rule = DietRequirementContents.ALL.get(
                DietRequirementKey.valueOf(tag.getEnumKey()));
        if (highRiskAdviceGate.shouldSuppress(tag.getQuote())
                || rule == null || rule.getReviewStatus() != ReviewStatus.REVIEWED) {
            putCard(cardMap, suppressedCardKeySet, "DIET:" + tag.getEnumKey(),
                    sourceOnlyDiet(tag.getEnumKey(), tag), true);
            return true;
        }
        Set<String> recommendFoodSet = new LinkedHashSet<String>();
        recommendFoodSet.addAll(rule.getRecommendableFoodList());
        recommendFoodSet.addAll(rule.getDisplayOnlyFoodList());
        // avoidFoodList 方向是「避免」，含过敏原恰是要展示的安全信息，不做差集。
        DietAttention card = new DietAttention(tag.getEnumKey(), rule.getDisplayName(),
                removeAllergenConflicts(recommendFoodSet, normalizedAllergenWordList),
                rule.getAvoidFoodList(),
                removeAllergenConflicts(rule.getCookingTipList(), normalizedAllergenWordList),
                toSource(tag));
        putCard(cardMap, suppressedCardKeySet, "DIET:" + tag.getEnumKey(), card, false);
        return false;
    }

    /**
     * 过敏原红线差集：正向内容（推荐食材、摄入量说明、搭配小贴士、烹饪建议）
     * 只要【包含】任意一个过敏 matchWord 就整条移除。
     * <p>误判代价不对称——少推荐一条只是少条信息，把过敏原推给用户是一级红线，宁可多减。</p>
     */
    private List<String> removeAllergenConflicts(java.util.Collection<String> positiveContentList,
                                                 List<String> normalizedAllergenWordList) {
        List<String> keptList = new ArrayList<String>(positiveContentList.size());
        for (String food : positiveContentList) {
            String normalizedFood = textNormalizer.normalize(food);
            boolean hit = false;
            for (String allergenWord : normalizedAllergenWordList) {
                if (normalizedFood.contains(allergenWord)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                keptList.add(food);
            }
        }
        return keptList;
    }

    private NutritionSupplement sourceOnlyNutrition(String enumKey, DietTagsResult.DietTag tag) {
        return new NutritionSupplement(enumKey, null, Collections.<String>emptyList(),
                Collections.<String>emptyList(), Collections.<String>emptyList(), toSource(tag));
    }

    private DietAttention sourceOnlyDiet(String enumKey, DietTagsResult.DietTag tag) {
        return new DietAttention(enumKey, null, Collections.<String>emptyList(),
                Collections.<String>emptyList(), Collections.<String>emptyList(), toSource(tag));
    }

    private <T> void putIfAbsent(Map<String, T> cardMap, String cardKey, T card) {
        if (!cardMap.containsKey(cardKey)) {
            cardMap.put(cardKey, card);
        }
    }

    /** 同一枚举去重取首次出现；先「仅原文」后完整时用完整卡片替换，反向不降级。 */
    private <T> void putCard(Map<String, T> cardMap, Set<String> suppressedCardKeySet,
                             String cardKey, T card, boolean suppressed) {
        if (!cardMap.containsKey(cardKey)) {
            cardMap.put(cardKey, card);
            if (suppressed) {
                suppressedCardKeySet.add(cardKey);
            }
            return;
        }
        if (!suppressed && suppressedCardKeySet.remove(cardKey)) {
            cardMap.put(cardKey, card);
        }
    }

    /** 来源标注完全由字段拼出，Java 不做任何推断；itemNo 为 null 时不写条号，不拿数组下标顶替。 */
    private Source toSource(DietTagsResult.DietTag tag) {
        String section = tag.getSection();
        String displayText = (section == null || section.isEmpty())
                ? "来源：" + tag.getQuote()
                : "来源：" + section + "–" + tag.getQuote();
        return new Source(section, tag.getItemNo(), tag.getQuote(), displayText);
    }

    private Entry toEntry(DietTagsResult.DietTag tag, String direction, boolean suppressed) {
        return new Entry(tag.getDimension(), tag.getEnumKey(), direction, tag.getSection(),
                tag.getItemNo(), tag.getQuote(), tag.getRawText(), suppressed);
    }

    /** 模块三完整返回结构：三维度卡片分区，各自独立空态（需求 §7-3 / §7-4）。 */
    @ApiModel(value = "DietAdviceResult", description = "模块三（饮食建议）完整返回结构；按过敏提醒/营养补充/饮食注意三维度分区")
    @Getter
    public static final class Result {

        @ApiModelProperty(value = "过敏提醒卡片列表", required = true)
        private final List<AllergyReminder> allergyReminderList;

        @ApiModelProperty(value = "过敏提醒分区为空时的空态文案；有内容时为 null",
                example = "本次报告未提取到过敏原相关内容")
        private final String allergyEmptyState;

        @ApiModelProperty(value = "营养补充卡片列表", required = true)
        private final List<NutritionSupplement> nutritionSupplementList;

        @ApiModelProperty(value = "营养补充分区为空时的空态文案；有内容时为 null",
                example = "本次报告未提取到明确的营养补充建议")
        private final String nutritionEmptyState;

        @ApiModelProperty(value = "饮食注意卡片列表", required = true)
        private final List<DietAttention> dietAttentionList;

        @ApiModelProperty(value = "饮食注意分区为空时的空态文案；有内容时为 null",
                example = "本次报告未提取到明确的饮食注意要求")
        private final String dietAttentionEmptyState;

        @ApiModelProperty(value = "逐条建议的来源与抑制状态；排障用，前端不渲染", required = true)
        private final List<Entry> entryList;

        @ApiModelProperty(value = "模块底部声明", required = true,
                example = "以上建议均基于体检报告原文，不构成医疗或营养处方，具体饮食方案请遵医嘱。")
        private final String disclaimer;

        @JsonCreator
        Result(@JsonProperty("allergyReminderList") List<AllergyReminder> allergyReminderList,
               @JsonProperty("allergyEmptyState") String allergyEmptyState,
               @JsonProperty("nutritionSupplementList") List<NutritionSupplement> nutritionSupplementList,
               @JsonProperty("nutritionEmptyState") String nutritionEmptyState,
               @JsonProperty("dietAttentionList") List<DietAttention> dietAttentionList,
               @JsonProperty("dietAttentionEmptyState") String dietAttentionEmptyState,
               @JsonProperty("entryList") List<Entry> entryList,
               @JsonProperty("disclaimer") String disclaimer) {
            this.allergyReminderList = Collections.unmodifiableList(
                    new ArrayList<AllergyReminder>(allergyReminderList));
            this.allergyEmptyState = allergyEmptyState;
            this.nutritionSupplementList = Collections.unmodifiableList(
                    new ArrayList<NutritionSupplement>(nutritionSupplementList));
            this.nutritionEmptyState = nutritionEmptyState;
            this.dietAttentionList = Collections.unmodifiableList(
                    new ArrayList<DietAttention>(dietAttentionList));
            this.dietAttentionEmptyState = dietAttentionEmptyState;
            this.entryList = Collections.unmodifiableList(new ArrayList<Entry>(entryList));
            this.disclaimer = disclaimer;
        }
    }

    /**
     * 过敏提醒卡片。{@code allergenName} 为 null 表示仅展示来源原文（OTHER 或抑制路径）；
     * {@code foodBorne} 为 null 表示食入性未知（OTHER，Java 不猜）。
     */
    @ApiModel(value = "AllergyReminder", description = "过敏提醒卡片；每个过敏原一张")
    @Getter
    public static final class AllergyReminder {

        @ApiModelProperty(value = "过敏原正式枚举名；枚举外为 OTHER", required = true,
                example = "SHRIMP_CRAB")
        private final String allergenKey;

        @ApiModelProperty(value = "过敏原展示名；null 表示仅展示来源原文（OTHER）", example = "虾蟹类")
        private final String allergenName;

        @ApiModelProperty(value = "是否食入性过敏原；null 表示未知（OTHER，系统不猜测）", example = "true")
        private final Boolean foodBorne;

        @ApiModelProperty(value = "需避免的食材清单；非食入性或 OTHER 时为空", required = true)
        private final List<String> avoidFoodList;

        @ApiModelProperty(value = "易忽略的含该过敏原的常见食物；非食入性或 OTHER 时为空", required = true)
        private final List<String> hiddenFoodList;

        @ApiModelProperty(value = "来源标注", required = true)
        private final Source source;

        @JsonCreator
        AllergyReminder(@JsonProperty("allergenKey") String allergenKey,
                        @JsonProperty("allergenName") String allergenName,
                        @JsonProperty("foodBorne") Boolean foodBorne,
                        @JsonProperty("avoidFoodList") List<String> avoidFoodList,
                        @JsonProperty("hiddenFoodList") List<String> hiddenFoodList,
                        @JsonProperty("source") Source source) {
            this.allergenKey = allergenKey;
            this.allergenName = allergenName;
            this.foodBorne = foodBorne;
            this.avoidFoodList = Collections.unmodifiableList(
                    new ArrayList<String>(avoidFoodList));
            this.hiddenFoodList = Collections.unmodifiableList(
                    new ArrayList<String>(hiddenFoodList));
            this.source = source;
        }
    }

    /** 营养补充卡片。{@code nutritionName} 为 null 表示仅展示来源原文。 */
    @ApiModel(value = "NutritionSupplement", description = "营养补充卡片；每个营养素一张")
    @Getter
    public static final class NutritionSupplement {

        @ApiModelProperty(value = "营养素正式枚举名；枚举外为 OTHER", required = true, example = "IRON")
        private final String nutritionKey;

        @ApiModelProperty(value = "营养素展示名；null 表示仅展示来源原文（OTHER 或抑制路径）", example = "铁")
        private final String nutritionName;

        @ApiModelProperty(value = "推荐食材清单；已做过敏原红线差集，抑制路径下为空", required = true)
        private final List<String> recommendFoodList;

        @ApiModelProperty(value = "食材推荐摄入量说明", required = true)
        private final List<String> intakeNoteList;

        @ApiModelProperty(value = "饮食搭配小贴士", required = true)
        private final List<String> pairingTipList;

        @ApiModelProperty(value = "来源标注", required = true)
        private final Source source;

        @JsonCreator
        NutritionSupplement(@JsonProperty("nutritionKey") String nutritionKey,
                            @JsonProperty("nutritionName") String nutritionName,
                            @JsonProperty("recommendFoodList") List<String> recommendFoodList,
                            @JsonProperty("intakeNoteList") List<String> intakeNoteList,
                            @JsonProperty("pairingTipList") List<String> pairingTipList,
                            @JsonProperty("source") Source source) {
            this.nutritionKey = nutritionKey;
            this.nutritionName = nutritionName;
            this.recommendFoodList = Collections.unmodifiableList(
                    new ArrayList<String>(recommendFoodList));
            this.intakeNoteList = Collections.unmodifiableList(
                    new ArrayList<String>(intakeNoteList));
            this.pairingTipList = Collections.unmodifiableList(
                    new ArrayList<String>(pairingTipList));
            this.source = source;
        }
    }

    /** 饮食注意卡片。{@code requirementName} 为 null 表示仅展示来源原文。 */
    @ApiModel(value = "DietAttention", description = "饮食注意卡片；每个饮食要求一张")
    @Getter
    public static final class DietAttention {

        @ApiModelProperty(value = "饮食要求正式枚举名；枚举外为 OTHER", required = true, example = "LOW_FAT")
        private final String requirementKey;

        @ApiModelProperty(value = "饮食要求展示名；null 表示仅展示来源原文（OTHER 或抑制路径）",
                example = "低脂饮食")
        private final String requirementName;

        @ApiModelProperty(value = "推荐食材清单；已做过敏原红线差集，抑制路径下为空", required = true)
        private final List<String> recommendFoodList;

        @ApiModelProperty(value = "需避免/限制的食材清单；抑制路径下为空", required = true)
        private final List<String> avoidFoodList;

        @ApiModelProperty(value = "烹饪方式建议", required = true)
        private final List<String> cookingTipList;

        @ApiModelProperty(value = "来源标注", required = true)
        private final Source source;

        @JsonCreator
        DietAttention(@JsonProperty("requirementKey") String requirementKey,
                      @JsonProperty("requirementName") String requirementName,
                      @JsonProperty("recommendFoodList") List<String> recommendFoodList,
                      @JsonProperty("avoidFoodList") List<String> avoidFoodList,
                      @JsonProperty("cookingTipList") List<String> cookingTipList,
                      @JsonProperty("source") Source source) {
            this.requirementKey = requirementKey;
            this.requirementName = requirementName;
            this.recommendFoodList = Collections.unmodifiableList(
                    new ArrayList<String>(recommendFoodList));
            this.avoidFoodList = Collections.unmodifiableList(
                    new ArrayList<String>(avoidFoodList));
            this.cookingTipList = Collections.unmodifiableList(
                    new ArrayList<String>(cookingTipList));
            this.source = source;
        }
    }

    /** 来源标注：section / itemNo / quote 来自契约字段，displayText 由 Java 拼好供前端直接渲染。 */
    @ApiModel(value = "DietAdviceSource", description = "饮食建议来源标注；引用报告原文")
    @Getter
    public static final class Source {

        @ApiModelProperty(value = "章节原文名；报告未标注章节时为 null", example = "总检结论")
        private final String section;

        @ApiModelProperty(value = "报告原文印刷的条目号；未印时为 null", example = "3")
        private final Integer itemNo;

        @ApiModelProperty(value = "建议那一句报告原文，1~100 字", required = true,
                example = "建议低脂饮食")
        private final String quote;

        @ApiModelProperty(value = "拼好的来源展示串，前端可直接渲染", required = true,
                example = "来源：总检结论–建议低脂饮食")
        private final String displayText;

        @JsonCreator
        Source(@JsonProperty("section") String section,
               @JsonProperty("itemNo") Integer itemNo,
               @JsonProperty("quote") String quote,
               @JsonProperty("displayText") String displayText) {
            this.section = section;
            this.itemNo = itemNo;
            this.quote = quote;
            this.displayText = displayText;
        }
    }

    /**
     * 一条已校验建议的来源与抑制状态；前端当前不渲染，保留用于排障
     * （设计方案 §7.6：section / itemNo / quote 不要因为当前不展示就删掉）。
     */
    @ApiModel(value = "DietAdviceEntry", description = "一条已校验建议的来源与抑制状态；排障用，前端不渲染")
    @Getter
    public static final class Entry {

        @ApiModelProperty(value = "建议维度", required = true,
                allowableValues = "ALLERGEN,NUTRITION,DIET", example = "DIET")
        private final String dimension;

        @ApiModelProperty(value = "正式枚举名；枚举外为 OTHER", required = true, example = "LOW_FAT")
        private final String enumKey;

        @ApiModelProperty(value = "菜品打标方向；条目来自哪个数组", required = true,
                allowableValues = "RECOMMEND,REJECT", example = "REJECT")
        private final String direction;

        @ApiModelProperty(value = "章节原文名；报告未标注章节时为 null", example = "总检结论")
        private final String section;

        @ApiModelProperty(value = "报告原文印刷的条目号；未印时为 null", example = "3")
        private final Integer itemNo;

        @ApiModelProperty(value = "建议那一句报告原文，1~100 字", required = true, example = "建议低脂饮食")
        private final String quote;

        @ApiModelProperty(value = "承载该建议的整条报告原文，1~500 字", required = true,
                example = "血脂偏高，建议低脂饮食，定期复查")
        private final String rawText;

        @ApiModelProperty(value = "为 true 表示该条按 OTHER 路径抑制，不产生食材（OTHER/安全闸/未过审/非食入性）",
                required = true, example = "false")
        private final boolean structuredOutputSuppressed;

        @JsonCreator
        Entry(@JsonProperty("dimension") String dimension,
              @JsonProperty("enumKey") String enumKey,
              @JsonProperty("direction") String direction,
              @JsonProperty("section") String section,
              @JsonProperty("itemNo") Integer itemNo,
              @JsonProperty("quote") String quote,
              @JsonProperty("rawText") String rawText,
              @JsonProperty("structuredOutputSuppressed") boolean structuredOutputSuppressed) {
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
