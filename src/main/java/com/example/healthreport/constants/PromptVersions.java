package com.example.healthreport.constants;

/** 三份在线体检报告分析提示词与菜品打标提示词各自独立演进的版本真源。 */
public final class PromptVersions {

	/** 调用一（健康指标）生产提示词版本，必须与文件头及摘要历史一致。 */
	public static final String INDICATORS = "indicators-1.1.0";

	/** 调用二（健康问题）生产提示词版本，必须与文件头及摘要历史一致。 */
	public static final String PROBLEMS = "problems-1.0.0";

	/** 调用三（饮食建议与标签）生产提示词版本，必须与文件头及摘要历史一致。 */
	public static final String DIET_TAGS = "diet-tags-1.1.0";

	/** 菜品打标提示词版本，必须与提示词头部和摘要历史一致。 */
	public static final String DISH_TAG = "dishtag-2.2.3";

	private PromptVersions() {
	}

}
