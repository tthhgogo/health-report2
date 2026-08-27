package com.example.healthreport.parse;

import com.example.healthreport.infra.PaddleOcrClient;
import com.example.healthreport.parse.ocr.OcrBlock;
import com.example.healthreport.parse.ocr.OcrResult;
import com.example.healthreport.parse.segment.BBox;
import com.example.healthreport.parse.segment.Segment;
import com.example.healthreport.parse.segment.TextNormalizer;
import com.example.healthreport.parse.segment.TextSource;
import com.example.healthreport.parse.word.WordCapacityGuard;
import com.example.healthreport.parse.word.WordCapacityResult;
import com.example.healthreport.parse.word.WordParseResult;
import com.example.healthreport.parse.word.WordSegmentParser;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Word 源码顺序、图片 OCR、真实容量和故障归属测试。 */
class WordSegmentParserTest {

    @Test
    void shouldKeepParagraphCellsAndOcrBlocksInOrderWithoutWordImages() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        PaddleOcrClient fake = client(imageBytes -> {
            calls.incrementAndGet();
            return new OcrResult(Arrays.asList(
                    new OcrBlock("ocr-a", new BBox(1D, 2D, 3D, 4D)),
                    new OcrBlock("ocr-b", null)), 300, 300);
        });
        WordSegmentParser parser = parser(fake, 4L * 1024L * 1024L);

        WordParseResult result = parser.parse(docxWithParagraphTableAndImage(), ContentType.DOCX, 0);

        assertThat(result.getSegmentList()).extracting(Segment::getRawText)
                .containsExactly("paragraph", "cell-a", "cell-b", "ocr-a", "ocr-b");
        assertThat(result.getSegmentList()).extracting(Segment::getTextSource)
                .containsExactly(TextSource.NATIVE, TextSource.NATIVE, TextSource.NATIVE,
                        TextSource.OCR, TextSource.OCR);
        assertThat(result.getSegmentList()).allSatisfy(segment -> assertThat(segment.getBbox()).isNull());
        assertThat(result.isImageRequired()).isFalse();
        assertThat(result.getJpegBytes()).isNull();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void shouldAllowExactCapacityBoundary() {
        List<Segment> segmentList = new ArrayList<Segment>(1200);
        for (int index = 0; index < 1200; index++) {
            int page = index / 40 + 1;
            segmentList.add(new Segment(Segment.id(0, page, index), "x", "x", TextSource.NATIVE, null));
        }

        WordCapacityResult result = new WordCapacityGuard().check(segmentList, 30);

        assertThat(result.getExactSegmentCount()).isEqualTo(1200);
        assertThat(result.getEmbeddedImageCount()).isEqualTo(30);
        assertThat(result.getExactWordPages()).isEqualTo(30);

        segmentList.add(new Segment(Segment.id(0, 31, 1200), "x", "x", TextSource.NATIVE, null));
        assertThatThrownBy(() -> new WordCapacityGuard().check(segmentList, 30))
                .isInstanceOfSatisfying(HealthReportException.class,
                        exception -> assertThat(exception.getFailCode())
                                .isEqualTo(FailCode.PAGE_LIMIT_EXCEEDED));
    }

    @Test
    void shouldPrecheckThenRejectOnlyAfterAllImageOcrBlocksAreKnown() throws Exception {
        byte[] contentBytes = SyntheticFileFactory.docx(1200, 10);
        ZipBombGuard zipBombGuard = new ZipBombGuard();
        WordDocumentInspector inspector = new WordDocumentInspector(new ImageContentInspector(), zipBombGuard);
        assertThat(new CapacityPrecheckService(inspector, zipBombGuard)
                .precheckPages(contentBytes, ContentType.DOCX)).isEqualTo(30);
        AtomicInteger ocrCalls = new AtomicInteger();
        PaddleOcrClient fake = client(imageBytes -> {
            ocrCalls.incrementAndGet();
            return new OcrResult(Collections.singletonList(new OcrBlock("ocr", null)), 300, 300);
        });

        assertThatThrownBy(() -> parser(fake, 4L * 1024L * 1024L)
                .parse(contentBytes, ContentType.DOCX, 0))
                .isInstanceOfSatisfying(HealthReportException.class,
                        exception -> assertThat(exception.getFailCode())
                                .isEqualTo(FailCode.PAGE_LIMIT_EXCEEDED));
        assertThat(ocrCalls.get()).isEqualTo(10);
    }

    @Test
    void shouldKeepOcrFailureSeparateFromCapacityAndRejectOversizeImage() throws Exception {
        byte[] contentBytes = SyntheticFileFactory.docx(0, 1);
        RuntimeException remoteFailure = new RuntimeException("synthetic integration failure");
        PaddleOcrClient failing = client(imageBytes -> {
            throw remoteFailure;
        });
        assertThatThrownBy(() -> parser(failing, 4L * 1024L * 1024L)
                .parse(contentBytes, ContentType.DOCX, 0)).isSameAs(remoteFailure);

        AtomicInteger calls = new AtomicInteger();
        PaddleOcrClient shouldNotRun = client(imageBytes -> {
            calls.incrementAndGet();
            return new OcrResult(Collections.<OcrBlock>emptyList(), 300, 300);
        });
        assertThatThrownBy(() -> parser(shouldNotRun, 8L)
                .parse(contentBytes, ContentType.DOCX, 0))
                .isInstanceOfSatisfying(ImageTooLargeException.class,
                        exception -> assertThat(exception.getFailCode())
                                .isEqualTo(FailCode.IMAGE_TOO_LARGE));
        assertThat(calls.get()).isZero();
    }

    private WordSegmentParser parser(PaddleOcrClient client, long effectiveBytes) {
        return new WordSegmentParser(new TextNormalizer(), new ImageContentInspector(), client,
                new WordCapacityGuard(), new ZipBombGuard(), effectiveBytes);
    }

    private PaddleOcrClient client(final OcrCall ocrCall) {
        return new PaddleOcrClient() {
            @Override
            public OcrResult recognize(byte[] encodedImageBytes) {
                return ocrCall.recognize(encodedImageBytes);
            }
        };
    }

    private interface OcrCall {
        OcrResult recognize(byte[] encodedImageBytes);
    }

    private byte[] docxWithParagraphTableAndImage() throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("paragraph");
            XWPFTable table = document.createTable(1, 2);
            table.getRow(0).getCell(0).setText("cell-a");
            table.getRow(0).getCell(1).setText("cell-b");
            byte[] imageBytes = SyntheticFileFactory.image("png", 300, 300);
            document.createParagraph().createRun().addPicture(new java.io.ByteArrayInputStream(imageBytes),
                    org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG, "synthetic.png",
                    org.apache.poi.util.Units.toEMU(300), org.apache.poi.util.Units.toEMU(300));
            document.write(output);
            return output.toByteArray();
        }
    }
}
