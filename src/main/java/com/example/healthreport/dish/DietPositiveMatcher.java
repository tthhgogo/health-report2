package com.example.healthreport.dish;

import com.example.healthreport.constants.DietRequirementContents;
import com.example.healthreport.constants.DietRequirementKey;
import com.example.healthreport.constants.DietRequirementRule;
import com.example.healthreport.constants.PositiveMatchPolicy;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;

/**
 * 饮食注意维度的纯 Java 确定性正向匹配，永远只产生 NEUTRAL 或 RECOMMEND。
 * <p>安全结论仍然只由 LLM-B 负责：本类<b>只在同一维度的离线安全态为 NEUTRAL 时才允许推荐</b>，
 * 模型判 REJECT、UNKNOWN 或标签缺失时一律返回 NEUTRAL。「模型没判出问题」和「可以给用户正面承诺」
 * 是两件事，后者必须有自己的确定性证据。</p>
 * <p>交集算法复用 {@link NutritionMatcher}：营养与饮食两条推荐链路必须用同一套主料口径，
 * 各写一份迟早会分叉。</p>
 */
@Service
public class DietPositiveMatcher {

    private final NutritionMatcher nutritionMatcher;

    public DietPositiveMatcher(NutritionMatcher nutritionMatcher) {
        this.nutritionMatcher = nutritionMatcher;
    }

    /**
     * 按维度的确定性政策产出正向标签值。
     *
     * @param dish 当日在架菜品
     * @param key 本次报告生效的饮食注意维度
     * @param safetyState 同一维度的离线安全态，只有 NEUTRAL 才允许继续
     * @return RECOMMEND 时携带命中的标准名主料，其余一律 NEUTRAL
     */
    public TagValue match(Dish dish, DietRequirementKey key, TagState safetyState) {
        if (dish == null || key == null || safetyState == null) {
            throw new IllegalArgumentException("饮食正向匹配输入不能为空");
        }
        if (safetyState != TagState.NEUTRAL) {
            // REJECT 要拦、UNKNOWN 与标签缺失要隐藏，都轮不到推荐；这里不产出，裁决层也不会误取。
            return TagValue.of(TagState.NEUTRAL);
        }
        DietRequirementRule rule = DietRequirementContents.ALL.get(key);
        if (rule == null || !rule.positiveRecommendEnabled()) {
            return TagValue.of(TagState.NEUTRAL);
        }
        if (rule.getPositiveMatchPolicy() != PositiveMatchPolicy.MAIN_INGREDIENT_INTERSECTION) {
            // 将来新增政策必须先在这里落地：没落地的政策默认不推荐，不允许悄悄走到别的分支。
            return TagValue.of(TagState.NEUTRAL);
        }
        return nutritionMatcher.match(dish,
                new LinkedHashSet<String>(rule.getRecommendableFoodList()));
    }
}
