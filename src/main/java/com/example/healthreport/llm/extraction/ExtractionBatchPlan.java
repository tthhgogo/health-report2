package com.example.healthreport.llm.extraction;

import lombok.Getter;

/** 单批请求及其仅在进程内保留的块号映射。 */
@Getter
public final class ExtractionBatchPlan {

    private final ExtractionBatchInput input;
    private final BatchAddressing addressing;

    public ExtractionBatchPlan(ExtractionBatchInput input, BatchAddressing addressing) {
        if (input == null || addressing == null) {
            throw new IllegalArgumentException("批次计划不能为空");
        }
        this.input = input;
        this.addressing = addressing;
    }
}
