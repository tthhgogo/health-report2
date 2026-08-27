package com.example.healthreport.llm.extraction;

import lombok.Getter;
import lombok.ToString;

/** LLM-A 单批中的一页；文本与本页图像保持结构性绑定。 */
@Getter
@ToString(exclude = {"renderedText", "jpegBytes"})
public final class BatchPage {

    private final int page;
    private final String renderedText;
    private final byte[] jpegBytes;
    private final boolean imageRequired;

    public BatchPage(int page, String renderedText, byte[] jpegBytes, boolean imageRequired) {
        if (page < 1 || renderedText == null) {
            throw new IllegalArgumentException("批次页参数无效");
        }
        this.page = page;
        this.renderedText = renderedText;
        this.jpegBytes = jpegBytes;
        this.imageRequired = imageRequired;
    }
}
