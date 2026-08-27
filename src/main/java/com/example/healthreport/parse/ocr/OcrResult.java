package com.example.healthreport.parse.ocr;

import lombok.Getter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** OCR 原子块及服务端实际识别图尺寸；尺寸是否返回由必填接入配置声明。 */
@Getter
@ToString(exclude = "blockList")
public final class OcrResult {

    private final List<OcrBlock> blockList;
    private final Integer imageWidth;
    private final Integer imageHeight;

    public OcrResult(List<OcrBlock> blockList, Integer imageWidth, Integer imageHeight) {
        if (blockList == null || (imageWidth == null) != (imageHeight == null)
                || (imageWidth != null && (imageWidth.intValue() <= 0 || imageHeight.intValue() <= 0))) {
            throw new IllegalArgumentException("OCR 结果参数无效");
        }
        this.blockList = Collections.unmodifiableList(new ArrayList<OcrBlock>(blockList));
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
    }
}
