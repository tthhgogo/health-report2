package com.example.healthreport.llm.extraction;

import lombok.Getter;
import lombok.ToString;

/** 单批调用结果；原始模型 content 仅供后续校验链路消费，不写日志。 */
@Getter
@ToString(exclude = "rawContent")
public final class ExtractionBatchResult {

    private final ExtractionBatchPlan plan;
    private final BatchStatus batchStatus;
    private final String rawContent;

    public ExtractionBatchResult(ExtractionBatchPlan plan, BatchStatus batchStatus, String rawContent) {
        if (plan == null || batchStatus == null || rawContent == null) {
            throw new IllegalArgumentException("批次结果参数不能为空");
        }
        this.plan = plan;
        this.batchStatus = batchStatus;
        this.rawContent = rawContent;
    }
}
