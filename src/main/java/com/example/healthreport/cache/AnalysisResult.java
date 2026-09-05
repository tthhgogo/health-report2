package com.example.healthreport.cache;

import com.example.healthreport.support.PartialReason;
import com.example.healthreport.task.DegradeAccumulator;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;

/**
 * Redis 中保存并由结果接口下发的最小结果契约。
 * <p>不含姓名、性别、页面图或模型原始响应。
 * 曾有 {@code suppressDietAdvice} 字段，2026-09-03 随文档一并删除：
 * 全案没有任何规则会把它置 true。</p>
 */
@ApiModel(value = "AnalysisResult", description = "体检报告分析结果；结果接口的完整响应体")
@Getter
public class AnalysisResult {

    @ApiModelProperty(value = "是否为部分结果：个别条目被契约校验剔除后其余照常输出", required = true,
            example = "false")
    private final boolean partial;

    @ApiModelProperty(value = "部分结果的主降级原因；partial 为 false 时为 null", example = "SCHEMA_ITEM_DROPPED")
    private final PartialReason partialReason;

    @ApiModelProperty(value = "模块四（食堂菜品推荐）是否被整体抑制；为 true 时 modules.dishRecommendations 为 null",
            required = true, example = "false")
    private final boolean suppressDishRecommend;

    @ApiModelProperty(value = "实际完成分析的页数", required = true, example = "6")
    private final int processedPages;

    @ApiModelProperty(value = "报告总页数（等效页）", required = true, example = "6")
    private final int totalPages;

    @ApiModelProperty(value = "四个展示模块的结果容器", required = true)
    private final AnalysisModules modules;

    /** Jackson 反序列化入口；业务代码应使用 {@link #create}。 */
    @JsonCreator
    AnalysisResult(@JsonProperty("partial") boolean partial,
                   @JsonProperty("partialReason") PartialReason partialReason,
                   @JsonProperty("suppressDishRecommend") boolean suppressDishRecommend,
                   @JsonProperty("processedPages") int processedPages,
                   @JsonProperty("totalPages") int totalPages,
                   @JsonProperty("modules") AnalysisModules modules) {
        this.partial = partial;
        this.partialReason = partialReason;
        this.suppressDishRecommend = suppressDishRecommend;
        this.processedPages = processedPages;
        this.totalPages = totalPages;
        this.modules = modules == null ? AnalysisModules.empty() : modules;
    }

    /**
     * 从任务级累加器一次性生成结果开关，禁止从单个 partialReason 反推抑制状态。
     */
    public static AnalysisResult create(DegradeAccumulator accumulator, int processedPages,
                                        int totalPages, AnalysisModules modules) {
        if (accumulator == null) {
            throw new IllegalArgumentException("降级累加器不能为空");
        }
        if (processedPages < 0 || totalPages < 0 || processedPages > totalPages) {
            throw new IllegalArgumentException("页数统计不合法");
        }
        AnalysisModules safeModules = modules == null ? AnalysisModules.empty() : modules;
        if (accumulator.suppressDishRecommend()) {
            safeModules = safeModules.withoutDishRecommend();
        }
        return new AnalysisResult(accumulator.partial(), accumulator.primaryReason(),
                accumulator.suppressDishRecommend(),
                processedPages, totalPages, safeModules);
    }
}
