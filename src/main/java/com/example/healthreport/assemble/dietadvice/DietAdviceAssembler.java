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
import com.example.healthreport.safety.StructuredAdmission;
import lombok.Getter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 模块三饮食建议组装器。
 * <p>公开入口只接收不含指标数据的 {@link DietAdviceInput}。高危或 OTHER 条目保留原文与来源，
 * 但不会生成结构化内容；未审核常量同样不会进入输出。</p>
 */
@Service
public class DietAdviceAssembler {

    /** 多段报告原文保持段边界时使用的展示换行符，不改变段内原文。 */
    private static final String RAW_TEXT_SEPARATOR = "\n";

    private final StructuredAdmission structuredAdmission;

    public DietAdviceAssembler(StructuredAdmission structuredAdmission) {
        this.structuredAdmission = structuredAdmission;
    }

    /**
     * 组装三个始终存在的饮食建议分区。
     *
     * @param input 仅含建议枚举、报告原文与机械来源字段的输入
     * @return 不含额外提示或警示文字的模块三结果
     */
    public Result assemble(DietAdviceInput input) {
        if (input == null) {
            throw new IllegalArgumentException("模块三输入不能为空");
        }
        List<AllergenCard> allergenCardList = new ArrayList<AllergenCard>(
                input.getAllergenList().size());
        for (DietAdviceInput.AllergenItem item : input.getAllergenList()) {
            allergenCardList.add(allergen(item));
        }

        List<NutritionCard> nutritionCardList = new ArrayList<NutritionCard>(
                input.getNutritionList().size());
        for (DietAdviceInput.AdviceItem<NutritionKey> item : input.getNutritionList()) {
            nutritionCardList.add(nutrition(item));
        }

        List<DietCard> dietCardList = new ArrayList<DietCard>(input.getDietList().size());
        for (DietAdviceInput.AdviceItem<DietRequirementKey> item : input.getDietList()) {
            dietCardList.add(diet(item));
        }

        return new Result(
                new Section<AllergenCard>(allergenCardList,
                        allergenCardList.isEmpty() ? EmptyStateConstants.MODULE_THREE_ALLERGEN : null),
                new Section<NutritionCard>(nutritionCardList,
                        nutritionCardList.isEmpty() ? EmptyStateConstants.MODULE_THREE_NUTRITION : null),
                new Section<DietCard>(dietCardList,
                        dietCardList.isEmpty() ? EmptyStateConstants.MODULE_THREE_DIET : null),
                DisclaimerConstants.MODULE_THREE);
    }

    private AllergenCard allergen(DietAdviceInput.AllergenItem item) {
        DietAdviceInput.StructuredValue<AllergenKey> value = item.getStructuredValue();
        // 【高危表述安全闸不作用于过敏原】该闸只扫营养补充与饮食注意的原文。
        // 过敏忌口本身就是要展示给用户的安全信息，用无关高危词（如「儿童」）连带抑制它，
        // 反而会把该看见的忌口清单收掉；同时 adviceOtherCount 的口径是「建议条数」，
        // 把过敏原计进去会让口径失真。这里只保留 OTHER 枚举本身走 OTHER 路径。
        boolean otherPath = value.getEnumKey() == AllergenKey.OTHER;

        List<String> avoidIngredientList = new ArrayList<String>();
        List<String> hiddenFoodList = new ArrayList<String>();
        if (!otherPath) {
            AllergenGroup group = AllergenGroups.ALL.get(value.getEnumKey());
            if (group != null) {
                for (AllergenWord word : group.getWordList()) {
                    // 未经医务审核的词条不得进入展示或后续匹配输入。
                    if (word.getReviewStatus() == ReviewStatus.REVIEWED) {
                        if (word.getBucket() == Bucket.AVOID) {
                            avoidIngredientList.add(word.getDisplayName());
                        } else {
                            hiddenFoodList.add(word.getDisplayName());
                        }
                    }
                }
            }
        }
        boolean structuredContentAvailable = !avoidIngredientList.isEmpty()
                || !hiddenFoodList.isEmpty();
        return new AllergenCard(value.getEnumKey(), item.isFoodBorne(), item.getRawName(),
                item.getRawResult(), item.getSource().getSourceLabel(), value.getRawTextList(),
                // 过敏原不再经过高危闸，structuredOutputSuppressed 恒为 false。
                joinRawText(value.getRawTextList()), false, structuredContentAvailable,
                avoidIngredientList, hiddenFoodList);
    }

    private NutritionCard nutrition(DietAdviceInput.AdviceItem<NutritionKey> item) {
        DietAdviceInput.StructuredValue<NutritionKey> value = item.getStructuredValue();
        boolean suppressed = structuredAdmission.shouldSuppress(value.getApplicability(),
                value.getStructuredSafety(), value.getAdviceQuote(), value.getRawTextList());
        boolean otherPath = suppressed || value.getEnumKey() == NutritionKey.OTHER;

        NutritionRule rule = otherPath ? null : NutritionContents.ALL.get(value.getEnumKey());
        boolean reviewed = rule != null && rule.getReviewStatus() == ReviewStatus.REVIEWED;
        return new NutritionCard(value.getEnumKey(), item.getSource().getSourceLabel(),
                value.getRawTextList(), joinRawText(value.getRawTextList()), suppressed, reviewed,
                reviewed ? rule.getRecommendableFoodList() : Collections.<String>emptyList(),
                reviewed ? rule.getDisplayOnlyFoodList() : Collections.<String>emptyList(),
                reviewed ? rule.getIntakeNoteList() : Collections.<String>emptyList(),
                reviewed ? rule.getPairingTipList() : Collections.<String>emptyList(),
                reviewed ? rule.getContraindication() : null);
    }

    private DietCard diet(DietAdviceInput.AdviceItem<DietRequirementKey> item) {
        DietAdviceInput.StructuredValue<DietRequirementKey> value = item.getStructuredValue();
        boolean suppressed = structuredAdmission.shouldSuppress(value.getApplicability(),
                value.getStructuredSafety(), value.getAdviceQuote(), value.getRawTextList());
        boolean otherPath = suppressed || value.getEnumKey() == DietRequirementKey.OTHER;

        DietRequirementRule rule = otherPath ? null
                : DietRequirementContents.ALL.get(value.getEnumKey());
        boolean reviewed = rule != null && rule.getReviewStatus() == ReviewStatus.REVIEWED;
        return new DietCard(value.getEnumKey(), item.getSource().getSourceLabel(),
                value.getRawTextList(), joinRawText(value.getRawTextList()), suppressed, reviewed,
                reviewed ? rule.getDisplayOnlyFoodList() : Collections.<String>emptyList(),
                reviewed ? rule.getAvoidFoodList() : Collections.<String>emptyList(),
                reviewed ? rule.getAvoidDishPatternList() : Collections.<String>emptyList(),
                reviewed ? rule.getCookingTipList() : Collections.<String>emptyList(),
                reviewed ? rule.getBehaviorTipList() : Collections.<String>emptyList(),
                reviewed ? rule.getContraindication() : null);
    }

    private String joinRawText(List<String> rawTextList) {
        StringBuilder builder = new StringBuilder();
        for (String rawText : rawTextList) {
            if (builder.length() > 0) {
                builder.append(RAW_TEXT_SEPARATOR);
            }
            builder.append(rawText);
        }
        return builder.toString();
    }

    private static List<String> immutableCopy(List<String> sourceList) {
        return Collections.unmodifiableList(new ArrayList<String>(sourceList));
    }

    /** 模块三完整返回结构，三个分区始终存在。 */
    @Getter
    public static final class Result {
        private final Section<AllergenCard> allergenSection;
        private final Section<NutritionCard> nutritionSection;
        private final Section<DietCard> dietSection;
        private final String disclaimer;

        private Result(Section<AllergenCard> allergenSection,
                       Section<NutritionCard> nutritionSection,
                       Section<DietCard> dietSection, String disclaimer) {
            this.allergenSection = allergenSection;
            this.nutritionSection = nutritionSection;
            this.dietSection = dietSection;
            this.disclaimer = disclaimer;
        }
    }

    /** 一个始终存在、按唯一排序计划排列的模块三分区。 */
    @Getter
    public static final class Section<T> {
        private final List<T> cardList;
        private final String emptyState;

        private Section(List<T> cardList, String emptyState) {
            this.cardList = Collections.unmodifiableList(new ArrayList<T>(cardList));
            this.emptyState = emptyState;
        }
    }

    /** 过敏提醒卡片；结构化内容不可用时仍保留报告原文和来源。 */
    @Getter
    public static final class AllergenCard {
        private final AllergenKey enumKey;
        private final boolean foodBorne;
        private final String rawName;
        private final String rawResult;
        private final String sourceLabel;
        private final List<String> rawTextList;
        private final String rawText;
        private final boolean structuredOutputSuppressed;
        private final boolean structuredContentAvailable;
        private final List<String> avoidIngredientList;
        private final List<String> hiddenFoodList;

        private AllergenCard(AllergenKey enumKey, boolean foodBorne, String rawName,
                             String rawResult, String sourceLabel, List<String> rawTextList,
                             String rawText, boolean structuredOutputSuppressed,
                             boolean structuredContentAvailable, List<String> avoidIngredientList,
                             List<String> hiddenFoodList) {
            this.enumKey = enumKey;
            this.foodBorne = foodBorne;
            this.rawName = rawName;
            this.rawResult = rawResult;
            this.sourceLabel = sourceLabel;
            this.rawTextList = immutableCopy(rawTextList);
            this.rawText = rawText;
            this.structuredOutputSuppressed = structuredOutputSuppressed;
            this.structuredContentAvailable = structuredContentAvailable;
            this.avoidIngredientList = immutableCopy(avoidIngredientList);
            this.hiddenFoodList = immutableCopy(hiddenFoodList);
        }
    }

    /** 营养补充卡片；仅审核通过的内容常量会进入各列表。 */
    @Getter
    public static final class NutritionCard {
        private final NutritionKey enumKey;
        private final String sourceLabel;
        private final List<String> rawTextList;
        private final String rawText;
        private final boolean structuredOutputSuppressed;
        private final boolean structuredContentAvailable;
        private final List<String> recommendableFoodList;
        private final List<String> displayOnlyFoodList;
        private final List<String> intakeNoteList;
        private final List<String> pairingTipList;
        private final String contraindication;

        private NutritionCard(NutritionKey enumKey, String sourceLabel, List<String> rawTextList,
                              String rawText, boolean structuredOutputSuppressed,
                              boolean structuredContentAvailable,
                              List<String> recommendableFoodList,
                              List<String> displayOnlyFoodList, List<String> intakeNoteList,
                              List<String> pairingTipList, String contraindication) {
            this.enumKey = enumKey;
            this.sourceLabel = sourceLabel;
            this.rawTextList = immutableCopy(rawTextList);
            this.rawText = rawText;
            this.structuredOutputSuppressed = structuredOutputSuppressed;
            this.structuredContentAvailable = structuredContentAvailable;
            this.recommendableFoodList = immutableCopy(recommendableFoodList);
            this.displayOnlyFoodList = immutableCopy(displayOnlyFoodList);
            this.intakeNoteList = immutableCopy(intakeNoteList);
            this.pairingTipList = immutableCopy(pairingTipList);
            this.contraindication = contraindication;
        }
    }

    /** 饮食注意卡片；仅审核通过的内容常量会进入各列表。 */
    @Getter
    public static final class DietCard {
        private final DietRequirementKey enumKey;
        private final String sourceLabel;
        private final List<String> rawTextList;
        private final String rawText;
        private final boolean structuredOutputSuppressed;
        private final boolean structuredContentAvailable;
        private final List<String> displayOnlyFoodList;
        private final List<String> avoidFoodList;
        private final List<String> avoidDishPatternList;
        private final List<String> cookingTipList;
        private final List<String> behaviorTipList;
        private final String contraindication;

        private DietCard(DietRequirementKey enumKey, String sourceLabel,
                         List<String> rawTextList, String rawText,
                         boolean structuredOutputSuppressed, boolean structuredContentAvailable,
                         List<String> displayOnlyFoodList, List<String> avoidFoodList,
                         List<String> avoidDishPatternList, List<String> cookingTipList,
                         List<String> behaviorTipList, String contraindication) {
            this.enumKey = enumKey;
            this.sourceLabel = sourceLabel;
            this.rawTextList = immutableCopy(rawTextList);
            this.rawText = rawText;
            this.structuredOutputSuppressed = structuredOutputSuppressed;
            this.structuredContentAvailable = structuredContentAvailable;
            this.displayOnlyFoodList = immutableCopy(displayOnlyFoodList);
            this.avoidFoodList = immutableCopy(avoidFoodList);
            this.avoidDishPatternList = immutableCopy(avoidDishPatternList);
            this.cookingTipList = immutableCopy(cookingTipList);
            this.behaviorTipList = immutableCopy(behaviorTipList);
            this.contraindication = contraindication;
        }
    }
}
