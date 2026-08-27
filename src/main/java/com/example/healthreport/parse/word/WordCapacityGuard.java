package com.example.healthreport.parse.word;

import com.example.healthreport.parse.segment.Segment;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import org.springframework.stereotype.Component;

import java.util.List;

/** Word 完成所有内嵌图 OCR 后的精确容量闸，只做可穷举的数量比较。 */
@Component
public class WordCapacityGuard {

    public static final int MAX_SEGMENTS = 1200;
    public static final int MAX_EMBEDDED_IMAGES = 30;
    public static final int SEGMENTS_PER_PAGE = 40;

    /** 超限即拒绝且不截断；调用方必须在调用 LLM-A 前执行。 */
    public WordCapacityResult check(List<Segment> orderedSegmentList, int embeddedImageCount) {
        if (orderedSegmentList == null || embeddedImageCount < 0) {
            throw new IllegalArgumentException("Word 容量参数无效");
        }
        int exactSegmentCount = orderedSegmentList.size();
        if (exactSegmentCount > MAX_SEGMENTS || embeddedImageCount > MAX_EMBEDDED_IMAGES) {
            throw new HealthReportException(FailCode.PAGE_LIMIT_EXCEEDED, 400);
        }
        int exactWordPages = (exactSegmentCount + SEGMENTS_PER_PAGE - 1) / SEGMENTS_PER_PAGE;
        return new WordCapacityResult(exactSegmentCount, embeddedImageCount, exactWordPages);
    }
}
