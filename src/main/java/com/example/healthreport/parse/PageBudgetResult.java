package com.example.healthreport.parse;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 三档页数预算的确定性结果。 */
@Getter
public final class PageBudgetResult {

    private final List<ParsedFile> retainedFileList;
    private final int processedPages;
    private final int totalPages;

    public PageBudgetResult(List<ParsedFile> retainedFileList, int processedPages, int totalPages) {
        this.retainedFileList = Collections.unmodifiableList(new ArrayList<ParsedFile>(retainedFileList));
        this.processedPages = processedPages;
        this.totalPages = totalPages;
    }
}
