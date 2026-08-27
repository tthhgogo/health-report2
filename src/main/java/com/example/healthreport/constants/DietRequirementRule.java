package com.example.healthreport.constants;

import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * 一个饮食注意维度的内容。
 * <p><b>本维度当前不产生任何推荐。</b>仅凭食材证明不了一道菜真的低脂、低盐或低能量
 * ——用油量与做法才是决定因素，而菜品接口给不了这些数据。
 * 因此 {@code displayOnlyFoodList} 只用于模块三展示，LLM-B 只判 REJECT。</p>
 * <p>字段按语义拆开：食材、菜式、烹饪方式、用餐行为不是一回事，
 * 混在一个数组里会让程序和模型把「蒸蛋」当成一种食材。</p>
 */
@Getter
public final class DietRequirementRule {

    private final DietRequirementKey key;
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

    DietRequirementRule(DietRequirementKey key, List<String> displayOnlyFoodList,
                        List<String> avoidFoodList, List<String> avoidDishPatternList,
                        List<String> cookingTipList, List<String> behaviorTipList,
                        String contraindication, ReviewStatus reviewStatus) {
        this.key = key;
        this.displayOnlyFoodList = Collections.unmodifiableList(displayOnlyFoodList);
        this.avoidFoodList = Collections.unmodifiableList(avoidFoodList);
        this.avoidDishPatternList = Collections.unmodifiableList(avoidDishPatternList);
        this.cookingTipList = Collections.unmodifiableList(cookingTipList);
        this.behaviorTipList = Collections.unmodifiableList(behaviorTipList);
        this.contraindication = contraindication;
        this.reviewStatus = reviewStatus;
    }
}
