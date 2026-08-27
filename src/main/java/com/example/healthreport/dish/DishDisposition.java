package com.example.healthreport.dish;

/** 菜品合并裁决结果。 */
public enum DishDisposition {

    /** 进入推荐列表。 */
    RECOMMENDED,

    /** 进入不推荐列表。 */
    NOT_RECOMMENDED,

    /** 标签不完整或模型未知，不进入任何列表。 */
    HIDDEN,

    /** 已核验但没有正负命中，不进入任何列表。 */
    NEUTRAL
}
