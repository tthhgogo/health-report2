package com.example.healthreport.parse.ocr;

import com.example.healthreport.parse.segment.BBox;
import lombok.Getter;
import lombok.ToString;

/** OCR 服务返回的单个原子识别块；文本不得进入日志或异常消息。 */
@Getter
@ToString(exclude = "rawText")
public final class OcrBlock {

    private final String rawText;
    private final BBox bbox;

    public OcrBlock(String rawText, BBox bbox) {
        if (rawText == null) {
            throw new IllegalArgumentException("OCR 原子块文本不能为空");
        }
        this.rawText = rawText;
        this.bbox = bbox;
    }
}
