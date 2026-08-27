package com.example.healthreport.parse.pdf;

import com.example.healthreport.parse.segment.Segment;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** PDF 原生绘制单元解析结果；命中密度闸时只返回整文件 OCR 路由标志。 */
@Getter
public final class PdfParseResult {

    private final List<Segment> segmentList;
    private final boolean ocrRequired;
    private final int pageCount;
    private final int residualNonStandardCount;

    public PdfParseResult(List<Segment> segmentList, boolean ocrRequired,
                          int pageCount, int residualNonStandardCount) {
        this.segmentList = Collections.unmodifiableList(new ArrayList<Segment>(segmentList));
        this.ocrRequired = ocrRequired;
        this.pageCount = pageCount;
        this.residualNonStandardCount = residualNonStandardCount;
    }
}
