package com.example.healthreport.parse;

import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.ofdrw.reader.OFDReader;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * 上传阶段容量下界预检。
 * <p>本服务不调用 OCR、不创建 Segment；Word 图片型文档的预检页数允许为 0。</p>
 */
@Service
public class CapacityPrecheckService {

    static final int WORD_SEGMENTS_PER_PAGE = 40;
    static final int MAX_WORD_NATIVE_SEGMENTS = 1200;
    static final int MAX_WORD_EMBEDDED_IMAGES = 30;

    private final WordDocumentInspector wordDocumentInspector;
    private final ZipBombGuard zipBombGuard;

    public CapacityPrecheckService(WordDocumentInspector wordDocumentInspector, ZipBombGuard zipBombGuard) {
        this.wordDocumentInspector = wordDocumentInspector;
        this.zipBombGuard = zipBombGuard;
    }

    /**
     * 计算上传时一次算定的 {@code precheck_pages}。
     */
    public int precheckPages(byte[] contentBytes, ContentType contentType) {
        try {
            switch (contentType) {
                case PDF:
                    return pdfPages(contentBytes);
                case OFD:
                    return ofdPages(contentBytes);
                case JPG:
                case PNG:
                    return 1;
                case DOCX:
                    return wordPages(wordDocumentInspector.inspectDocx(contentBytes));
                case DOC:
                    return wordPages(wordDocumentInspector.inspectDoc(contentBytes));
                default:
                    throw new HealthReportException(FailCode.UNSUPPORTED_FORMAT, 400);
            }
        } catch (HealthReportException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new HealthReportException(FailCode.FILE_UNREADABLE, 400, exception);
        }
    }

    private int pdfPages(byte[] contentBytes) throws IOException {
        try (PDDocument document = PDDocument.load(contentBytes)) {
            return document.getNumberOfPages();
        }
    }

    private int ofdPages(byte[] contentBytes) throws IOException {
        zipBombGuard.inspect(contentBytes);
        try (OFDReader reader = new OFDReader(new ByteArrayInputStream(contentBytes))) {
            return reader.getNumberOfPages();
        }
    }

    private int wordPages(WordInspection wordInspection) {
        if (wordInspection.getNativeSegmentCount() > MAX_WORD_NATIVE_SEGMENTS
                || wordInspection.getQualifiedEmbeddedImageCount() > MAX_WORD_EMBEDDED_IMAGES) {
            throw new HealthReportException(FailCode.PAGE_LIMIT_EXCEEDED, 400);
        }
        int nativeSegmentCount = wordInspection.getNativeSegmentCount();
        return (nativeSegmentCount + WORD_SEGMENTS_PER_PAGE - 1) / WORD_SEGMENTS_PER_PAGE;
    }
}
