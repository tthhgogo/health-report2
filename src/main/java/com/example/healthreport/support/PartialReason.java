package com.example.healthreport.support;

/**
 * 部分结果的主降级原因。
 */
public enum PartialReason {

    /** 报告超过三十等效页，仅处理前段页面。 */
    PAGE_TRUNCATED,

    /** 某个文件或批次无法读取，其内容被整体丢弃。 */
    BATCH_UNREADABLE,

    /** 过敏原抽取可能遗漏，必须抑制菜品推荐。 */
    ALLERGEN_SUSPECT_MISS
}
