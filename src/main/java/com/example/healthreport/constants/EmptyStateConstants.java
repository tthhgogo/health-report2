package com.example.healthreport.constants;

/**
 * 展示模块空态文案的唯一来源。
 */
public final class EmptyStateConstants {

    /** 模块一没有带明确结论指标时仍保留模块所用的空态。 */
    public static final String MODULE_ONE = "本次报告未提取到带明确结论的指标项";

    /** 模块二没有模型准入健康问题时使用的保守空态，不推断用户健康状态。 */
    public static final String MODULE_TWO = "本次报告未提取到明确的异常结论或健康提示。";

    /** 模块三过敏提醒分区没有报告原文条目时使用的空态。 */
    public static final String MODULE_THREE_ALLERGEN = "本次报告未提取到明确的过敏原相关内容";

    /** 模块三营养补充分区没有报告原文条目时使用的空态。 */
    public static final String MODULE_THREE_NUTRITION = "本次报告未提取到明确的营养补充建议";

    /** 模块三饮食注意分区没有报告原文条目时使用的空态。 */
    public static final String MODULE_THREE_DIET = "本次报告未提取到明确的饮食注意要求";

    /** 模块四在没有任何正式枚举建议时使用的空态。 */
    public static final String MODULE_FOUR_NO_ADVICE = "本食堂菜品暂无个性化推荐。";

    /** 模块四有建议但裁决后两个列表均为空时使用的空态。 */
    public static final String MODULE_FOUR_NO_MATCH = "本次未匹配到符合建议的食堂菜品，菜品以食堂实际上架为准。";

    private EmptyStateConstants() {
    }
}
