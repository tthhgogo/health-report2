package com.example.healthreport.constants;

/**
 * 展示模块底部声明的唯一文案来源。
 */
public final class DisclaimerConstants {

    /**
     * 报告未印结论、经参考范围比较确认落在范围内时，模块一卡片展示的固定文案。
     *
     * <p><b>它不是报告原文</b>：报告对这类指标只印了结果与参考值，没有写任何结论。
     * 因此措辞刻意选「在参考范围内」这种陈述事实的说法，而不是「正常」——
     * 「正常」听起来像报告下的结论，会让用户以为报告上写了这两个字。
     * 卡片上另有 {@code conclusionGenerated} 标志供前端做视觉区分。</p>
     */
    public static final String INDICATOR_IN_REFERENCE_RANGE = "在参考范围内";

    /**
     * 报告未印结论、经定性参考值精确匹配确认一致时，模块一卡片展示的固定文案。
     *
     * <p>措辞刻意停在「符合报告参考值」这一层事实上：
     * <b>它只表示结果与本报告给出的参考值一致，不表示未发现疾病、更不表示身体正常</b>。
     * 写成「未见异常」「正常」都会把「符合参考值」扩大解释成医学结论。</p>
     */
    public static final String INDICATOR_MATCHES_REFERENCE_VALUE = "符合报告参考值";

    /** 模块一声明，强调指标值来自报告原文且不能替代医生意见。 */
    public static final String MODULE_ONE = "以上指标数据均来自体检报告原文，仅供参考，如有疑问请咨询医生。";

    /** 模块二声明，明确健康问题汇总不构成二次诊断。 */
    public static final String MODULE_TWO = "以上内容均为体检报告原文结论的汇总，不构成二次诊断，如有疑问请咨询医生。";

    /** 模块三声明，明确饮食建议不能作为医疗或营养处方。 */
    public static final String MODULE_THREE = "以上建议均基于体检报告原文，不构成医疗或营养处方，具体饮食方案请遵医嘱。";

    /** 模块四声明，强调推荐基于报告与实际上架菜品数据。 */
    public static final String MODULE_FOUR = "推荐菜品基于体检报告内容及食堂菜品数据自动匹配，菜品信息以食堂实际上架为准。";

    private DisclaimerConstants() {
    }
}
