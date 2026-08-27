package com.example.healthreport.constants;

import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * 一个营养补充维度的内容。
 * <p><b>只有 {@code recommendableFoodList} 参与自动推荐</b>（与菜品主料取交集，
 * 匹配方式 CANONICAL_EXACT）。{@code displayOnlyFoodList} 只出现在模块三，
 * 绝不进入交集计算，也绝不注入模型提示词。</p>
 * <p>拆两个字段是为了让医务能把某个食材写进科普清单、同时拒绝让它触发自动推荐
 * ——否则审一句文案等于批一条推荐规则。</p>
 */
@Getter
public final class NutritionRule {

    private final NutritionKey key;
    /** 可触发自动推荐的食材。必须写别名表右侧的标准名。 */
    private final List<String> recommendableFoodList;
    /** 仅展示的食材，不触发推荐。 */
    private final List<String> displayOnlyFoodList;
    private final List<String> intakeNoteList;
    private final List<String> pairingTipList;
    /** 不适用或慎用人群。 */
    private final String contraindication;
    private final ReviewStatus reviewStatus;

    NutritionRule(NutritionKey key, List<String> recommendableFoodList, List<String> displayOnlyFoodList,
                  List<String> intakeNoteList, List<String> pairingTipList,
                  String contraindication, ReviewStatus reviewStatus) {
        this.key = key;
        this.recommendableFoodList = Collections.unmodifiableList(recommendableFoodList);
        this.displayOnlyFoodList = Collections.unmodifiableList(displayOnlyFoodList);
        this.intakeNoteList = Collections.unmodifiableList(intakeNoteList);
        this.pairingTipList = Collections.unmodifiableList(pairingTipList);
        this.contraindication = contraindication;
        this.reviewStatus = reviewStatus;
    }
}
