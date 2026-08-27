package com.example.healthreport.llm.dishtag;

import com.example.healthreport.dish.Dish;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LLM-B 单维度批次输入。
 * <p>字段只来自公开菜品与已审核内容常量，不得加入用户、报告或健康数据。</p>
 */
@Getter
public final class DishTagInput {

    private final String enumKey;
    private final String enumDisplayName;
    private final List<String> avoidFoodList;
    private final List<String> hiddenFoodList;
    private final List<String> avoidDishPatternList;
    private final List<String> cookingTipList;
    private final List<Dish> dishList;

    /** 创建一个不可变的单维度批次。 */
    public DishTagInput(String enumKey, String enumDisplayName, List<String> avoidFoodList,
                        List<String> hiddenFoodList, List<String> avoidDishPatternList,
                        List<String> cookingTipList, List<Dish> dishList) {
        if (enumKey == null || enumDisplayName == null || avoidFoodList == null
                || hiddenFoodList == null || avoidDishPatternList == null
                || cookingTipList == null || dishList == null) {
            throw new IllegalArgumentException("LLM-B输入不能为空");
        }
        this.enumKey = enumKey;
        this.enumDisplayName = enumDisplayName;
        this.avoidFoodList = immutableCopy(avoidFoodList);
        this.hiddenFoodList = immutableCopy(hiddenFoodList);
        this.avoidDishPatternList = immutableCopy(avoidDishPatternList);
        this.cookingTipList = immutableCopy(cookingTipList);
        this.dishList = Collections.unmodifiableList(new ArrayList<Dish>(dishList));
    }

    private static List<String> immutableCopy(List<String> sourceList) {
        return Collections.unmodifiableList(new ArrayList<String>(sourceList));
    }
}
