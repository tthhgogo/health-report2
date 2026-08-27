package com.example.healthreport.parse.pdf;

import com.example.healthreport.parse.ExifOrientationTransform;
import com.example.healthreport.parse.ocr.OcrBboxNormalizer;
import com.example.healthreport.parse.ocr.OcrBlock;
import com.example.healthreport.parse.ocr.OcrPageSegmentFactory;
import com.example.healthreport.parse.ocr.OcrResult;
import com.example.healthreport.parse.segment.BBox;
import com.example.healthreport.parse.segment.GlyphDensityGate;
import com.example.healthreport.parse.segment.Segment;
import com.example.healthreport.parse.segment.TextNormalizer;
import com.example.healthreport.parse.segment.TextSource;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;

import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** PDF Tj/TJ 原子粒度、密度闸、坐标与确定性测试。 */
class PdfSegmentParserTest {

    private static final float TEXT_X_POINTS = 72F;
    private static final float TEXT_BASELINE_Y_POINTS = 720F;
    private static final float FONT_SIZE_POINTS = 10F;
    private static final double COORDINATE_TOLERANCE = 0.05D;

    static {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void shouldKeepEachDisplayOperationAndMatchOcrTopLeftPixelCoordinates() throws Exception {
        GlyphDensityGate densityGate = new GlyphDensityGate();
        PdfSegmentParser parser = new PdfSegmentParser(new TextNormalizer(), densityGate);
        try (PDDocument document = documentWithOperations(3)) {
            PdfParseResult first = parser.parse(document, 0);
            PdfParseResult second = parser.parse(document, 0);

            assertThat(first.isOcrRequired()).isFalse();
            assertThat(first.getSegmentList()).hasSize(3);
            assertThat(first.getSegmentList()).extracting(Segment::getRawText)
                    .containsExactly("A", "B", "C");
            assertThat(ids(first.getSegmentList())).containsExactlyElementsOf(ids(second.getSegmentList()));
            BBox expectedBox = expectedUnrotatedBox();
            BBox pdfBox = first.getSegmentList().get(0).getBbox();
            assertBoxCloseTo(pdfBox, expectedBox);

            OcrResult ocrResult = new OcrResult(
                    Collections.singletonList(new OcrBlock("A", expectedBox)),
                    renderedWidth(0), renderedHeight(0));
            OcrBboxNormalizer bboxNormalizer = new OcrBboxNormalizer(true, true,
                    new ExifOrientationTransform());
            Segment ocrSegment = new OcrPageSegmentFactory(new TextNormalizer(), bboxNormalizer)
                    .create(ocrResult, 0, 1, 0, 1).getSegmentList().get(0);
            assertThat(ocrSegment.getTextSource()).isEqualTo(TextSource.OCR);
            assertThat(ocrSegment.getBbox()).isSameAs(expectedBox);
            assertBoxCloseTo(pdfBox, ocrSegment.getBbox());

            BufferedImage renderedImage = new PdfPageRenderer().render(document, 0);
            try {
                assertThat(renderedImage.getWidth()).isEqualTo(renderedWidth(0));
                assertThat(renderedImage.getHeight()).isEqualTo(renderedHeight(0));
            } finally {
                renderedImage.flush();
            }
        }
    }

    @Test
    void shouldMapNinetyAndTwoHundredSeventyDegreePagesToRenderedPixels() throws Exception {
        PdfSegmentParser parser = new PdfSegmentParser(new TextNormalizer(), new GlyphDensityGate());
        BBox unrotatedBox = expectedUnrotatedBox();
        try (PDDocument ninety = documentWithOperations(1, 90);
             PDDocument twoHundredSeventy = documentWithOperations(1, 270)) {
            BBox ninetyBox = parser.parse(ninety, 0).getSegmentList().get(0).getBbox();
            BBox expectedNinety = new BBox(
                    renderedHeightPixels(0) - unrotatedBox.getY() - unrotatedBox.getHeight(),
                    unrotatedBox.getX(), unrotatedBox.getHeight(), unrotatedBox.getWidth());
            assertBoxCloseTo(ninetyBox, expectedNinety);

            BBox twoHundredSeventyBox = parser.parse(twoHundredSeventy, 0)
                    .getSegmentList().get(0).getBbox();
            BBox expectedTwoHundredSeventy = new BBox(unrotatedBox.getY(),
                    renderedWidthPixels(0) - unrotatedBox.getX() - unrotatedBox.getWidth(),
                    unrotatedBox.getHeight(), unrotatedBox.getWidth());
            assertBoxCloseTo(twoHundredSeventyBox, expectedTwoHundredSeventy);

            assertRenderedDimensions(ninety, 0, renderedWidth(90), renderedHeight(90));
            assertRenderedDimensions(twoHundredSeventy, 0, renderedWidth(270), renderedHeight(270));
        }
    }

    @Test
    void shouldAllowExactBoundaryAndSwitchWholeFileToOcrAboveIt() throws Exception {
        GlyphDensityGate densityGate = new GlyphDensityGate();
        PdfSegmentParser parser = new PdfSegmentParser(new TextNormalizer(), densityGate);
        try (PDDocument exact = documentWithOperations(400);
             PDDocument exceeded = documentWithOperations(401)) {
            assertThat(parser.parse(exact, 0).isOcrRequired()).isFalse();
            PdfParseResult result = parser.parse(exceeded, 0);
            assertThat(result.isOcrRequired()).isTrue();
            assertThat(result.getSegmentList()).isEmpty();
            assertThat(densityGate.glyphLevelPdfCount()).isEqualTo(1L);
        }
    }

    @Test
    void shouldMapRenderFailureToUnreadableInsteadOfIllegalState() {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(PDRectangle.A4));

            assertThatThrownBy(() -> new PdfPageRenderer().render(document, 2))
                    .isInstanceOfSatisfying(HealthReportException.class,
                            exception -> assertThat(exception.getFailCode()).isEqualTo(FailCode.UNREADABLE));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    /**
     * Type 3 字体的字形过程本身是内容流，其中可以合法地再次出现 Tj。
     * 早先 beginUnit 一遇嵌套就抛 IllegalStateException，会让这类 PDF 整份失败，
     * 且落成 SERVER_ERROR 被误标可重试。这里断言：不抛异常，且 segment 数等于
     * 最外层绘制操作数（内层字形并入外层，不额外产出）。
     */
    @Test
    void type3NestedTextOperationShouldNotFailAndShouldCountOnlyOuterUnits() throws Exception {
        PdfSegmentParser parser = new PdfSegmentParser(new TextNormalizer(), new GlyphDensityGate());
        try (PDDocument document = documentWithType3NestedOperations(2)) {
            PdfParseResult result = parser.parse(document, 0);

            assertThat(result.isOcrRequired()).isFalse();
            assertThat(result.getSegmentList()).hasSize(2);
        }
    }

    /** 构造一份使用 Type 3 字体的 PDF，字形过程内再嵌一次 BT/Tj/ET。 */
    private PDDocument documentWithType3NestedOperations(int operationCount) throws Exception {
        PDDocument document = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);

        COSStream glyphStream = document.getDocument().createCOSStream();
        try (OutputStream glyphOut = glyphStream.createOutputStream()) {
            glyphOut.write(("600 0 d0\nBT /Helv 10 Tf 0 0 Td (x) Tj ET\n")
                    .getBytes(StandardCharsets.US_ASCII));
        }
        COSDictionary charProcs = new COSDictionary();
        charProcs.setItem(COSName.getPDFName("square"), glyphStream);

        COSArray differences = new COSArray();
        differences.add(COSName.getPDFName("0"));
        differences.add(COSName.getPDFName("square"));
        COSDictionary encoding = new COSDictionary();
        encoding.setItem(COSName.TYPE, COSName.ENCODING);
        encoding.setItem(COSName.DIFFERENCES, differences);

        PDResources glyphResources = new PDResources();
        glyphResources.put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA);

        COSArray fontMatrix = new COSArray();
        for (double value : new double[]{0.001D, 0D, 0D, 0.001D, 0D, 0D}) {
            fontMatrix.add(new org.apache.pdfbox.cos.COSFloat((float) value));
        }
        COSArray fontBBox = new COSArray();
        for (int value : new int[]{0, 0, 750, 750}) {
            fontBBox.add(org.apache.pdfbox.cos.COSInteger.get(value));
        }
        COSArray widths = new COSArray();
        widths.add(org.apache.pdfbox.cos.COSInteger.get(600));

        COSDictionary type3 = new COSDictionary();
        type3.setItem(COSName.TYPE, COSName.FONT);
        type3.setItem(COSName.SUBTYPE, COSName.getPDFName("Type3"));
        type3.setItem(COSName.getPDFName("FontBBox"), fontBBox);
        type3.setItem(COSName.getPDFName("FontMatrix"), fontMatrix);
        type3.setItem(COSName.getPDFName("CharProcs"), charProcs);
        type3.setItem(COSName.ENCODING, encoding);
        type3.setInt(COSName.FIRST_CHAR, 0);
        type3.setInt(COSName.LAST_CHAR, 0);
        type3.setItem(COSName.WIDTHS, widths);
        type3.setItem(COSName.RESOURCES, glyphResources);

        PDResources pageResources = new PDResources();
        pageResources.getCOSObject().setItem(COSName.FONT, new COSDictionary());
        COSDictionary pageFonts = (COSDictionary) pageResources.getCOSObject().getDictionaryObject(COSName.FONT);
        pageFonts.setItem(COSName.getPDFName("T3"), type3);
        page.setResources(pageResources);

        StringBuilder content = new StringBuilder();
        content.append("BT /T3 12 Tf 72 720 Td\n");
        for (int index = 0; index < operationCount; index++) {
            content.append("<00> Tj\n");
        }
        content.append("ET\n");
        COSStream pageStream = document.getDocument().createCOSStream();
        try (OutputStream pageOut = pageStream.createOutputStream()) {
            pageOut.write(content.toString().getBytes(StandardCharsets.US_ASCII));
        }
        page.getCOSObject().setItem(COSName.CONTENTS, pageStream);
        return document;
    }

    private PDDocument documentWithOperations(int operationCount) throws Exception {
        return documentWithOperations(operationCount, 0);
    }

    private PDDocument documentWithOperations(int operationCount, int rotation) throws Exception {
        PDDocument document = new PDDocument();
        PDPage page = new PDPage(PDRectangle.A4);
        page.setRotation(rotation);
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.beginText();
            content.setFont(PDType1Font.HELVETICA, FONT_SIZE_POINTS);
            content.newLineAtOffset(TEXT_X_POINTS, TEXT_BASELINE_Y_POINTS);
            for (int index = 0; index < operationCount; index++) {
                content.showText(String.valueOf((char) ('A' + index % 26)));
            }
            content.endText();
        }
        return document;
    }

    private BBox expectedUnrotatedBox() throws Exception {
        double scale = (double) PdfSegmentParser.RENDER_DPI / 72D;
        double widthPoints = PDType1Font.HELVETICA.getStringWidth("A") / 1000D * FONT_SIZE_POINTS;
        return new BBox(TEXT_X_POINTS * scale,
                (PDRectangle.A4.getHeight() - TEXT_BASELINE_Y_POINTS - FONT_SIZE_POINTS) * scale,
                widthPoints * scale, FONT_SIZE_POINTS * scale);
    }

    private int renderedWidth(int rotation) {
        return (int) Math.floor(renderedWidthPixels(rotation));
    }

    private double renderedWidthPixels(int rotation) {
        boolean swapsDimensions = rotation == 90 || rotation == 270;
        double widthPoints = swapsDimensions ? PDRectangle.A4.getHeight() : PDRectangle.A4.getWidth();
        return widthPoints * PdfSegmentParser.RENDER_DPI / 72D;
    }

    private int renderedHeight(int rotation) {
        return (int) Math.floor(renderedHeightPixels(rotation));
    }

    private double renderedHeightPixels(int rotation) {
        boolean swapsDimensions = rotation == 90 || rotation == 270;
        double heightPoints = swapsDimensions ? PDRectangle.A4.getWidth() : PDRectangle.A4.getHeight();
        return heightPoints * PdfSegmentParser.RENDER_DPI / 72D;
    }

    private void assertRenderedDimensions(PDDocument document, int pageIndex,
                                          int expectedWidth, int expectedHeight) {
        BufferedImage renderedImage = new PdfPageRenderer().render(document, pageIndex);
        try {
            assertThat(renderedImage.getWidth()).isEqualTo(expectedWidth);
            assertThat(renderedImage.getHeight()).isEqualTo(expectedHeight);
        } finally {
            renderedImage.flush();
        }
    }

    private void assertBoxCloseTo(BBox actual, BBox expected) {
        assertThat(actual.getX()).isCloseTo(expected.getX(), within(COORDINATE_TOLERANCE));
        assertThat(actual.getY()).isCloseTo(expected.getY(), within(COORDINATE_TOLERANCE));
        assertThat(actual.getWidth()).isCloseTo(expected.getWidth(), within(COORDINATE_TOLERANCE));
        assertThat(actual.getHeight()).isCloseTo(expected.getHeight(), within(COORDINATE_TOLERANCE));
    }

    private List<String> ids(List<Segment> segmentList) {
        return segmentList.stream().map(Segment::getSegmentId).collect(Collectors.toList());
    }
}
