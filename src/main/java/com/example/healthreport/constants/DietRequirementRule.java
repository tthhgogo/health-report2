package com.example.healthreport.constants;

import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * 一个饮食注意维度的内容。
 * <p><b>LLM-B 在本维度只判 REJECT / UNKNOWN / NEUTRAL，永远不产出 RECOMMEND。</b>
 * 仅凭食材证明不了一道菜真的低脂、低盐或低能量——用油量与做法才是决定因素，
 * 而菜品接口给不了这些数据，因此 {@code displayOnlyFoodList} 只用于模块三展示。</p>
 * <p>推荐由 Java 侧按 {@link PositiveMatchPolicy} 做确定性匹配产出，
 * 只有 {@code recommendableFoodList} 参与交集，且必须单独通过医学审核
 * ——审一句科普文案不等于批一条推荐规则，这与 {@link NutritionRule} 的两张表同一个道理。
 * 第一期只有 {@code LOW_PURINE} 与 {@code HIGH_FIBER} 开放，其余维度政策为
 * {@link PositiveMatchPolicy#NONE}。</p>
 * <p>字段按语义拆开：食材、菜式、烹饪方式、用餐行为不是一回事，
 * 混在一个数组里会让程序和模型把「蒸蛋」当成一种食材。</p>
 */
@Getter
public final class DietRequirementRule {

    private final DietRequirementKey key;
    /** 卡片展示名，如「低脂饮食」。 */
    private final String displayName;
    /** 正向食材，仅展示，不产生 RECOMMEND。 */
    private final List<String> displayOnlyFoodList;
    /** 需避免的食材，产生 REJECT。 */
    private final List<String> avoidFoodList;
    /** 需避免的菜式，产生 REJECT。 */
    private final List<String> avoidDishPatternList;
    private final List<String> cookingTipList;
    private final List<String> behaviorTipList;
    private final String contraindication;
    private final ReviewStatus reviewStatus;
    /** Java 侧产出推荐的确定性政策；NONE 表示本维度不产出任何推荐。 */
    private final PositiveMatchPolicy positiveMatchPolicy;
    /** 可触发自动推荐的食材，与菜品主料取交集。必须写别名表右侧的标准名。 */
    private final List<String> recommendableFoodList;
    /** 命中推荐时展示的固定正面标签文字；政策为 NONE 时必须为空串。 */
    private final String recommendTagText;
    /** 正向规则的独立审核状态：负向已审核不等于正面健康承诺已审核。 */
    private final ReviewStatus positiveReviewStatus;

    DietRequirementRule(DietRequirementKey key, String displayName, List<String> displayOnlyFoodList,
                        List<String> avoidFoodList, List<String> avoidDishPatternList,
                        List<String> cookingTipList, List<String> behaviorTipList,
                        String contraindication, ReviewStatus reviewStatus,
                        PositiveMatchPolicy positiveMatchPolicy,
                        List<String> recommendableFoodList, String recommendTagText,
                        ReviewStatus positiveReviewStatus) {
        if (positiveMatchPolicy == PositiveMatchPolicy.NONE
                && (!recommendableFoodList.isEmpty() || recommendTagText.length() > 0)) {
            // 政策与内容对不上时直接拒绝装载：留着半套正向内容，下次改政策就会悄悄放开推荐。
            throw new IllegalArgumentException("不产出推荐的维度不得携带正向内容：" + key);
        }
        if (positiveMatchPolicy != PositiveMatchPolicy.NONE
                && (recommendableFoodList.isEmpty() || recommendTagText.length() == 0)) {
            throw new IllegalArgumentException("开放推荐的维度必须有推荐食材和标签文案：" + key);
        }
        this.key = key;
        this.displayName = displayName;
        this.displayOnlyFoodList = Collections.unmodifiableList(displayOnlyFoodList);
        this.avoidFoodList = Collections.unmodifiableList(avoidFoodList);
        this.avoidDishPatternList = Collections.unmodifiableList(avoidDishPatternList);
        this.cookingTipList = Collections.unmodifiableList(cookingTipList);
        this.behaviorTipList = Collections.unmodifiableList(behaviorTipList);
        this.contraindication = contraindication;
        this.reviewStatus = reviewStatus;
        this.positiveMatchPolicy = positiveMatchPolicy;
        this.recommendableFoodList = Collections.unmodifiableList(recommendableFoodList);
        this.recommendTagText = recommendTagText;
        this.positiveReviewStatus = positiveReviewStatus;
    }

    /**
     * 本维度当前是否允许产出推荐。
     * <p>政策非 NONE 且正向内容已审核通过才生效；负向 {@code reviewStatus} 不能替代它。
     * 全链路只在这里判断一次，避免推荐开关散落到各个分支里。</p>
     */
    public boolean positiveRecommendEnabled() {
        return positiveMatchPolicy != PositiveMatchPolicy.NONE
                && positiveReviewStatus == ReviewStatus.REVIEWED;
    }
}
