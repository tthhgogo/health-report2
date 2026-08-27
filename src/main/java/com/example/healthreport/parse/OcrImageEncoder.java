package com.example.healthreport.parse;

import java.awt.image.BufferedImage;

/**
 * PDF/OFD OCR 源图的独立编码器。
 * <p>优先 PNG 无损，超有效上限才回退 JPEG 0.95；不缩放，也不复用 LLM-A 压缩结果。</p>
 */
public class OcrImageEncoder {

    static final float JPEG_FALLBACK_QUALITY = 0.95F;

    private final long effectiveOcrImageBytes;

    public OcrImageEncoder(long effectiveOcrImageBytes) {
        if (effectiveOcrImageBytes <= 0L) {
            throw new IllegalArgumentException("OCR 有效图像上限必须大于零");
        }
        this.effectiveOcrImageBytes = effectiveOcrImageBytes;
    }

    /** 编码 OCR 图，不释放调用方持有的源图。 */
    public byte[] encodeForOcr(BufferedImage source) {
        if (source == null) {
            throw new IllegalArgumentException("OCR 源图不能为空");
        }
        byte[] pngBytes = ImageEncodingSupport.encodePng(source);
        if ((long) pngBytes.length <= effectiveOcrImageBytes) {
            return pngBytes;
        }
        BufferedImage rgbImage = ImageEncodingSupport.resizeRgb(source,
                Math.max(source.getWidth(), source.getHeight()));
        try {
            byte[] jpegBytes = ImageEncodingSupport.encodeJpeg(rgbImage, JPEG_FALLBACK_QUALITY);
            if ((long) jpegBytes.length <= effectiveOcrImageBytes) {
                return jpegBytes;
            }
            throw new ImageTooLargeException(jpegBytes.length, effectiveOcrImageBytes);
        } finally {
            rgbImage.flush();
        }
    }
}
