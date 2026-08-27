package com.example.healthreport.parse.segment;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/** PDF 原生绘制单元密度闸，超限时要求整文件改走 OCR。 */
@Component
public class GlyphDensityGate {

    public static final int MAX_SEGMENTS_PER_PAGE = 400;

    private final AtomicLong glyphLevelPdfCount = new AtomicLong();

    /** 精确 400 块/页放行；严格大于阈值才触发，乘法使用 long 避免溢出。 */
    public boolean requiresOcr(int segmentCount, int effectivePageCount) {
        if (segmentCount < 0 || effectivePageCount <= 0) {
            throw new IllegalArgumentException("segment 数与页数必须有效");
        }
        boolean exceeded = (long) segmentCount
                > (long) effectivePageCount * (long) MAX_SEGMENTS_PER_PAGE;
        if (exceeded) {
            glyphLevelPdfCount.incrementAndGet();
        }
        return exceeded;
    }

    /** 返回密度闸累计触发次数，不记录任何文本或坐标。 */
    public long glyphLevelPdfCount() {
        return glyphLevelPdfCount.get();
    }
}
