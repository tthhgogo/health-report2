package com.example.healthreport.parse;

import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import com.example.healthreport.task.DegradeAccumulator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析层收口：先执行页数预算，再在任何分批和模型调用之前裁决零文字块。
 */
@Slf4j
@Service
public class ParseOrchestrator {

    private final PageBudgetService pageBudgetService;

    public ParseOrchestrator(PageBudgetService pageBudgetService) {
        this.pageBudgetService = pageBudgetService;
    }

    /**
     * 生成模型分批前的计划。全部文件无文字时失败；部分文件无文字时明确降级。
     */
    public ParsePlan prepare(List<ParsedFile> fileResultList,
                             DegradeAccumulator degradeAccumulator) {
        PageBudgetResult budgetResult = pageBudgetService.apply(fileResultList, degradeAccumulator);
        boolean anySegment = false;
        for (ParsedFile file : fileResultList) {
            anySegment = anySegment || file.hasSegments();
        }
        if (!anySegment) {
            throw new HealthReportException(FailCode.UNREADABLE, 400);
        }

        for (ParsedFile file : fileResultList) {
            if (!file.hasSegments()) {
                degradeAccumulator.recordBatchUnreadable();
            }
        }

        List<ParsedFile> readableFileList = new ArrayList<ParsedFile>(
                budgetResult.getRetainedFileList().size());
        for (ParsedFile file : budgetResult.getRetainedFileList()) {
            if (file.hasSegments()) {
                readableFileList.add(file);
            }
        }
        if (readableFileList.isEmpty()) {
            throw new HealthReportException(FailCode.UNREADABLE, 400);
        }
        // 解析阶段收口。processedPages 与 totalPages 不等就说明页数预算截过——
        // DegradeAccumulator 那条只说「命中了 PAGE_TRUNCATED」，截了多少只有这里看得到。
        log.info("解析阶段完成，可读文件数={}，输入文件数={}，实际处理页数={}，总页数={}",
                readableFileList.size(), fileResultList.size(),
                budgetResult.getProcessedPages(), budgetResult.getTotalPages());
        return new ParsePlan(readableFileList, budgetResult.getProcessedPages(), budgetResult.getTotalPages());
    }
}
