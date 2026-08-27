package com.example.healthreport.safety;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 过敏安全扫描专用词项；只做确定性子串判断，不承载医疗语义分类。 */
final class AllergenSafetyTerms {

	/** 过敏原章节的标题用词；命中这些词但模型没抽到过敏原时触发降级。 */
	static final List<String> SECTION_TERM_LIST = Collections
		.unmodifiableList(Arrays.asList("过敏原", "变应原", "IgE", "致敏原"));

	/**
	 * 阳性与临界结果标记：命中其一表示该行可能存在需要核对的过敏结果。 本扫描只触发漏抽取降级，不能据此作出临床阳性诊断。 【只放 NFKC 之后的形式】——扫描对象是
	 * Segment.normalizedText，它已过 NFKC， 全角变体到这里都已折成半角。原表里的「（+）」「＋」「＋/－」经实测分别归一为 "(+)" /
	 * "+" / "+/-"，三者都已在本表中，写全角形式属于永远命中不了的死词。
	 */
	static final List<String> ADMITTED_RESULT_MARK_LIST = Collections.unmodifiableList(
			Arrays.asList("阳性", "强阳性", "弱阳性", "可疑", "临界", "阳", "(+)", "+", "++", "+++", "±", "+/-", "(±)", "(+/-)"));

	private AllergenSafetyTerms() {
	}

}
