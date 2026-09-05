package com.example.healthreport.llm.extraction;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * 调用三（饮食建议与标签）的已校验结果。
 * <p>条目所在数组就是它的方向；{@code dimension + enumKey + 方向}
 * 是 Java 查询 Redis 方向集合的唯一输入（设计方案 §4.2.2）。</p>
 */
@Getter
public final class DietAdviceResult {

    private final String reportStatus;
    private final List<DietTag> recommend;
    private final List<DietTag> reject;

    @JsonCreator
    public DietAdviceResult(@JsonProperty("reportStatus") String reportStatus,
                          @JsonProperty("recommend") List<DietTag> recommend,
                          @JsonProperty("reject") List<DietTag> reject) {
        this.reportStatus = reportStatus;
        this.recommend = recommend == null
                ? Collections.<DietTag>emptyList() : Collections.unmodifiableList(recommend);
        this.reject = reject == null
                ? Collections.<DietTag>emptyList() : Collections.unmodifiableList(reject);
    }

    /** 单条饮食建议标签。 */
    @Getter
    public static final class DietTag {

        private final String dimension;
        private final String enumKey;
        private final int page;
        private final String section;
        private final Integer itemNo;
        private final String quote;
        private final String rawText;

        @JsonCreator
        public DietTag(@JsonProperty("dimension") String dimension,
                       @JsonProperty("enumKey") String enumKey,
                       @JsonProperty("page") int page,
                       @JsonProperty("section") String section,
                       @JsonProperty("itemNo") Integer itemNo,
                       @JsonProperty("quote") String quote,
                       @JsonProperty("rawText") String rawText) {
            this.dimension = dimension;
            this.enumKey = enumKey;
            this.page = page;
            this.section = section;
            this.itemNo = itemNo;
            this.quote = quote;
            this.rawText = rawText;
        }
    }
}
