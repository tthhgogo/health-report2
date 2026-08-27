package com.example.healthreport.llm.extraction;

/** 过敏原检测结果状态，决定过敏提醒链路准入。 */
public enum AllergenResultStatus {

	/** 明确阳性，进入提醒与菜品拦截链路。 */
	POSITIVE,

	/** 明确阴性，不进入链路且不计未知数。 */
	NEGATIVE,

	/** 报告标为弱阳性、可疑或临界；作为产品安全信号进入链路，但不等同临床确诊。 */
	BORDERLINE,

	/** 结果没有读明白，不进入链路但单独计数。 */
	UNKNOWN

}
