package com.example.healthreport.parse;

import lombok.Getter;

/**
 * Word 上传阶段的确定性遍历结果，不保存正文或图片字节。
 */
@Getter
public class WordInspection {

    private final int nativeSegmentCount;
    private final int qualifiedEmbeddedImageCount;
    private final boolean readable;

    public WordInspection(int nativeSegmentCount, int qualifiedEmbeddedImageCount, boolean readable) {
        this.nativeSegmentCount = nativeSegmentCount;
        this.qualifiedEmbeddedImageCount = qualifiedEmbeddedImageCount;
        this.readable = readable;
    }
}
