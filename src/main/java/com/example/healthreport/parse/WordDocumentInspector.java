package com.example.healthreport.parse;

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
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

/**
 * Word 上传阶段的正文、显式单元格与合规内嵌图片遍历器。
 * <p>只读取文档自身声明的结构，不做分页、表格还原或任何版面推断。</p>
 */
@Component
public class WordDocumentInspector {

    static final int MIN_EMBEDDED_IMAGE_SIDE = 300;

    private final ImageContentInspector imageContentInspector;
    private final ZipBombGuard zipBombGuard;

    public WordDocumentInspector(ImageContentInspector imageContentInspector, ZipBombGuard zipBombGuard) {
        this.imageContentInspector = imageContentInspector;
        this.zipBombGuard = zipBombGuard;
    }

    /** 遍历 DOCX；在 POI 打开前先完成 ZIP 流式防护。 */
    public WordInspection inspectDocx(byte[] contentBytes) throws IOException {
        zipBombGuard.inspect(contentBytes);
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(contentBytes))) {
            MutableWordCount count = new MutableWordCount();
            inspectDocxBody(document.getBodyElements(), count);
            return count.toResult();
        }
    }

    /** 遍历旧版 DOC 的正文、显式表格单元格和图片。 */
    public WordInspection inspectDoc(byte[] contentBytes) throws IOException {
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(contentBytes))) {
            int nativeSegmentCount = countDocNativeSegments(document.getRange());
            int imageCount = 0;
            for (Picture picture : document.getPicturesTable().getAllPictures()) {
                if (isQualifiedImage(picture.getContent())) {
                    imageCount++;
                }
            }
            return new WordInspection(nativeSegmentCount, imageCount,
                    nativeSegmentCount > 0 || imageCount > 0);
        }
    }

    private void inspectDocxBody(List<IBodyElement> bodyElementList, MutableWordCount count) throws IOException {
        for (IBodyElement bodyElement : bodyElementList) {
            if (bodyElement instanceof XWPFParagraph) {
                inspectDocxParagraph((XWPFParagraph) bodyElement, count, true);
            } else if (bodyElement instanceof XWPFTable) {
                inspectDocxTable((XWPFTable) bodyElement, count);
            }
        }
    }

    private void inspectDocxParagraph(XWPFParagraph paragraph, MutableWordCount count,
                                      boolean countAsNativeSegment) throws IOException {
        if (countAsNativeSegment && hasText(paragraph.getText())) {
            count.nativeSegmentCount++;
        }
        for (XWPFRun run : paragraph.getRuns()) {
            for (XWPFPicture picture : run.getEmbeddedPictures()) {
                if (picture.getPictureData() != null && isQualifiedImage(picture.getPictureData().getData())) {
                    count.qualifiedEmbeddedImageCount++;
                }
            }
        }
    }

    private void inspectDocxTable(XWPFTable table, MutableWordCount count) throws IOException {
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                if (hasText(cell.getText())) {
                    count.nativeSegmentCount++;
                }
                for (XWPFParagraph paragraph : cell.getParagraphs()) {
                    inspectDocxParagraph(paragraph, count, false);
                }
                for (XWPFTable nestedTable : cell.getTables()) {
                    inspectDocxTable(nestedTable, count);
                }
            }
        }
    }

    private int countDocNativeSegments(Range range) {
        int nativeSegmentCount = 0;
        for (int index = 0; index < range.numParagraphs(); index++) {
            Paragraph paragraph = range.getParagraph(index);
            if (!paragraph.isInTable() && hasText(paragraph.text())) {
                nativeSegmentCount++;
            }
        }
        TableIterator tableIterator = new TableIterator(range);
        while (tableIterator.hasNext()) {
            Table table = tableIterator.next();
            for (int rowIndex = 0; rowIndex < table.numRows(); rowIndex++) {
                TableRow row = table.getRow(rowIndex);
                for (int cellIndex = 0; cellIndex < row.numCells(); cellIndex++) {
                    TableCell cell = row.getCell(cellIndex);
                    if (hasText(cell.text())) {
                        nativeSegmentCount++;
                    }
                }
            }
        }
        return nativeSegmentCount;
    }

    private boolean isQualifiedImage(byte[] imageBytes) {
        ImageDimensions dimensions;
        try {
            dimensions = imageContentInspector.readDimensions(imageBytes);
        } catch (IOException exception) {
            // 单张装饰图或 JDK 不支持的编码不能把正文可读的整份 Word 判为不可读。
            return false;
        }
        // Word 上传阶段只需按尺寸计数；不得为可读性判定整幅解码内嵌图片。
        return dimensions.getWidth() >= MIN_EMBEDDED_IMAGE_SIDE
                && dimensions.getHeight() >= MIN_EMBEDDED_IMAGE_SIDE;
    }

    private boolean hasText(String text) {
        if (text == null) {
            return false;
        }
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (!Character.isWhitespace(current) && current != '\u0007' && current != '\r') {
                return true;
            }
        }
        return false;
    }

    private static class MutableWordCount {
        private int nativeSegmentCount;
        private int qualifiedEmbeddedImageCount;

        private WordInspection toResult() {
            return new WordInspection(nativeSegmentCount, qualifiedEmbeddedImageCount,
                    nativeSegmentCount > 0 || qualifiedEmbeddedImageCount > 0);
        }
    }
}
