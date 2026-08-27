package com.example.healthreport.llm.extraction;

/** 批内章节与前一批章节的关系，由 LLM-A 明确给出。 */
public enum SectionRelation {

    /** 标题位于当前批次。 */
    CURRENT,

    /** 机械承接同文件前一批末章节。 */
    CONTINUATION,

    /** 内容本来不属于任何章节。 */
    UNSECTIONED,

    /** 模型无法确定章节归属。 */
    UNKNOWN
}
