package com.example.healthreport.parse;

import com.example.healthreport.infra.OcrCallException;
import com.example.healthreport.infra.PaddleOcrClient;
import com.example.healthreport.parse.ocr.OcrBboxNormalizer;
import com.example.healthreport.parse.ocr.OcrBlock;
import com.example.healthreport.parse.ocr.OcrPageSegmentFactory;
import com.example.healthreport.parse.ocr.OcrResult;
import com.example.healthreport.parse.ofd.OfdSegmentParser;
import com.example.healthreport.parse.pdf.PdfPageRenderer;
import com.example.healthreport.parse.pdf.PdfSegmentParser;
import com.example.healthreport.parse.segment.GlyphDensityGate;
import com.example.healthreport.parse.segment.Segment;
import com.example.healthreport.parse.segment.TextNormalizer;
import com.example.healthreport.parse.segment.TextSource;
import com.example.healthreport.parse.word.WordCapacityGuard;
import com.example.healthreport.parse.word.WordSegmentParser;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 文件解析驱动：路由、OCR 调用方式与每页上限。 */
class FileParseServiceTest {

    private static final long EFFECTIVE_OCR_IMAGE_BYTES = 8L * 1024L * 1024L;

    private final List<byte[]> ocrInputList = new ArrayList<byte[]>();
    private OcrCallException ocrFailure;
    private List<String> ocrLineList = Arrays.asList("血脂检查", "甘油三酯 1.85");

    @Test
    void uploadedImageBytesShouldReachOcrUnchanged() throws IOException {
        byte[] uploadedBytes = pngBytes(120, 90);

        ParsedFile parsedFile = newService().parse(0, ContentType.PNG, uploadedBytes, 1);

        // §5.6.3-④：上传图直传编码字节，本地不解码、不重编码。
        assertThat(ocrInputList).hasSize(1);
        assertThat(ocrInputList.get(0)).isEqualTo(uploadedBytes);
        assertThat(parsedFile.getPageList()).hasSize(1);
        ParsedPage page = parsedFile.getPageList().get(0);
        assertThat(page.getSegmentList()).extracting(Segment::getRawText)
                .containsExactly("血脂检查", "甘油三酯 1.85");
        assertThat(page.getSegmentList()).allSatisfy(segment -> {
            assertThat(segment.getTextSource()).isEqualTo(TextSource.OCR);
            assertThat(segment.getBbox()).isNull();
        });
        // 发 LLM-A 的是压缩图，与送 OCR 的那份不是同一批字节。
        assertThat(page.isImageRequired()).isTrue();
        assertThat(page.getJpegBytes()).isNotNull().isNotEqualTo(uploadedBytes);
    }

    @Test
    void pdfWithoutTextLayerShouldRouteEveryPageThroughOcr() throws IOException {
        byte[] pdfBytes = blankPdfBytes(2);

        ParsedFile parsedFile = newService().parse(1, ContentType.PDF, pdfBytes, 2);

        assertThat(ocrInputList).hasSize(2);
        assertThat(parsedFile.getPageList()).hasSize(2);
        for (ParsedPage page : parsedFile.getPageList()) {
            assertThat(page.getSegmentList()).hasSize(2);
            assertThat(page.isImageRequired()).isTrue();
            assertThat(page.getJpegBytes()).isNotNull();
        }
        // 页码来自渲染顺序，segmentId 必须与所属页一致。
        assertThat(parsedFile.getPageList().get(1).getSegmentList().get(0).getSegmentId())
                .isEqualTo("f1-p2-s0");
    }

    @Test
    void ocrPageOverBlockLimitShouldFailWholeTaskWithoutTruncation() throws IOException {
        List<String> tooManyLines = new ArrayList<String>();
        for (int index = 0; index <= GlyphDensityGate.MAX_SEGMENTS_PER_PAGE; index++) {
            tooManyLines.add("第" + index + "行");
        }
        ocrLineList = tooManyLines;
        byte[] uploadedBytes = pngBytes(80, 60);

        assertThatThrownBy(() -> newService().parse(0, ContentType.PNG, uploadedBytes, 1))
                .isInstanceOf(HealthReportException.class)
                .satisfies(thrown -> assertThat(((HealthReportException) thrown).getFailCode())
                        .isEqualTo(FailCode.UNREADABLE));
    }

    @Test
    void exactlyFourHundredBlocksShouldPass() throws IOException {
        List<String> lineList = new ArrayList<String>();
        for (int index = 0; index < GlyphDensityGate.MAX_SEGMENTS_PER_PAGE; index++) {
            lineList.add("第" + index + "行");
        }
        ocrLineList = lineList;

        ParsedFile parsedFile = newService().parse(0, ContentType.PNG, pngBytes(80, 60), 1);

        assertThat(parsedFile.getPageList().get(0).getSegmentList())
                .hasSize(GlyphDensityGate.MAX_SEGMENTS_PER_PAGE);
    }


    @Test
    void corruptFileShouldBeUnreadableNotServerError() {
        byte[] corruptPdf = "%PDF-1.4 然后就全是垃圾字节".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        // 损坏文件是【用户的问题】：必须 400/UNREADABLE。
        // 逃成 SERVER_ERROR 会告诉用户「服务端出错可重试」，而重试必然再失败。
        assertThatThrownBy(() -> newService().parse(0, ContentType.PDF, corruptPdf, 1))
                .isInstanceOf(HealthReportException.class)
                .satisfies(thrown -> assertThat(((HealthReportException) thrown).getFailCode())
                        .isEqualTo(FailCode.UNREADABLE));
    }

    @Test
    void ocrFailureInsideWordMustStayServerErrorNotUnreadable() throws Exception {
        // 必须走 Word：只有它的解析调用【内部会调 OCR】且被 asUnreadable 包着，
        // 图片路径的 recognizePage 在包裹范围之外，用它测等于什么也没测。
        ocrFailure = new OcrCallException(FailCode.SERVER_ERROR, 500, 12L);
        byte[] docxWithImage = SyntheticFileFactory.docx(1, 1);

        // OCR 挂了是【我们下游的问题】，不是用户文件坏了。被吞成 UNREADABLE
        // 会误导用户去换文件，而且把 reanalyzable 一起丢掉（R43b5）。
        assertThatThrownBy(() -> newService().parse(0, ContentType.DOCX, docxWithImage, 1))
                .isInstanceOf(OcrCallException.class);
    }

    @Test
    void segmentBeyondParsedPageCountMustFailInsteadOfBeingDropped() {
        List<Segment> segmentList = Arrays.asList(
                new Segment("f0-p1-s0", "第一页", "第一页", TextSource.NATIVE, null),
                new Segment("f0-p3-s0", "第三页", "第三页", TextSource.NATIVE, null));

        // 解析器说只有 2 页，却给出了第 3 页的 segment：页数与编址自相矛盾。
        // 按页数建页会把第 3 页那块【静默丢掉】——报告少一页内容而无人知晓。
        assertThatThrownBy(() -> FileParseService.pagesFromSegments(segmentList, 2))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pagesFromSegmentsShouldKeepEmptyPagesAndPreserveOrder() {
        List<Segment> segmentList = Arrays.asList(
                new Segment("f0-p1-s0", "甲", "甲", TextSource.NATIVE, null),
                new Segment("f0-p3-s0", "丙", "丙", TextSource.NATIVE, null));

        List<ParsedPage> pageList = FileParseService.pagesFromSegments(segmentList, 3);

        // 第 2 页没有文字也必须占位，否则页码与真实页对不上。
        assertThat(pageList).hasSize(3);
        assertThat(pageList.get(1).getSegmentList()).isEmpty();
        assertThat(pageList.get(2).getSegmentList()).extracting(Segment::getRawText)
                .containsExactly("丙");
    }

    private FileParseService newService() {
        TextNormalizer textNormalizer = new TextNormalizer();
        ImageContentInspector imageContentInspector = new ImageContentInspector();
        ZipBombGuard zipBombGuard = new ZipBombGuard();
        PaddleOcrClient ocrClient = new PaddleOcrClient() {
            @Override
            public OcrResult recognize(byte[] encodedImageBytes) {
                if (ocrFailure != null) {
                    throw ocrFailure;
                }
                ocrInputList.add(Arrays.copyOf(encodedImageBytes, encodedImageBytes.length));
                List<OcrBlock> blockList = new ArrayList<OcrBlock>(ocrLineList.size());
                for (String line : ocrLineList) {
                    blockList.add(new OcrBlock(line, null));
                }
                return new OcrResult(blockList, null, null);
            }
        };
        return new FileParseService(
                new PdfTextLayerChecker(),
                new PdfSegmentParser(textNormalizer, new GlyphDensityGate()),
                new PdfPageRenderer(),
                new OfdSegmentParser(textNormalizer, zipBombGuard),
                new WordSegmentParser(textNormalizer, imageContentInspector, ocrClient,
                        new WordCapacityGuard(), zipBombGuard, EFFECTIVE_OCR_IMAGE_BYTES),
                new RenderedPageImageProcessor(new OcrImageEncoder(EFFECTIVE_OCR_IMAGE_BYTES),
                        new ExtractionImageCompressor()),
                new ExtractionImageCompressor(),
                imageContentInspector,
                new OcrPageSegmentFactory(textNormalizer,
                        new OcrBboxNormalizer(true, true, new ExifOrientationTransform())),
                ocrClient);
    }

    private byte[] pngBytes(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        try {
            return ImageEncodingSupport.encodePng(image);
        } finally {
            image.flush();
        }
    }

    /** 空白页没有文本层，会被路由到 OCR；页面开小一点避免 300DPI 渲染出巨图。 */
    private byte[] blankPdfBytes(int pageCount) throws IOException {
        PDDocument document = new PDDocument();
        try {
            for (int index = 0; index < pageCount; index++) {
                document.addPage(new PDPage(new PDRectangle(120F, 160F)));
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        } finally {
            document.close();
        }
    }
}
