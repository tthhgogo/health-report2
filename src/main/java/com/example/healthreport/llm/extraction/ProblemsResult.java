package com.example.healthreport.llm.extraction;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * 调用二（健康问题）的已校验结果。
 * <p>{@code problems} 数组即最终展示列表；准入完全由模型判定（设计方案 §6.1）。</p>
 */
@Getter
public final class ProblemsResult {

    private final String reportStatus;
    private final List<Problem> problems;

    @JsonCreator
    public ProblemsResult(@JsonProperty("reportStatus") String reportStatus,
                          @JsonProperty("problems") List<Problem> problems) {
        this.reportStatus = reportStatus;
        this.problems = problems == null
                ? Collections.<Problem>emptyList() : Collections.unmodifiableList(problems);
    }

    /** 单条健康问题。 */
    @Getter
    public static final class Problem {

        private final String sourceType;
        private final int page;
        private final String section;
        private final Integer itemNo;
        private final String indicatorName;
        private final String name;
        private final String rawText;

        @JsonCreator
        public Problem(@JsonProperty("sourceType") String sourceType,
                       @JsonProperty("page") int page,
                       @JsonProperty("section") String section,
                       @JsonProperty("itemNo") Integer itemNo,
                       @JsonProperty("indicatorName") String indicatorName,
                       @JsonProperty("name") String name,
                       @JsonProperty("rawText") String rawText) {
            this.sourceType = sourceType;
            this.page = page;
            this.section = section;
            this.itemNo = itemNo;
            this.indicatorName = indicatorName;
            this.name = name;
            this.rawText = rawText;
        }
    }
}
