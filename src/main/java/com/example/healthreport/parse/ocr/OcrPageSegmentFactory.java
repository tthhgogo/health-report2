package com.example.healthreport.parse.ocr;

import com.example.healthreport.parse.segment.BBox;
import com.example.healthreport.parse.segment.Segment;
import com.example.healthreport.parse.segment.TextNormalizationResult;
import com.example.healthreport.parse.segment.TextNormalizer;
import com.example.healthreport.parse.segment.TextSource;

import java.util.ArrayList;
import java.util.List;

/** OCR 原子块到 Segment 的确定性转换，不拼行、不按坐标重排。 */
public class OcrPageSegmentFactory {

    private final TextNormalizer textNormalizer;
    private final OcrBboxNormalizer bboxNormalizer;

    public OcrPageSegmentFactory(TextNormalizer textNormalizer, OcrBboxNormalizer bboxNormalizer) {
        this.textNormalizer = textNormalizer;
        this.bboxNormalizer = bboxNormalizer;
    }

    /** 严格保持 OCR 响应块顺序，并保证 OCR 左上原点坐标不发生额外 Y 翻转。 */
    public OcrPageSegmentResult create(OcrResult ocrResult, int fileIndex, int pageNumber,
                                       int firstSequence, int orientation) {
        if (ocrResult == null || firstSequence < 0) {
            throw new IllegalArgumentException("OCR 分段参数无效");
        }
        List<Segment> segmentList = new ArrayList<Segment>(ocrResult.getBlockList().size());
        int sequence = firstSequence;
        for (OcrBlock block : ocrResult.getBlockList()) {
            if (block.getRawText().length() == 0) {
                continue;
            }
            TextNormalizationResult normalizationResult = textNormalizer.normalize(block.getRawText());
            BBox normalizedBox = bboxNormalizer.normalize(block.getBbox(), orientation, ocrResult);
            segmentList.add(new Segment(Segment.id(fileIndex, pageNumber, sequence++), block.getRawText(),
                    normalizationResult.getNormalizedText(), TextSource.OCR, normalizedBox));
        }
        return new OcrPageSegmentResult(segmentList);
    }
}
