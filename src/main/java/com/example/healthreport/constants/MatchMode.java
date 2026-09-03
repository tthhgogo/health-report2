package com.example.healthreport.constants;

/**
 * 过敏词条的匹配方式。
 * <p>营养维度的主料交集不经过本枚举——它直接对规范化标准名做整串相等（NutritionMatcher），
 * 原 CANONICAL_EXACT / NONE 两个值没有任何词条实例，已删除。</p>
 */
public enum MatchMode {

    /** 规范化后子串包含。用于过敏 Layer 1，宁可过杀 */
    SUBSTRING,

    /** 不做任何 Java 匹配，只作为线索进模型提示词 */
    MODEL_ONLY;
}
