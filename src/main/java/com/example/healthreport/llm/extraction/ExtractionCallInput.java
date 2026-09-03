package com.example.healthreport.llm.extraction;

import com.example.healthreport.render.PageImage;

import java.util.Collections;
import java.util.List;

/**
 * 单次体检报告分析模型调用的完整输入：系统提示词 + 任务说明 + 全部页面图。
 * <p>三次调用的 {@code pageList} 必须引用同一 {@code PageImageSequence} 的元素，
 * 不得重新渲染、重新压缩或重排（设计方案 §4.2）。</p>
 */
public final class ExtractionCallInput {

    private final ExtractionCall call;
    private final String systemPrompt;
    private final String userText;
    private final List<PageImage> pageList;

    public ExtractionCallInput(ExtractionCall call, String systemPrompt, String userText,
                               List<PageImage> pageList) {
        if (call == null || systemPrompt == null || systemPrompt.isEmpty()
                || userText == null || userText.isEmpty()
                || pageList == null || pageList.isEmpty()) {
            throw new IllegalArgumentException("模型调用输入无效");
        }
        this.call = call;
        this.systemPrompt = systemPrompt;
        this.userText = userText;
        this.pageList = Collections.unmodifiableList(pageList);
    }

    public ExtractionCall getCall() {
        return call;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public String getUserText() {
        return userText;
    }

    public List<PageImage> getPageList() {
        return pageList;
    }
}
