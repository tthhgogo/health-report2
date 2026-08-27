package com.example.healthreport.llm.extraction;

/** LLM-A 返回的检查指标状态；Java 只保存，不重新判定。 */
public enum IndicatorStatus {

    /** 报告或模型判定为正常。 */
    NORMAL,

    /** 报告明确为偏高。 */
    HIGH,

    /** 报告明确为偏低。 */
    LOW,

    /** 非高低方向的异常结论。 */
    ABNORMAL
}
