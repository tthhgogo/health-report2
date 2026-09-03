package com.example.healthreport.llm.extraction;

/**
 * 三次串行调用全部通过校验后的任务级结果。
 * <p>唯一汇总入口消费；{@code indicators} 已剥离临时患者字段。</p>
 */
public final class ExtractionOutcome {

    private final IndicatorsResult indicators;
    private final ProblemsResult problems;
    private final DietTagsResult dietTags;

    public ExtractionOutcome(IndicatorsResult indicators, ProblemsResult problems,
                             DietTagsResult dietTags) {
        if (indicators == null || problems == null || dietTags == null) {
            throw new IllegalArgumentException("三阶段结果都不能为空");
        }
        this.indicators = indicators;
        this.problems = problems;
        this.dietTags = dietTags;
    }

    public IndicatorsResult getIndicators() {
        return indicators;
    }

    public ProblemsResult getProblems() {
        return problems;
    }

    public DietTagsResult getDietTags() {
        return dietTags;
    }
}
