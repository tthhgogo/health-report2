package com.example.healthreport.parse;

import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import com.example.healthreport.task.DegradeAccumulator;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 按文件顺序执行三档等效页预算。
 * <p>这里只做页数、排序和截断；非 Word 的页数真源是上传期 {@code precheckPages}。</p>
 */
@Service
public class PageBudgetService {

    public static final int PROCESS_PAGE_LIMIT = 30;
    public static final int TASK_PAGE_LIMIT = 60;

    /**
     * 计算总页数并保留任务前 30 个等效页；截断可以落在文件中间。
     */
    public PageBudgetResult apply(List<ParsedFile> fileResultList,
                                  DegradeAccumulator degradeAccumulator) {
        if (fileResultList == null || fileResultList.isEmpty() || degradeAccumulator == null) {
            throw new IllegalArgumentException("页数预算参数不能为空");
        }
        List<ParsedFile> orderedFileList = new ArrayList<ParsedFile>(fileResultList);
        Collections.sort(orderedFileList, new Comparator<ParsedFile>() {
            @Override
            public int compare(ParsedFile left, ParsedFile right) {
                return Integer.compare(left.getFileIndex(), right.getFileIndex());
            }
        });
        assertUniqueFileIndex(orderedFileList);

        int totalPages = 0;
        boolean containsWord = false;
        for (ParsedFile file : orderedFileList) {
            totalPages += file.getEffectivePageCount();
            containsWord = containsWord || file.isWord();
        }
        if (totalPages > TASK_PAGE_LIMIT) {
            throw new HealthReportException(containsWord
                    ? FailCode.PAGE_LIMIT_EXCEEDED : FailCode.SERVER_ERROR,
                    containsWord ? 400 : 500);
        }

        int remaining = Math.min(totalPages, PROCESS_PAGE_LIMIT);
        List<ParsedFile> retainedFileList = new ArrayList<ParsedFile>(orderedFileList.size());
        for (ParsedFile file : orderedFileList) {
            int retainedPages = Math.min(file.getEffectivePageCount(), remaining);
            if (retainedPages > 0) {
                retainedFileList.add(file.retainFirstPages(retainedPages));
                remaining -= retainedPages;
            }
        }
        int processedPages = Math.min(totalPages, PROCESS_PAGE_LIMIT);
        if (totalPages > PROCESS_PAGE_LIMIT) {
            degradeAccumulator.recordPageTruncated();
        }
        return new PageBudgetResult(retainedFileList, processedPages, totalPages);
    }

    private void assertUniqueFileIndex(List<ParsedFile> fileList) {
        Set<Integer> fileIndexSet = new HashSet<Integer>(fileList.size());
        for (ParsedFile file : fileList) {
            if (file == null || !fileIndexSet.add(file.getFileIndex())) {
                throw new IllegalArgumentException("fileIndex 不能为空且不能重复");
            }
        }
    }
}
