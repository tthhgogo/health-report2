package com.example.healthreport.parse;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 通过页数预算与零块裁决后，可进入 LLM-A 分批的解析计划。 */
@Getter
public final class ParsePlan {

    private final List<ParsedFile> readableFileList;
    private final int processedPages;
    private final int totalPages;

    public ParsePlan(List<ParsedFile> readableFileList, int processedPages, int totalPages) {
        this.readableFileList = Collections.unmodifiableList(new ArrayList<ParsedFile>(readableFileList));
        this.processedPages = processedPages;
        this.totalPages = totalPages;
    }
}
