package com.example.healthreport.parse.ofd;

import com.example.healthreport.parse.ZipBombGuard;
import com.example.healthreport.parse.pdf.PdfSegmentParser;
import com.example.healthreport.parse.segment.BBox;
import com.example.healthreport.parse.segment.Segment;
import com.example.healthreport.parse.segment.TextNormalizationResult;
import com.example.healthreport.parse.segment.TextNormalizer;
import com.example.healthreport.parse.segment.TextSource;
import org.ofdrw.core.basicStructure.pageObj.layer.block.TextObject;
import org.ofdrw.reader.ContentExtractor;
import org.ofdrw.core.basicType.ST_Box;
import org.ofdrw.core.text.TextCode;
import org.ofdrw.reader.OFDReader;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * OFD 原子文本对象解析器。
 * <p>一个 ofdrw {@link TextObject} 对应一个 segment，不合并、不重排、不推断版面结构。</p>
 */
@Component
public class OfdSegmentParser {

    private static final double MILLIMETERS_PER_INCH = 25.4D;

    private final TextNormalizer textNormalizer;
    private final ZipBombGuard zipBombGuard;

    public OfdSegmentParser(TextNormalizer textNormalizer, ZipBombGuard zipBombGuard) {
        this.textNormalizer = textNormalizer;
        this.zipBombGuard = zipBombGuard;
    }

    /** 解析全部页面并把 OFD 左上原点毫米坐标换算为实际渲染像素坐标。 */
    public OfdParseResult parse(byte[] contentBytes, int fileIndex) throws IOException {
        zipBombGuard.inspect(contentBytes);
        List<Segment> segmentList = new ArrayList<Segment>();
        int sequence = 0;
        try (OFDReader reader = new OFDReader(new ByteArrayInputStream(contentBytes))) {
            int pageCount = reader.getNumberOfPages();
            if (pageCount <= 0) {
                throw new IOException("OFD 文档必须至少包含一页");
            }
            ContentExtractor extractor = new ContentExtractor(reader);
            for (int pageNumber = 1; pageNumber <= pageCount; pageNumber++) {
                ST_Box pageBox = reader.getPageSize(pageNumber);
                PageScale pageScale = PageScale.from(pageBox);
                List<TextObject> textObjectList = extractor.getPageTextObject(pageNumber);
                for (TextObject textObject : textObjectList) {
                    String rawText = concatenate(textObject.getTextCodes());
                    if (rawText.length() == 0) {
                        continue;
                    }
                    TextNormalizationResult normalizationResult = textNormalizer.normalize(rawText);
                    segmentList.add(new Segment(Segment.id(fileIndex, pageNumber, sequence++), rawText,
                            normalizationResult.getNormalizedText(), TextSource.NATIVE,
                            pageScale.toRenderedBox(textObject.getBoundary())));
                }
            }
            return new OfdParseResult(segmentList, pageCount);
        }
    }


    private String concatenate(List<TextCode> textCodeList) {
        StringBuilder rawTextBuilder = new StringBuilder();
        if (textCodeList != null) {
            for (TextCode textCode : textCodeList) {
                if (textCode != null && textCode.getContent() != null) {
                    rawTextBuilder.append(textCode.getContent());
                }
            }
        }
        return rawTextBuilder.toString();
    }

    /** OFD 页面毫米坐标到 300 DPI、长边不超过 3600 像素渲染图的固定换算。 */
    private static final class PageScale {

        private final double pageLeft;
        private final double pageTop;
        private final double scale;
        private final double imageWidth;
        private final double imageHeight;

        private PageScale(ST_Box pageBox, double scale) {
            this.pageLeft = pageBox.getTopLeftX();
            this.pageTop = pageBox.getTopLeftY();
            this.scale = scale;
            this.imageWidth = pageBox.getWidth() * scale;
            this.imageHeight = pageBox.getHeight() * scale;
        }

        private static PageScale from(ST_Box pageBox) throws IOException {
            if (pageBox == null || pageBox.getWidth() == null || pageBox.getHeight() == null
                    || pageBox.getTopLeftX() == null || pageBox.getTopLeftY() == null
                    || pageBox.getWidth() <= 0D || pageBox.getHeight() <= 0D) {
                throw new IOException("OFD 页面尺寸无效");
            }
            double dpiScale = (double) PdfSegmentParser.RENDER_DPI / MILLIMETERS_PER_INCH;
            double longEdge = Math.max(pageBox.getWidth(), pageBox.getHeight()) * dpiScale;
            double capScale = longEdge > PdfSegmentParser.MAX_RENDER_LONG_EDGE
                    ? (double) PdfSegmentParser.MAX_RENDER_LONG_EDGE / longEdge : 1D;
            return new PageScale(pageBox, dpiScale * capScale);
        }

        private BBox toRenderedBox(ST_Box sourceBox) {
            if (sourceBox == null || sourceBox.getTopLeftX() == null || sourceBox.getTopLeftY() == null
                    || sourceBox.getWidth() == null || sourceBox.getHeight() == null) {
                return null;
            }
            double x = (sourceBox.getTopLeftX() - pageLeft) * scale;
            double y = (sourceBox.getTopLeftY() - pageTop) * scale;
            double safeX = Math.max(0D, Math.min(x, imageWidth));
            double safeY = Math.max(0D, Math.min(y, imageHeight));
            double safeWidth = Math.max(0D, Math.min(sourceBox.getWidth() * scale, imageWidth - safeX));
            double safeHeight = Math.max(0D, Math.min(sourceBox.getHeight() * scale, imageHeight - safeY));
            return new BBox(safeX, safeY, safeWidth, safeHeight);
        }
    }
}
