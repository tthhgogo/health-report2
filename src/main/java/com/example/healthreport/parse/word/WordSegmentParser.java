package com.example.healthreport.parse.word;

import com.example.healthreport.infra.PaddleOcrClient;
import com.example.healthreport.parse.ContentType;
import com.example.healthreport.parse.ImageContentInspector;
import com.example.healthreport.parse.ImageDimensions;
import com.example.healthreport.parse.ImageTooLargeException;
import com.example.healthreport.parse.ZipBombGuard;
import com.example.healthreport.parse.ocr.OcrBlock;
import com.example.healthreport.parse.ocr.OcrResult;
import com.example.healthreport.parse.segment.Segment;
import com.example.healthreport.parse.segment.TextNormalizationResult;
import com.example.healthreport.parse.segment.TextNormalizer;
import com.example.healthreport.parse.segment.TextSource;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Picture;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.hwpf.usermodel.Table;
import org.apache.poi.hwpf.usermodel.TableCell;
import org.apache.poi.hwpf.usermodel.TableIterator;
import org.apache.poi.hwpf.usermodel.TableRow;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * DOC/DOCX 段落、显式单元格与内嵌图 OCR 原子块解析器。
 * <p>本类按源码顺序产出块，不合并单元格、不重排行列；OCR 异常原样上抛给唯一编排映射点。</p>
 */
public class WordSegmentParser {

    public static final int MIN_OCR_IMAGE_EDGE = 300;

    private final TextNormalizer textNormalizer;
    private final ImageContentInspector imageContentInspector;
    private final PaddleOcrClient paddleOcrClient;
    private final WordCapacityGuard wordCapacityGuard;
    private final ZipBombGuard zipBombGuard;
    private final long effectiveOcrImageBytes;

    public WordSegmentParser(TextNormalizer textNormalizer, ImageContentInspector imageContentInspector,
                             PaddleOcrClient paddleOcrClient, WordCapacityGuard wordCapacityGuard,
                             ZipBombGuard zipBombGuard, long effectiveOcrImageBytes) {
        if (effectiveOcrImageBytes <= 0L) {
            throw new IllegalArgumentException("OCR 有效图像上限必须大于零");
        }
        this.textNormalizer = textNormalizer;
        this.imageContentInspector = imageContentInspector;
        this.paddleOcrClient = paddleOcrClient;
        this.wordCapacityGuard = wordCapacityGuard;
        this.zipBombGuard = zipBombGuard;
        this.effectiveOcrImageBytes = effectiveOcrImageBytes;
    }

    /** 完成 Word 全量解析和图片 OCR 后执行精确容量闸。 */
    public WordParseResult parse(byte[] contentBytes, ContentType contentType, int fileIndex) throws IOException {
        if (contentType != ContentType.DOC && contentType != ContentType.DOCX) {
            throw new IllegalArgumentException("Word 解析器只接受 DOC 或 DOCX");
        }
        List<PendingSegment> pendingSegmentList = new ArrayList<PendingSegment>();
        int embeddedImageCount;
        if (contentType == ContentType.DOCX) {
            zipBombGuard.inspect(contentBytes);
            embeddedImageCount = parseDocx(contentBytes, pendingSegmentList);
        } else {
            embeddedImageCount = parseDoc(contentBytes, pendingSegmentList);
        }
        List<Segment> segmentList = new ArrayList<Segment>(pendingSegmentList.size());
        int residualCount = 0;
        for (int index = 0; index < pendingSegmentList.size(); index++) {
            PendingSegment pendingSegment = pendingSegmentList.get(index);
            TextNormalizationResult normalizationResult = textNormalizer.normalize(pendingSegment.rawText);
            residualCount += normalizationResult.getResidualNonStandardCount();
            textNormalizer.recordResidualSegment(normalizationResult);
            int pageNumber = index / WordCapacityGuard.SEGMENTS_PER_PAGE + 1;
            segmentList.add(new Segment(Segment.id(fileIndex, pageNumber, index), pendingSegment.rawText,
                    normalizationResult.getNormalizedText(), pendingSegment.textSource, null));
        }
        WordCapacityResult capacity = wordCapacityGuard.check(segmentList, embeddedImageCount);
        return new WordParseResult(segmentList, capacity, residualCount);
    }

    private int parseDocx(byte[] contentBytes, List<PendingSegment> pendingSegmentList) throws IOException {
        int embeddedImageCount = 0;
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(contentBytes))) {
            for (IBodyElement bodyElement : document.getBodyElements()) {
                if (bodyElement instanceof XWPFParagraph) {
                    XWPFParagraph paragraph = (XWPFParagraph) bodyElement;
                    addNative(paragraph.getText(), pendingSegmentList);
                    embeddedImageCount += addDocxPictures(paragraph, pendingSegmentList);
                } else if (bodyElement instanceof XWPFTable) {
                    embeddedImageCount += addDocxTable((XWPFTable) bodyElement, pendingSegmentList);
                }
            }
        }
        return embeddedImageCount;
    }

    private int addDocxTable(XWPFTable table, List<PendingSegment> pendingSegmentList) throws IOException {
        int embeddedImageCount = 0;
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                addNative(cell.getText(), pendingSegmentList);
                for (XWPFParagraph paragraph : cell.getParagraphs()) {
                    embeddedImageCount += addDocxPictures(paragraph, pendingSegmentList);
                }
                for (XWPFTable nestedTable : cell.getTables()) {
                    embeddedImageCount += addDocxTable(nestedTable, pendingSegmentList);
                }
            }
        }
        return embeddedImageCount;
    }

    private int addDocxPictures(XWPFParagraph paragraph,
                                List<PendingSegment> pendingSegmentList) throws IOException {
        int embeddedImageCount = 0;
        for (XWPFRun run : paragraph.getRuns()) {
            for (XWPFPicture picture : run.getEmbeddedPictures()) {
                XWPFPictureData pictureData = picture.getPictureData();
                if (pictureData != null && addPicture(pictureData.getData(), pendingSegmentList)) {
                    embeddedImageCount++;
                }
            }
        }
        return embeddedImageCount;
    }

    private int parseDoc(byte[] contentBytes, List<PendingSegment> pendingSegmentList) throws IOException {
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(contentBytes))) {
            Range range = document.getRange();
            List<DocItem> docItemList = new ArrayList<DocItem>();
            for (int paragraphIndex = 0; paragraphIndex < range.numParagraphs(); paragraphIndex++) {
                Paragraph paragraph = range.getParagraph(paragraphIndex);
                if (!paragraph.isInTable()) {
                    docItemList.add(DocItem.nativeText(paragraph.getStartOffset(), paragraph.text()));
                }
            }
            TableIterator tableIterator = new TableIterator(range);
            while (tableIterator.hasNext()) {
                Table table = tableIterator.next();
                int itemOffset = table.getStartOffset();
                for (int rowIndex = 0; rowIndex < table.numRows(); rowIndex++) {
                    TableRow row = table.getRow(rowIndex);
                    for (int cellIndex = 0; cellIndex < row.numCells(); cellIndex++) {
                        TableCell cell = row.getCell(cellIndex);
                        docItemList.add(DocItem.nativeText(itemOffset++, cell.text()));
                    }
                }
            }
            for (Picture picture : document.getPicturesTable().getAllPictures()) {
                docItemList.add(DocItem.picture(picture.getStartOffset(), picture.getContent(),
                        picture.getWidth(), picture.getHeight()));
            }
            Collections.sort(docItemList, Comparator.comparingInt(DocItem::getOffset));
            int embeddedImageCount = 0;
            for (DocItem docItem : docItemList) {
                if (docItem.pictureBytes == null) {
                    addNative(docItem.rawText, pendingSegmentList);
                } else if (docItem.width >= MIN_OCR_IMAGE_EDGE && docItem.height >= MIN_OCR_IMAGE_EDGE) {
                    if (addPicture(docItem.pictureBytes, pendingSegmentList)) {
                        embeddedImageCount++;
                    }
                }
            }
            return embeddedImageCount;
        }
    }

    private boolean addPicture(byte[] pictureBytes, List<PendingSegment> pendingSegmentList) throws IOException {
        if ((long) pictureBytes.length > effectiveOcrImageBytes) {
            throw new ImageTooLargeException(pictureBytes.length, effectiveOcrImageBytes);
        }
        ImageDimensions dimensions = imageContentInspector.readDimensions(pictureBytes);
        if (dimensions.getWidth() < MIN_OCR_IMAGE_EDGE || dimensions.getHeight() < MIN_OCR_IMAGE_EDGE) {
            return false;
        }
        OcrResult ocrResult = paddleOcrClient.recognize(pictureBytes);
        if (ocrResult == null) {
            throw new IllegalStateException("OCR 服务返回空结果");
        }
        for (OcrBlock ocrBlock : ocrResult.getBlockList()) {
            addOcr(ocrBlock.getRawText(), pendingSegmentList);
        }
        return true;
    }

    private void addNative(String rawText, List<PendingSegment> pendingSegmentList) {
        if (hasVisibleText(rawText)) {
            pendingSegmentList.add(new PendingSegment(rawText, TextSource.NATIVE));
        }
    }

    private void addOcr(String rawText, List<PendingSegment> pendingSegmentList) {
        if (hasVisibleText(rawText)) {
            pendingSegmentList.add(new PendingSegment(rawText, TextSource.OCR));
        }
    }

    private boolean hasVisibleText(String value) {
        if (value == null) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isWhitespace(value.charAt(index)) && value.charAt(index) != '\u0007') {
                return true;
            }
        }
        return false;
    }

    private static final class PendingSegment {
        private final String rawText;
        private final TextSource textSource;

        private PendingSegment(String rawText, TextSource textSource) {
            this.rawText = rawText;
            this.textSource = textSource;
        }
    }

    private static final class DocItem {
        private final int offset;
        private final String rawText;
        private final byte[] pictureBytes;
        private final int width;
        private final int height;

        private DocItem(int offset, String rawText, byte[] pictureBytes, int width, int height) {
            this.offset = offset;
            this.rawText = rawText;
            this.pictureBytes = pictureBytes;
            this.width = width;
            this.height = height;
        }

        private static DocItem nativeText(int offset, String rawText) {
            return new DocItem(offset, rawText, null, 0, 0);
        }

        private static DocItem picture(int offset, byte[] pictureBytes, int width, int height) {
            return new DocItem(offset, null, pictureBytes, width, height);
        }

        private int getOffset() {
            return offset;
        }
    }
}
