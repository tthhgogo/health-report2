package com.example.healthreport.llm.extraction;

import com.example.healthreport.constants.PromptVersions;
import com.example.healthreport.parse.ParsePlan;
import com.example.healthreport.parse.ParsedFile;
import com.example.healthreport.parse.ParsedPage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 按文件边界和每批八页限制生成 LLM-A 调用计划。 */
@Service
public class BatchPlanner {

    public static final int PAGES_PER_BATCH = 8;
    public static final int MAX_BATCHES = 8;

    private final ExtractionPromptProvider promptProvider;

    public BatchPlanner(ExtractionPromptProvider promptProvider) {
        this.promptProvider = promptProvider;
    }

    /**
     * 生成全任务批次；批次不跨文件，批次数为各文件向上取整之和。
     */
    public List<ExtractionBatchPlan> plan(ParsePlan parsePlan) {
        if (parsePlan == null || parsePlan.getReadableFileList().isEmpty()) {
            throw new IllegalArgumentException("分批前必须已有可读文件");
        }
        int batchCount = countBatches(parsePlan.getReadableFileList());
        if (batchCount > MAX_BATCHES) {
            throw new IllegalStateException("LLM-A 批次数超过上限");
        }
        String systemPrompt = promptProvider.getPrompt();
        List<ExtractionBatchPlan> batchPlanList = new ArrayList<ExtractionBatchPlan>(batchCount);
        int batchIndex = 0;
        for (ParsedFile file : parsePlan.getReadableFileList()) {
            List<ParsedPage> pageList = file.getPageList();
            for (int from = 0; from < pageList.size(); from += PAGES_PER_BATCH) {
                int to = Math.min(from + PAGES_PER_BATCH, pageList.size());
                BatchAddressing addressing = new BatchAddressing(file.getFileIndex(),
                        pageList.subList(from, to));
                ExtractionBatchInput input = new ExtractionBatchInput(systemPrompt, PromptVersions.EXTRACTION,
                        file.getFileIndex(), batchIndex, batchCount, addressing.getPageList());
                batchPlanList.add(new ExtractionBatchPlan(input, addressing));
                batchIndex++;
            }
        }
        return batchPlanList;
    }

    private int countBatches(List<ParsedFile> fileList) {
        int count = 0;
        for (ParsedFile file : fileList) {
            count += (file.getPageList().size() + PAGES_PER_BATCH - 1) / PAGES_PER_BATCH;
        }
        return count;
    }
}
