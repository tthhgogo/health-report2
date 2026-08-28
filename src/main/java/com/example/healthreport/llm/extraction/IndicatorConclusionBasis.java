package com.example.healthreport.llm.extraction;

/**
 * 一条数值指标的结论从哪来。
 *
 * <p>报告为该指标印了结论时永远走 {@link #REPORT_TEXT}；只有报告<b>没印结论</b>时，
 * 才允许走参考范围准入。两者不会并存——并存等于同一条指标有两套结论。</p>
 */
public enum IndicatorConclusionBasis {

    /** 报告印了结论原文（「↑偏高」「未见异常」），展示时直接引用该原文。 */
    REPORT_TEXT,

    /**
     * 报告只印了<b>数值</b>结果与参考范围、没印结论，经确定性数值比较确认结果落在范围内。
     * <p><b>只有落在范围内才用这一态</b>：超出范围而报告没给结论的指标一律不展示，
     * 系统不得凭空生成一个报告没写过的异常结论。</p>
     */
    REFERENCE_RANGE_IN_RANGE,

    /**
     * 报告只印了<b>定性</b>结果与定性参考值（如「亚硝酸盐 阴性 / 参考值 阴性」）、没印结论，
     * 经归一化后精确相等确认一致。
     * <p>归一化由 LLM-A 完成，Java 只对归一化结果做 {@code equals}——
     * 「阴性」与「未检出」是不是一回事属于医学等价判断，Java 不碰。</p>
     */
    REFERENCE_VALUE_MATCH
}
