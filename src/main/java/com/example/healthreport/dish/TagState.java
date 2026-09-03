package com.example.healthreport.dish;

/** 凌晨打标链路的菜品维度状态；在线只消费发布后的方向集合，不接触本枚举。 */
public enum TagState {

    /** 已打标，但现有菜品数据不足以判断。 */
    UNKNOWN,

    /** 已核验并确认不含或不违反。 */
    NEUTRAL,

    /** 仅营养维度由 Java 确定性交集产生的推荐。 */
    RECOMMEND,

    /** 明确含有过敏原或违反饮食要求。 */
    REJECT
}
