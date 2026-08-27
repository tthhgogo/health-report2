package com.example.healthreport.parse.word;

import com.example.healthreport.parse.segment.Segment;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Word 有序原子块与精确容量；Word 永不携带页面图片。 */
@Getter
public final class WordParseResult {

    private final List<Segment> segmentList;
    private final WordCapacityResult capacity;
    private final int residualNonStandardCount;

    public WordParseResult(List<Segment> segmentList, WordCapacityResult capacity,
                           int residualNonStandardCount) {
        this.segmentList = Collections.unmodifiableList(new ArrayList<Segment>(segmentList));
        this.capacity = capacity;
        this.residualNonStandardCount = residualNonStandardCount;
    }

    /** Word 逻辑页不发送图像给 LLM-A。 */
    public boolean isImageRequired() {
        return false;
    }

    /** Word 逻辑页不生成或持有 JPEG。 */
    public byte[] getJpegBytes() {
        return null;
    }
}
