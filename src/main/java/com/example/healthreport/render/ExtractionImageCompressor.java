package com.example.healthreport.render;

import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;

/**
 * 发给 LLM-A 的唯一页面图压缩入口。
 * <p>固定两档，不落盘、不放大；两档均超 1MiB 时明确失败。</p>
 */
@Component
public class ExtractionImageCompressor {

    static final int PRIMARY_LONG_EDGE = 2000;
    static final float PRIMARY_QUALITY = 0.85F;
    static final int FALLBACK_LONG_EDGE = 1600;
    static final float FALLBACK_QUALITY = 0.80F;
    static final long MAX_IMAGE_BYTES = 1024L * 1024L;

    /** 从渲染档源图降采样并压缩，绝不修改或释放调用方持有的源图。 */
    public CompressedPageImage compressForExtraction(BufferedImage source) {
        CompressedPageImage primary = compress(source, PRIMARY_LONG_EDGE, PRIMARY_QUALITY);
        if ((long) primary.sizeBytes() <= MAX_IMAGE_BYTES) {
            return primary;
        }
        CompressedPageImage fallback = compress(source, FALLBACK_LONG_EDGE, FALLBACK_QUALITY);
        if ((long) fallback.sizeBytes() <= MAX_IMAGE_BYTES) {
            return fallback;
        }
        throw new ImageTooLargeException(fallback.sizeBytes(), MAX_IMAGE_BYTES);
    }

    private CompressedPageImage compress(BufferedImage source, int maxLongEdge, float quality) {
        BufferedImage resized = ImageEncodingSupport.resizeRgb(source, maxLongEdge);
        try {
            byte[] jpegBytes = ImageEncodingSupport.encodeJpeg(resized, quality);
            return new CompressedPageImage(jpegBytes, resized.getWidth(), resized.getHeight());
        } finally {
            resized.flush();
        }
    }
}
