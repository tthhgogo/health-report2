package com.example.healthreport.llm.extraction;

import com.example.healthreport.constants.PromptVersions;

/**
 * 体检报告分析模型的三次串行调用（设计方案 §4.1）。
 * <p>每个枚举值携带生产 Prompt 路径、生产 Schema 路径与 promptVersion；
 * 顺序即调用顺序，不得并发、不得跳过。</p>
 */
public enum ExtractionCall {

    /** 调用一：健康指标（模块一 + 同一性校验临时字段）。 */
    INDICATORS("prompt/indicators.md", "schema/indicators.schema.json", PromptVersions.INDICATORS),

    /** 调用二：健康问题（模块二）。 */
    PROBLEMS("prompt/health-problems.md", "schema/health_problems.schema.json", PromptVersions.PROBLEMS),

    /** 调用三：饮食建议与正式枚举标签（模块三，及模块四的标签输入）。 */
    DIET_TAGS("prompt/diet-tags.md", "schema/diet_tags.schema.json", PromptVersions.DIET_TAGS);

    private final String promptResource;
    private final String schemaResource;
    private final String promptVersion;

    ExtractionCall(String promptResource, String schemaResource, String promptVersion) {
        this.promptResource = promptResource;
        this.schemaResource = schemaResource;
        this.promptVersion = promptVersion;
    }

    public String getPromptResource() {
        return promptResource;
    }

    public String getSchemaResource() {
        return schemaResource;
    }

    public String getPromptVersion() {
        return promptVersion;
    }
}
