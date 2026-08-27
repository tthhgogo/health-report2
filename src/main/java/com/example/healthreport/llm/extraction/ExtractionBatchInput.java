package com.example.healthreport.llm.extraction;

import lombok.Getter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** LLM-A 单批请求输入；不包含任务、用户、文件名或进程内 segmentId。 */
@Getter
@ToString(exclude = {"systemPrompt", "pageList"})
public final class ExtractionBatchInput {

    private final String systemPrompt;
    private final String promptVersion;
    private final int fileIndex;
    private final int batchIndex;
    private final int batchCount;
    private final List<BatchPage> pageList;

    public ExtractionBatchInput(String systemPrompt, String promptVersion, int fileIndex,
                          int batchIndex, int batchCount, List<BatchPage> pageList) {
        if (systemPrompt == null || promptVersion == null || fileIndex < 0 || fileIndex > 4
                || batchIndex < 0 || batchCount < 1 || batchCount > 8 || batchIndex >= batchCount
                || pageList == null || pageList.size() > 8) {
            throw new IllegalArgumentException("LLM-A 批次输入参数无效");
        }
        this.systemPrompt = systemPrompt;
        this.promptVersion = promptVersion;
        this.fileIndex = fileIndex;
        this.batchIndex = batchIndex;
        this.batchCount = batchCount;
        this.pageList = Collections.unmodifiableList(new ArrayList<BatchPage>(pageList));
    }
}
