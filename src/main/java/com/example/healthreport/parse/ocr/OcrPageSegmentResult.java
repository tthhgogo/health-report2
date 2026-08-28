package com.example.healthreport.parse.ocr;

import com.example.healthreport.parse.segment.Segment;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 单页 OCR 原子块转换结果。 */
@Getter
public final class OcrPageSegmentResult {

    private final List<Segment> segmentList;
    private final int nextSequence;

    public OcrPageSegmentResult(List<Segment> segmentList, int nextSequence) {
        this.segmentList = Collections.unmodifiableList(new ArrayList<Segment>(segmentList));
        this.nextSequence = nextSequence;
    }
}
