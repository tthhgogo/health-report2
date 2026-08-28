package com.example.healthreport.llm.extraction;

/**
 * 定性检查结果的归一化取值。
 *
 * <p>报告里「阴性」「(-)」「未见」可能指同一件事，把它们统一到同一个枚举是<b>语义归一化</b>，
 * 由 LLM-A 完成；本枚举只是那次归一化的落点，Java 拿到之后只做 {@code equals}。</p>
 *
 * <p><b>刻意只收这四个。</b> 多一个词条就多一次医学等价判断——
 * 「阴性」是否等于「未检出」这类问题需要医学评审，不能由实现顺手决定。
 * 归不进这四个的，模型给 {@code valueMatch = null}，该指标不展示。</p>
 */
public enum ComparableQualitativeValue {

    /** 阴性；报告写法含「阴性」「(-)」「未见」等。 */
    NEGATIVE,

    /** 阳性；报告写法含「阳性」「(+)」等。 */
    POSITIVE,

    /** 弱阳性；报告写法含「弱阳性」「±」等。 */
    WEAK_POSITIVE,

    /** 未检出；与 {@link #NEGATIVE} <b>不是</b>同义词，两者不得互相匹配。 */
    NOT_DETECTED
}
