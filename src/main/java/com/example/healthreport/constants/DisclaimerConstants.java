package com.example.healthreport.constants;

/**
 * 展示模块底部声明的唯一文案来源。
 */
public final class DisclaimerConstants {

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
