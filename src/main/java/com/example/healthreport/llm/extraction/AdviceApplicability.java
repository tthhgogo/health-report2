package com.example.healthreport.llm.extraction;

/**
 * 一条饮食建议是给谁的。
 *
 * <p>由 LLM-A 判断。「儿童」「孕期」这类词出现在文本里可能在说受检者、家属、既往史，
 * 也可能是科普——<b>分辨指向需要语义理解，Java 只能做字面包含，做不了这件事</b>。</p>
 */
public enum AdviceApplicability {

    /** 明确针对本次受检者。只有这一态可能进入结构化链路。 */
    CURRENT_PATIENT,

    /** 针对他人，如家属或既往史里提到的人。 */
    OTHER_PERSON,

    /** 通用科普或人群说明，不针对具体个人。 */
    GENERAL_INFORMATION,

    /** 看不出指向。拿不准时给这一态，后端按保守处理。 */
    UNCERTAIN
}
