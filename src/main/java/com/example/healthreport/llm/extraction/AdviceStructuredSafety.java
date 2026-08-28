package com.example.healthreport.llm.extraction;

/**
 * 一条饮食建议本身是什么性质。
 *
 * <p>与 {@link AdviceApplicability} 正交：「您已绝经，应注意补钙」是
 * {@code CURRENT_PATIENT} + {@link #SPECIAL_POPULATION}——建议确实给本人，
 * 但涉及特殊人群，仍需专业指导，不进结构化链路。</p>
 */
public enum AdviceStructuredSafety {

    /** 常规饮食建议。只有这一态可能进入结构化链路。 */
    NORMAL,

    /** 方向性限制，如低蛋白、限钾、限碘；须由医嘱个体化，系统不配食材清单。 */
    DIRECTIONAL_RESTRICTION,

    /** 面向特殊人群，如妊娠、哺乳、儿童、透析。 */
    SPECIAL_POPULATION,

    /** 看不出性质。拿不准时给这一态，后端按保守处理。 */
    UNCERTAIN
}
