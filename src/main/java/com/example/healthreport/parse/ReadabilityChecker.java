package com.example.healthreport.parse;

import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.ofdrw.reader.OFDReader;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * 六种上传格式的可读性校验入口。
 */
@Component
public class ReadabilityChecker {

    static final int MIN_IMAGE_SIDE = 100;
    static final long MAX_IMAGE_PIXELS = 80_000_000L;

    private final ImageContentInspector imageContentInspector;
    private final WordDocumentInspector wordDocumentInspector;
    private final ZipBombGuard zipBombGuard;

    public ReadabilityChecker(ImageContentInspector imageContentInspector,
                              WordDocumentInspector wordDocumentInspector,
                              ZipBombGuard zipBombGuard) {
        this.imageContentInspector = imageContentInspector;
        this.wordDocumentInspector = wordDocumentInspector;
        this.zipBombGuard = zipBombGuard;
    }

    /**
     * 校验文件可读；图片像素超限单独映射为 FILE_TOO_LARGE。
     */
    public void check(byte[] contentBytes, ContentType contentType) {
        try {
            switch (contentType) {
                case PDF:
                    checkPdf(contentBytes);
                    break;
                case JPG:
                case PNG:
                    checkImage(contentBytes);
                    break;
                case DOCX:
                    assertReadableWord(wordDocumentInspector.inspectDocx(contentBytes));
                    break;
                case OFD:
                    checkOfd(contentBytes);
                    break;
                case DOC:
                    assertReadableWord(wordDocumentInspector.inspectDoc(contentBytes));
                    break;
                default:
                    throw new HealthReportException(FailCode.UNSUPPORTED_FORMAT, 400);
            }
        } catch (HealthReportException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new HealthReportException(FailCode.FILE_UNREADABLE, 400, exception);
        }
    }

    private void checkPdf(byte[] contentBytes) throws IOException {
        try (PDDocument document = PDDocument.load(contentBytes)) {
            if (document.getNumberOfPages() < 1) {
                throw new HealthReportException(FailCode.FILE_UNREADABLE, 400);
            }
        }
    }

    private void checkImage(byte[] contentBytes) throws IOException {
        ImageDimensions dimensions = imageContentInspector.readDimensions(contentBytes);
        if (dimensions.getWidth() < MIN_IMAGE_SIDE || dimensions.getHeight() < MIN_IMAGE_SIDE) {
            throw new HealthReportException(FailCode.FILE_UNREADABLE, 400);
        }
        if (dimensions.totalPixels() > MAX_IMAGE_PIXELS) {
            // 必须在实际解码前拒绝，避免 240~320MB 的整幅位图进入共享堆。
            throw new HealthReportException(FailCode.FILE_TOO_LARGE, 400);
        }
        imageContentInspector.assertActuallyDecodable(contentBytes);
    }

    private void checkOfd(byte[] contentBytes) throws IOException {
        zipBombGuard.inspect(contentBytes);
        try (OFDReader reader = new OFDReader(new ByteArrayInputStream(contentBytes))) {
            if (reader.getNumberOfPages() < 1) {
                throw new HealthReportException(FailCode.FILE_UNREADABLE, 400);
            }
        }
    }

    private void assertReadableWord(WordInspection wordInspection) {
        if (!wordInspection.isReadable()) {
            throw new HealthReportException(FailCode.FILE_UNREADABLE, 400);
        }
    }
}
