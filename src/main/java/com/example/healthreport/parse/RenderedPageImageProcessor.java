package com.example.healthreport.parse;

import java.awt.image.BufferedImage;

/**
 * 一次页面渲染的两个图像消费者入口。
 * <p>先产生 OCR 独立编码，再产生 LLM-A 压缩图，并在本方法结束时立即释放源位图。</p>
 */
public class RenderedPageImageProcessor {

    private final OcrImageEncoder ocrImageEncoder;
    private final ExtractionImageCompressor extractionImageCompressor;

    public RenderedPageImageProcessor(OcrImageEncoder ocrImageEncoder,
                                      ExtractionImageCompressor extractionImageCompressor) {
        this.ocrImageEncoder = ocrImageEncoder;
        this.extractionImageCompressor = extractionImageCompressor;
    }

    /** 消费并释放源渲染图；调用后调用方不得继续使用该 BufferedImage。 */
    public PageImageArtifacts processAndRelease(BufferedImage renderedImage) {
        if (renderedImage == null) {
            throw new IllegalArgumentException("页面渲染图不能为空");
        }
        try {
            byte[] ocrBytes = ocrImageEncoder.encodeForOcr(renderedImage);
            CompressedPageImage extractionImage = extractionImageCompressor.compressForExtraction(renderedImage);
            return new PageImageArtifacts(ocrBytes, extractionImage);
        } finally {
            renderedImage.flush();
        }
    }
}
