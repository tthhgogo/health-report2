package com.example.healthreport.constants;

/**
 * 饮食注意维度产出 RECOMMEND 的确定性政策。
 * <p>正向结论一律由 Java 按本政策产出，LLM-B 只判 REJECT / UNKNOWN / NEUTRAL
 * ——正面标签是对用户的健康承诺，不交给模型生成。</p>
 * <p>新政策只有在菜品源真正提供了对应数据后才允许新增（例如完整配方、烹饪方式、
 * 油盐糖酒用量或营养成分表），不得为了「让推荐列表非空」提前放开。</p>
 */
public enum PositiveMatchPolicy {

    /** 不产出推荐。该维度取决于调味料与用油量，菜品接口给不了这些数据 */
    NONE,

    /** 菜品主料与已审核 recommendableFoodList 取交集，命中即推荐，命中食材本身就是证据 */
    MAIN_INGREDIENT_INTERSECTION;
}
