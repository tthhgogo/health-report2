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
    ALLERGEN_SUSPECT_MISS,

    /**
     * 个别条目不合 Schema 已被剔除，其余照常输出。
     *
     * <p><b>抑制范围为空</b>——它不影响任何模块的输出，只表示这份结果少了几条。
     * 过敏原与章节不参与剔除（前者是一级红线，后者被其他条目按 sectionIndex 引用）。
     * 被剔除的是饮食注意时另记 {@link #DIET_REQUIREMENT_DROPPED}，因为那一类会动摇安全结论。</p>
     */
    SCHEMA_ITEM_DROPPED,

    /**
     * 被剔除的条目里含饮食注意，**必须抑制菜品推荐**。
     *
     * <p>每一条 {@code dietRequirements} 都会在模块四生成一个 <b>REJECT 方向集合</b>
     * （{@code DishRecommendInputFactory}）。剔掉「低嘌呤」这一条，高嘌呤的菜就不再被排除，
     * 而推荐照常输出——那是把一次<b>格式错误</b>变成一次<b>错误推荐</b>。
     * 营养补充只生成 RECOMMEND 方向，剔掉只是少推荐一条，不在此列。</p>
     */
    DIET_REQUIREMENT_DROPPED
}
