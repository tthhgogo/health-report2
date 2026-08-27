package com.example.healthreport.dish;

/** 菜品维度五态；缺标签与模型未知必须保持可区分。 */
public enum TagState {

    /** 缓存和数据库都没有当前哈希对应的标签。 */
    TAG_MISSING,

    /** 已打标，但现有菜品数据不足以判断。 */
    UNKNOWN,

    /** 已核验并确认不含或不违反。 */
    NEUTRAL,

    /** 仅营养维度由 Java 确定性交集产生的推荐。 */
    RECOMMEND,

    /** 明确含有过敏原或违反饮食要求。 */
    REJECT
}
