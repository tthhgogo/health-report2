package com.example.healthreport.cache;

import com.example.healthreport.support.PartialReason;
import com.example.healthreport.task.DegradeAccumulator;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/**
 * Redis 中保存并由结果接口下发的最小结果契约。
 * <p>不含姓名、性别、完整 OCR 文本或完整 segment 集合。</p>
 */
@Getter
public class AnalysisResult {

    private final boolean partial;
    private final PartialReason partialReason;
    private final boolean suppressDietAdvice;
    private final boolean suppressDishRecommend;
    private final int processedPages;
    private final int totalPages;
    private final AnalysisModules modules;

    /** Jackson 反序列化入口；业务代码应使用 {@link #create}。 */
    @JsonCreator
    AnalysisResult(@JsonProperty("partial") boolean partial,
                   @JsonProperty("partialReason") PartialReason partialReason,
                   @JsonProperty("suppressDietAdvice") boolean suppressDietAdvice,
                   @JsonProperty("suppressDishRecommend") boolean suppressDishRecommend,
                   @JsonProperty("processedPages") int processedPages,
                   @JsonProperty("totalPages") int totalPages,
                   @JsonProperty("modules") AnalysisModules modules) {
        this.partial = partial;
        this.partialReason = partialReason;
        this.suppressDietAdvice = suppressDietAdvice;
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
        if (accumulator.suppressDietAdvice()) {
            safeModules = safeModules.withoutDietAdviceAndDishRecommend();
        } else if (accumulator.suppressDishRecommend()) {
            safeModules = safeModules.withoutDishRecommend();
        }
        return new AnalysisResult(accumulator.partial(), accumulator.primaryReason(),
                accumulator.suppressDietAdvice(), accumulator.suppressDishRecommend(),
                processedPages, totalPages, safeModules);
    }
}
