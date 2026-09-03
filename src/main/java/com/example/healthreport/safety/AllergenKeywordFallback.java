package com.example.healthreport.safety;

import com.example.healthreport.constants.AllergenExceptions;
import com.example.healthreport.constants.AllergenGroup;
import com.example.healthreport.constants.AllergenWord;
import com.example.healthreport.constants.ReviewStatus;
import com.example.healthreport.constants.SourceField;
import com.example.healthreport.dish.Dish;
import com.example.healthreport.dish.DishIngredient;
import org.springframework.stereotype.Service;

/**
 * 过敏关键词硬兜底；匹配菜名和全部食材名，无重量阈值，结果只会增加 REJECT。
 */
@Service
public class AllergenKeywordFallback {

    /**
     * 判断正式食入性过敏组是否硬命中。
     * <p>食材表没有调味料，因此“未命中”绝不推出 NEUTRAL；它只表示本兜底没有新增拒绝。</p>
     */
    public boolean matches(AllergenGroup group, Dish dish) {
        if (group == null || dish == null || !group.isFoodBorne()) {
            return false;
        }
        for (AllergenWord word : group.getWordList()) {
            if (!word.isHardMatchable()) {
                continue;
            }
            if (dish.getDishName().contains(word.getMatchWord())
                    && !dishNameExcepted(group, word, dish.getDishName())) {
                return true;
            }
            for (DishIngredient ingredient : dish.getIngredientList()) {
                if (ingredient.getName().contains(word.getMatchWord())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean dishNameExcepted(AllergenGroup group, AllergenWord word, String dishName) {
        for (AllergenExceptions.Rule rule : AllergenExceptions.ALL) {
            if (rule.getReviewStatus() == ReviewStatus.REVIEWED
                    && rule.getSourceField() == SourceField.DISH_NAME
                    && rule.getAllergenKey() == group.getKey()
                    && rule.getMatchWord().equals(word.getMatchWord())
                    && dishName.contains(rule.getExceptionPhrase())) {
                return true;
            }
        }
        return false;
    }
}
