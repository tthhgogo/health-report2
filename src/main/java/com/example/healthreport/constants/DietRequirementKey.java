package com.example.healthreport.constants;

/**
 * 饮食注意枚举。本维度由 LLM-B 离线打标，当前只产生 REJECT。
 */
public enum DietRequirementKey {

    /** 低脂 */
    LOW_FAT,

    /** 低盐 */
    LOW_SODIUM,

    /** 限制添加糖 */
    LOW_ADDED_SUGAR,

    /** 低嘌呤 */
    LOW_PURINE,

    /** 低胆固醇 */
    LOW_CHOLESTEROL,

    /** 控制体重 */
    LOW_CALORIE,

    /** 高纤维 */
    HIGH_FIBER,

    /** 限酒 */
    LIMIT_ALCOHOL,

    /** 清淡饮食 */
    LIGHT_DIET,

    /** 枚举外的饮食要求，只展示原文 */
    OTHER;
}
