package com.example.healthreport.constants;

/** 抽取与菜品打标两份提示词各自独立演进的版本真源。 */
public final class PromptVersions {

	/** 体检报告抽取提示词版本，必须与提示词头部和摘要历史一致。 */
	public static final String EXTRACTION = "extraction-2.3.2";

	/** 菜品打标提示词版本，必须与提示词头部和摘要历史一致。 */
	public static final String DISH_TAG = "dishtag-2.2.2";

	private PromptVersions() {
	}

}
