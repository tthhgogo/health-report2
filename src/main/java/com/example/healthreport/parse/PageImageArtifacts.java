package com.example.healthreport.parse;

import lombok.Getter;

import java.util.Arrays;

/** 同一次页面渲染的 OCR 独立编码与 LLM-A 压缩产物。 */
@Getter
public final class PageImageArtifacts {

    private final byte[] ocrEncodedImageBytes;
    private final CompressedPageImage extractionImage;

    public PageImageArtifacts(byte[] ocrEncodedImageBytes, CompressedPageImage extractionImage) {
        if (ocrEncodedImageBytes == null || extractionImage == null) {
            throw new IllegalArgumentException("页面图两个消费者产物不能为空");
        }
        this.ocrEncodedImageBytes = Arrays.copyOf(ocrEncodedImageBytes, ocrEncodedImageBytes.length);
        this.extractionImage = extractionImage;
    }

    public byte[] getOcrEncodedImageBytes() {
        return Arrays.copyOf(ocrEncodedImageBytes, ocrEncodedImageBytes.length);
    }
}
