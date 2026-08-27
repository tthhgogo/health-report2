package com.example.healthreport.dish;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 食堂只读菜品快照。
 * <p>只包含离线打标与模块四所需的公开菜品字段，不含用户或健康数据。</p>
 */
@Getter
public final class Dish {

    private final long dishId;
    private final String dishName;
    private final List<DishIngredient> ingredientList;

    /** 创建不可变的当日在架菜品快照。 */
    public Dish(long dishId, String dishName, List<DishIngredient> ingredientList) {
        if (dishId <= 0L || dishName == null || ingredientList == null) {
            throw new IllegalArgumentException("菜品字段不合法");
        }
        // 列表非空不等于元素非空：原来只拦住了前者，null 元素会一路漏到
        // MainIngredientResolver、DishTagUserMessageRenderer 等每一个遍历它的地方，
        // 各自炸出一个没有上下文的 NPE。在这里拦掉，下游全部按构造即安全处理。
        List<DishIngredient> copyList = new ArrayList<DishIngredient>(ingredientList);
        if (copyList.contains(null)) {
            throw new IllegalArgumentException("菜品食材列表不接受 null 元素，dishId=" + dishId);
        }
        this.dishId = dishId;
        this.dishName = dishName;
        this.ingredientList = Collections.unmodifiableList(copyList);
    }
}
