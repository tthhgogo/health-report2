package com.example.healthreport.support;

/**
 * 部分结果的主降级原因（设计方案 §4.4）。
 * <p>单值列；两类同时发生时取 {@link #DIET_TAG_DROPPED}——它携带「模块四已抑制」的行为后果。</p>
 */
public enum PartialReason {

    /**
     * 阶段一/二的个别条目不合契约已被剔除，其余照常输出。
     * <p><b>抑制范围为空</b>——它不影响任何模块的输出，只表示这份结果少了几条。</p>
     */
    SCHEMA_ITEM_DROPPED,

    /**
     * 阶段三的饮食标签被剔除，<b>必须抑制整个模块四</b>。
     * <p>每条 reject 标签都会在模块四选择一个排除方向集合；剔掉「低嘌呤」这一条，
     * 高嘌呤的菜就不再被排除，而推荐照常输出——那是把一次格式错误变成一次错误推荐。
     * 模块三可展示其余已校验条目。</p>
     */
    DIET_TAG_DROPPED
}
