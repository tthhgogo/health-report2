package com.example.healthreport.parse;

import org.springframework.stereotype.Component;

/** 根据 OCR 单图、请求体与协议开销共同计算有效图像字节上限。 */
@Component
public class OcrCapacityCalculator {

    static final long JSON_FIXED_OVERHEAD_BYTES = 64L * 1024L;
    static final long MULTIPART_FIXED_OVERHEAD_BYTES = 16L * 1024L;

    /**
     * 计算有效上限；全部运算使用 long，并在任何非正参数或协议无可用空间时启动失败。
     */
    public long calculate(long maxEncodedImageBytes, long maxRequestBodyBytes,
                          OcrRequestEncoding requestEncoding) {
        if (maxEncodedImageBytes <= 0L || maxRequestBodyBytes <= 0L || requestEncoding == null) {
            throw new IllegalStateException("OCR 容量与编码方式必须显式配置");
        }
        long fixedOverheadBytes = requestEncoding == OcrRequestEncoding.JSON_BASE64
                ? JSON_FIXED_OVERHEAD_BYTES : MULTIPART_FIXED_OVERHEAD_BYTES;
        if (maxRequestBodyBytes <= fixedOverheadBytes) {
            throw new IllegalStateException("OCR 请求体上限不大于协议固定开销");
        }
        long availableBodyBytes = maxRequestBodyBytes - fixedOverheadBytes;
        long bodyLimitedImageBytes;
        if (requestEncoding == OcrRequestEncoding.JSON_BASE64) {
            // floor(available * 3 / 4)，拆开乘法避免接近 Long.MAX_VALUE 时溢出。
            bodyLimitedImageBytes = availableBodyBytes / 4L * 3L
                    + (availableBodyBytes % 4L) * 3L / 4L;
        } else {
            bodyLimitedImageBytes = availableBodyBytes;
        }
        long effectiveBytes = Math.min(maxEncodedImageBytes, bodyLimitedImageBytes);
        if (effectiveBytes <= 0L) {
            throw new IllegalStateException("OCR 有效图像上限计算结果无效");
        }
        return effectiveBytes;
    }
}
