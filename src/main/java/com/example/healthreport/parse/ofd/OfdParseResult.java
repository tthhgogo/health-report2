package com.example.healthreport.parse.ofd;

import com.example.healthreport.parse.segment.Segment;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** OFD 原子文本对象解析结果，只在当前分析进程内存中流转。 */
@Getter
public final class OfdParseResult {

    private final List<Segment> segmentList;
    private final int pageCount;
    private final int residualNonStandardCount;

    public OfdParseResult(List<Segment> segmentList, int pageCount, int residualNonStandardCount) {
        this.segmentList = Collections.unmodifiableList(new ArrayList<Segment>(segmentList));
        this.pageCount = pageCount;
        this.residualNonStandardCount = residualNonStandardCount;
    }
}
