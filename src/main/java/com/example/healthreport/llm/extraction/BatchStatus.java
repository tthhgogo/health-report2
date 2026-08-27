package com.example.healthreport.llm.extraction;

/** LLM-A 批次可读性与报告特征三态。 */
public enum BatchStatus {

    /** 批次可读且识别到体检报告内容。 */
    OK,

    /** 批次可读，但确定没有体检报告特征。 */
    NO_REPORT_FEATURE,

    /** 批次无法读取，不能据此判断是否有报告内容。 */
    UNREADABLE
}
