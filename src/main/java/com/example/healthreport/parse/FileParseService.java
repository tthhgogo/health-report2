package com.example.healthreport.parse;

import com.example.healthreport.infra.OcrCallException;
import com.example.healthreport.infra.PaddleOcrClient;
import com.example.healthreport.parse.ocr.OcrPageSegmentFactory;
import com.example.healthreport.parse.ocr.OcrPageSegmentResult;
import com.example.healthreport.parse.ocr.OcrResult;
import com.example.healthreport.parse.ofd.OfdParseResult;
import com.example.healthreport.parse.ofd.OfdSegmentParser;
import com.example.healthreport.parse.pdf.PdfPageRenderer;
import com.example.healthreport.parse.pdf.PdfParseResult;
import com.example.healthreport.parse.pdf.PdfSegmentParser;
import com.example.healthreport.parse.segment.GlyphDensityGate;
import com.example.healthreport.parse.segment.Segment;
import com.example.healthreport.parse.word.WordParseResult;
import com.example.healthreport.parse.word.WordSegmentParser;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import com.example.healthreport.support.SensitiveLog;
import org.apache.pdfbox.pdmodel.PDDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 单文件解析驱动：把上传字节变成 {@link ParsedFile}。
 *
 * <p>本类是「渲染 → 判原生还是 OCR → 调 OCR → 组装页」这条轴，
 * 是 {@link PaddleOcrClient} 在 PDF 与图片路径上的唯一调用方。
 * 产出交给 {@link ParseOrchestrator} 做页数预算与零块裁决。</p>
 *
 * <p><b>本类不做任何语义判断</b>：走原生还是 OCR 由文本层阈值和密度闸决定，
 * 两者都是可穷举输入的确定性判断（`AGENTS.md` §3）。</p>
 */
@Slf4j
@Service
public class FileParseService {

    /**
     * 传给 OCR 分段工厂的 EXIF Orientation。
     * <p>PDF 渲染阶段已按页面 {@code /Rotate} 归一化，本来就该是恒等值 1；
     * 而当前 OCR 协议不回传坐标，{@code bbox} 恒为 null，这个值在任何分支下都不参与计算。
     * 写成常量而不是省略，是为了换成回传坐标的 OCR 接口时能一眼看到该从哪里取真实方向。</p>
     */
    static final int NORMALIZED_ORIENTATION = 1;

    private final PdfTextLayerChecker pdfTextLayerChecker;
    private final PdfSegmentParser pdfSegmentParser;
    private final PdfPageRenderer pdfPageRenderer;
    private final OfdSegmentParser ofdSegmentParser;
    private final WordSegmentParser wordSegmentParser;
    private final RenderedPageImageProcessor renderedPageImageProcessor;
    private final ExtractionImageCompressor extractionImageCompressor;
    private final ImageContentInspector imageContentInspector;
    private final OcrPageSegmentFactory ocrPageSegmentFactory;
    private final PaddleOcrClient paddleOcrClient;

    public FileParseService(PdfTextLayerChecker pdfTextLayerChecker,
                            PdfSegmentParser pdfSegmentParser,
                            PdfPageRenderer pdfPageRenderer,
                            OfdSegmentParser ofdSegmentParser,
                            WordSegmentParser wordSegmentParser,
                            RenderedPageImageProcessor renderedPageImageProcessor,
                            ExtractionImageCompressor extractionImageCompressor,
                            ImageContentInspector imageContentInspector,
                            OcrPageSegmentFactory ocrPageSegmentFactory,
                            PaddleOcrClient paddleOcrClient) {
        this.pdfTextLayerChecker = pdfTextLayerChecker;
        this.pdfSegmentParser = pdfSegmentParser;
        this.pdfPageRenderer = pdfPageRenderer;
        this.ofdSegmentParser = ofdSegmentParser;
        this.wordSegmentParser = wordSegmentParser;
        this.renderedPageImageProcessor = renderedPageImageProcessor;
        this.extractionImageCompressor = extractionImageCompressor;
        this.imageContentInspector = imageContentInspector;
        this.ocrPageSegmentFactory = ocrPageSegmentFactory;
        this.paddleOcrClient = paddleOcrClient;
    }

    /**
     * 解析一个已下载到内存的文件。
     *
     * @param precheckPages 上传期记录的等效页数；Word 之外必须与实际页数一致
     */
    public ParsedFile parse(int fileIndex, ContentType contentType, byte[] contentBytes,
                            int precheckPages) {
        if (contentType == null || contentBytes == null || contentBytes.length == 0) {
            throw new IllegalArgumentException("文件解析入参无效");
        }
        switch (contentType) {
            case PDF:
                return parsePdf(fileIndex, contentBytes, precheckPages);
            case OFD:
                return parseOfd(fileIndex, contentBytes, precheckPages);
            case JPG:
            case PNG:
                return parseImage(fileIndex, contentType, contentBytes);
            case DOC:
            case DOCX:
            default:
                return parseWord(fileIndex, contentType, contentBytes);
        }
    }

    /**
     * 把第三方解析库对损坏输入抛出的异常统一映射为 {@link FailCode#UNREADABLE}。
     *
     * <p><b>为什么必须映射</b>：PDFBox 解析损坏 xref、POI 读坏 OLE2、ofdrw 读坏 ZIP 时抛的是
     * <b>unchecked</b> 异常。不接住它们就会逃到 Worker 的通用分支变成
     * {@code SERVER_ERROR}（500、{@code reanalyzable=true}）——等于告诉用户「服务端出错，
     * 可以重试」，而重试必然再失败，因为坏的是他的文件。正确答案是 400 / UNREADABLE。</p>
     *
     * <p><b>两类异常必须原样上抛</b>：
     * {@link HealthReportException} 自带确定性失败码（如 Word 超限的 {@code PAGE_LIMIT_EXCEEDED}、
     * 编码超限的 {@code IMAGE_TOO_LARGE}）；{@link OcrCallException} 是<b>我们的下游挂了</b>，
     * 不是用户文件的问题，必须保持 {@code SERVER_ERROR} 并允许重解析。</p>
     *
     * <p>代价：本方法包住的调用里若有我们自己的 bug（比如 NPE），也会被记成 UNREADABLE。
     * 因此包裹范围只限第三方解析入口这一行，不覆盖后续的组装逻辑。</p>
     */
    private RuntimeException asUnreadable(Exception exception) {
        if (exception instanceof HealthReportException) {
            return (HealthReportException) exception;
        }
        if (exception instanceof OcrCallException) {
            return (OcrCallException) exception;
        }
        return new HealthReportException(FailCode.UNREADABLE, 400, exception);
    }

    /**
     * PDF：先判文本层，再判密度闸，任一不通过整文件改走 OCR。
     * <p>无论走哪条路都要逐页渲染——原生路径也需要页面图发给 LLM-A（§6.2.1）。</p>
     */
    private ParsedFile parsePdf(int fileIndex, byte[] contentBytes, int precheckPages) {
        final PDDocument document;
        try {
            document = PDDocument.load(contentBytes);
        } catch (IOException | RuntimeException exception) {
            throw asUnreadable(exception);
        }
        try {
            List<Segment> nativeSegmentList = null;
            try {
                if (pdfTextLayerChecker.hasUsableTextLayer(document)) {
                    PdfParseResult parseResult = pdfSegmentParser.parse(document, fileIndex);
                    // 密度闸命中说明是逐字形绘制，整文件改走 OCR，不退化成字形 segment。
                    if (!parseResult.isOcrRequired()) {
                        nativeSegmentList = parseResult.getSegmentList();
                    }
                }
            } catch (IOException | RuntimeException exception) {
                throw asUnreadable(exception);
            }
            int pageCount = document.getNumberOfPages();
            List<ParsedPage> pageList = new ArrayList<ParsedPage>(pageCount);
            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                int page = pageIndex + 1;
                BufferedImage renderedImage = pdfPageRenderer.render(document, pageIndex);
                PageImageArtifacts artifacts = renderedPageImageProcessor.processAndRelease(renderedImage);
                List<Segment> pageSegmentList = nativeSegmentList == null
                        ? recognizePage(artifacts.getOcrEncodedImageBytes(), fileIndex, page)
                        : segmentsOfPage(nativeSegmentList, page);
                pageList.add(new ParsedPage(page, pageSegmentList,
                        artifacts.getExtractionImage().getJpegBytes(), true));
            }
            ParsedFile parsedFile = new ParsedFile(fileIndex, ContentType.PDF, precheckPages, pageList);
            logParsed(parsedFile, nativeSegmentList == null ? "OCR" : "PDF原生文本层");
            return parsedFile;
        } finally {
            closeQuietly(document);
        }
    }

    /** 关闭失败不改变已经产生的失败码，也不覆盖正在抛出的异常。 */
    private void closeQuietly(PDDocument document) {
        try {
            document.close();
        } catch (IOException | RuntimeException exception) {
            log.warn("PDF 文档关闭失败", exception);
        }
    }

    /**
     * OFD：只走原生文本对象。
     * <p><b>没有 OFD 页面渲染器</b>，所以扫描版 OFD 既拿不到识别块、也拿不到页面图：
     * 它会以零 segment 落到 {@link ParseOrchestrator} 的 UNREADABLE 裁决上，
     * 这是显式失败而不是静默降级。补渲染器之前不要给 OFD 加 OCR 分支——
     * 加了就得先本地整幅解码，那是 §5.6.3 明确拒绝的。</p>
     */
    private ParsedFile parseOfd(int fileIndex, byte[] contentBytes, int precheckPages) {
        final OfdParseResult parseResult;
        try {
            parseResult = ofdSegmentParser.parse(contentBytes, fileIndex);
        } catch (IOException | RuntimeException exception) {
            throw asUnreadable(exception);
        }
        ParsedFile parsedFile = new ParsedFile(fileIndex, ContentType.OFD, precheckPages,
                pagesFromSegments(parseResult.getSegmentList(), parseResult.getPageCount()));
        logParsed(parsedFile, "OFD原生文本对象");
        return parsedFile;
    }

    /**
     * 把 segment 按页分组成 {@link ParsedPage}。
     *
     * <p><b>页数取自解析器，不取 {@code precheckPages}。</b> 按 {@code precheckPages} 建页时，
     * 实际页数更多的话超出部分的 segment 会被<b>静默丢掉</b>——报告少一页内容而无人知晓；
     * 而且 {@link ParsedFile} 构造器里那句 {@code pageList.size() == precheckPages}
     * 会恒为真，变成一个永远不会失败的断言。两者对上不上，交给构造器去判。</p>
     *
     * <p>再加一条本方法自己的断言：<b>每个 segment 都必须落进某一页</b>。
     * 页码越界说明解析器自相矛盾（页数与 segment 编址对不上），必须炸而不是丢。</p>
     */
    static List<ParsedPage> pagesFromSegments(List<Segment> segmentList, int pageCount) {
        if (pageCount < 1) {
            throw new IllegalStateException("解析页数必须大于零");
        }
        for (Segment segment : segmentList) {
            int page = segment.pageNumber();
            if (page < 1 || page > pageCount) {
                throw new IllegalStateException(
                        "segment 页码越界，解析器页数与编址不一致：pageCount=" + pageCount
                                + "，segmentPage=" + page);
            }
        }
        List<ParsedPage> pageList = new ArrayList<ParsedPage>(pageCount);
        for (int page = 1; page <= pageCount; page++) {
            pageList.add(new ParsedPage(page, segmentsOfPage(segmentList, page), null, false));
        }
        return pageList;
    }

    /**
     * 上传的 JPG/PNG：原始编码字节直传 OCR，本地不解码（§5.6.3-④）。
     * <p>只有发 LLM-A 的那张才降采样解码——整幅解码一张 8000 万像素的图是 240MB 位图。</p>
     */
    private ParsedFile parseImage(int fileIndex, ContentType contentType, byte[] contentBytes) {
        List<Segment> segmentList = recognizePage(contentBytes, fileIndex, 1);
        final BufferedImage decodedImage;
        try {
            decodedImage = imageContentInspector.decodeSubsampled(
                    contentBytes, ExtractionImageCompressor.PRIMARY_LONG_EDGE);
        } catch (IOException | RuntimeException exception) {
            throw asUnreadable(exception);
        }
        CompressedPageImage extractionImage;
        try {
            extractionImage = extractionImageCompressor.compressForExtraction(decodedImage);
        } finally {
            decodedImage.flush();
        }
        List<ParsedPage> pageList = new ArrayList<ParsedPage>(1);
        pageList.add(new ParsedPage(1, segmentList, extractionImage.getJpegBytes(), true));
        ParsedFile parsedFile = new ParsedFile(fileIndex, contentType, 1, pageList);
        logParsed(parsedFile, "OCR");
        return parsedFile;
    }

    /** Word：按源码顺序产块，内嵌图片走 OCR；Word 不向 LLM-A 发页面图。 */
    private ParsedFile parseWord(int fileIndex, ContentType contentType, byte[] contentBytes) {
        final WordParseResult parseResult;
        try {
            parseResult = wordSegmentParser.parse(contentBytes, contentType, fileIndex);
        } catch (IOException | RuntimeException exception) {
            // Word 解析内部会调 OCR：OcrCallException 与 WordCapacityGuard 的
            // PAGE_LIMIT_EXCEEDED 都由 asUnreadable 原样放行，不会被误报成 UNREADABLE。
            throw asUnreadable(exception);
        }
        ParsedFile parsedFile = ParsedFile.word(fileIndex, contentType, parseResult.getSegmentList());
        logParsed(parsedFile, "Word原生文本+内嵌图OCR");
        return parsedFile;
    }

    /**
     * 记一条单文件解析完成。
     *
     * <p><b>{@code 解析路径} 是本条日志存在的理由。</b> 「这份报告为什么抽得这么差」
     * 的第一个分叉就是走了原生文本层还是走了 OCR——同一份 PDF 两条路的产出质量差一个量级，
     * 而这个判断由文本层阈值和密度闸自动做出，事后从任何别的地方都<b>倒推不出来</b>。</p>
     *
     * <p>只记规模量（页数、块数），不记块内容——正文走 {@link SensitiveLog}。</p>
     */
    private void logParsed(ParsedFile parsedFile, String parsePath) {
        log.info("文件解析完成，fileIndex={}，格式={}，解析路径={}，等效页数={}，文字块数={}",
                parsedFile.getFileIndex(), parsedFile.getContentType(), parsePath,
                parsedFile.getEffectivePageCount(), parsedFile.segmentCount());
    }

    /** 调一次 OCR 并转成本页 segment；识别块超过每页上限时整任务失败，不做局部截断。 */
    private List<Segment> recognizePage(byte[] encodedImageBytes, int fileIndex, int page) {
        OcrResult ocrResult = paddleOcrClient.recognize(encodedImageBytes);
        OcrPageSegmentResult segmentResult = ocrPageSegmentFactory.create(
                ocrResult, fileIndex, page, 0, NORMALIZED_ORIENTATION);
        if (segmentResult.getSegmentList().size() > GlyphDensityGate.MAX_SEGMENTS_PER_PAGE) {
            throw new HealthReportException(FailCode.UNREADABLE, 400);
        }
        log.info("OCR 单页识别完成，fileIndex={}，页码={}，识别块数={}",
                fileIndex, page, segmentResult.getSegmentList().size());
        // OCR 文本是报告正文，只走 SensitiveLog；enabled() 先判一次，
        // 关闭状态下不付出下面这次拼接的代价（一页可能上百块）。
        if (SensitiveLog.enabled()) {
            StringBuilder recognizedText = new StringBuilder();
            for (Segment segment : segmentResult.getSegmentList()) {
                recognizedText.append(segment.getRawText()).append('\n');
            }
            SensitiveLog.debug("OCR 识别文本，fileIndex={}，页码={}，正文=\n{}",
                    fileIndex, page, recognizedText);
        }
        return segmentResult.getSegmentList();
    }

    /** 按 segmentId 反解的页码过滤；解析器已保证同页块相邻且有序，这里不重排。 */
    private static List<Segment> segmentsOfPage(List<Segment> segmentList, int page) {
        List<Segment> pageSegmentList = new ArrayList<Segment>();
        for (Segment segment : segmentList) {
            if (segment.pageNumber() == page) {
                pageSegmentList.add(segment);
            }
        }
        return pageSegmentList;
    }
}
