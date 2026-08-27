package com.example.healthreport.llm.extraction;

/** 总检结论的模型分类；Java 只按枚举做集合运算。 */
public enum SummaryCategory {

    /** 健康问题。 */
    HEALTH_PROBLEM,

    /** 饮食建议。 */
    DIET_ADVICE,

    /** 生活方式建议。 */
    LIFESTYLE,

    /** 常规提醒。 */
    ROUTINE,

    /** 正常陈述。 */
    NORMAL_STATEMENT
}
