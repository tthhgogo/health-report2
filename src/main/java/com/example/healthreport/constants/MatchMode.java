package com.example.healthreport.constants;

/**
 * 匹配方式。
 */
public enum MatchMode {

    /** 规范化后子串包含。用于过敏 Layer 1，宁可过杀 */
    SUBSTRING,

    /** 规范化后整串相等。用于营养维度的主料交集 */
    CANONICAL_EXACT,

    /** 不做任何 Java 匹配，只作为线索进模型提示词 */
    MODEL_ONLY,

    /** 纯文案，不参与任何匹配 */
    NONE;
}
