package com.example.healthreport.dish;

import com.example.healthreport.constants.IngredientAliasWords;
import com.example.healthreport.constants.NutritionContents;
import com.example.healthreport.constants.NutritionKey;
import com.example.healthreport.constants.NutritionRule;
import com.example.healthreport.constants.ReviewStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** 营养维度的纯 Java 主料交集匹配，永远只产生 NEUTRAL 或 RECOMMEND。 */
@Service
public class NutritionMatcher {

    private final MainIngredientResolver mainIngredientResolver;

    public NutritionMatcher(MainIngredientResolver mainIngredientResolver) {
        this.mainIngredientResolver = mainIngredientResolver;
    }

    /** 只使用审核通过的营养内容常量匹配。 */
    public TagValue match(Dish dish, NutritionKey nutritionKey) {
        NutritionRule rule = NutritionContents.ALL.get(nutritionKey);
        if (rule == null || rule.getReviewStatus() != ReviewStatus.REVIEWED) {
            return TagValue.of(TagState.NEUTRAL);
        }
        return match(dish, new LinkedHashSet<String>(rule.getRecommendableFoodList()));
    }

    /** 对显式推荐食材集合做确定性交集，供规则单测与上层复用。 */
    public TagValue match(Dish dish, Set<String> recommendIngredientSet) {
        if (dish == null || recommendIngredientSet == null) {
            throw new IllegalArgumentException("营养匹配输入不能为空");
        }
        Set<String> canonicalRecommendSet = new LinkedHashSet<String>(recommendIngredientSet.size());
        for (String ingredient : recommendIngredientSet) {
            canonicalRecommendSet.add(IngredientAliasWords.canonical(ingredient));
        }
        Set<String> matchedSet = new LinkedHashSet<String>();
        for (String mainIngredient : mainIngredientResolver.resolve(dish)) {
            String canonical = IngredientAliasWords.canonical(mainIngredient);
            if (canonicalRecommendSet.contains(canonical)) {
                matchedSet.add(canonical);
            }
        }
        if (matchedSet.isEmpty()) {
            return TagValue.of(TagState.NEUTRAL);
        }
        return new TagValue(TagState.RECOMMEND,
                Collections.unmodifiableList(new ArrayList<String>(matchedSet)));
    }
}
