package com.example.healthreport.render;

import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.ofdrw.reader.OFDReader;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * 上传阶段的可读性校验与容量预检，一次解析同时完成（原 ReadabilityChecker 已并入本类）。
 *
 * <p>合并的理由是重复解析：可读性与页数分开做时，一次 PDF 上传要开两次 {@code PDDocument}、
 * 一次 OFD 上传要开两次 {@code OFDReader} 外加多次全量 ZIP 扫描——对接近解压上限的
 * OFD 是白花的 CPU 与内存带宽。全部支持格式的页数都是精确值（设计方案 §3.4.1）。</p>
 *
 * <p><b>调用前置条件：{@link FormatDetector#detect} 已执行。</b> OFD 的解压炸弹扫描
 * （{@link ZipBombGuard}）在格式识别区分 DOCX/OFD 时已对同一份字节完成，
 * 本类不再重复扫描；脱离 detect 单独调用本类等于绕开炸弹防御。</p>
 */
@Service
public class CapacityPrecheckService {

    static final int MIN_IMAGE_SIDE = 100;
    static final long MAX_IMAGE_PIXELS = 80_000_000L;

    private final ImageContentInspector imageContentInspector;

    public CapacityPrecheckService(ImageContentInspector imageContentInspector) {
        this.imageContentInspector = imageContentInspector;
    }

    /**
     * 校验可读性并返回 {@code precheck_pages}（恒为精确页数）。
     * <p>图片像素超限单独映射为 FILE_TOO_LARGE，必须在实际解码前拒绝——
     * 240~320MB 的整幅位图不能进入共享堆。</p>
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
                    checkImage(contentBytes);
                    return 1;
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
            int pageCount = document.getNumberOfPages();
            if (pageCount < 1) {
                throw new HealthReportException(FailCode.FILE_UNREADABLE, 400);
            }
            return pageCount;
        }
    }

    private int ofdPages(byte[] contentBytes) throws IOException {
        try (OFDReader reader = new OFDReader(new ByteArrayInputStream(contentBytes))) {
            int pageCount = reader.getNumberOfPages();
            if (pageCount < 1) {
                throw new HealthReportException(FailCode.FILE_UNREADABLE, 400);
            }
            return pageCount;
        }
    }

    private void checkImage(byte[] contentBytes) throws IOException {
        ImageDimensions dimensions = imageContentInspector.readDimensions(contentBytes);
        if (dimensions.getWidth() < MIN_IMAGE_SIDE || dimensions.getHeight() < MIN_IMAGE_SIDE) {
            throw new HealthReportException(FailCode.FILE_UNREADABLE, 400);
        }
        if (dimensions.totalPixels() > MAX_IMAGE_PIXELS) {
            throw new HealthReportException(FailCode.FILE_TOO_LARGE, 400);
        }
        imageContentInspector.assertActuallyDecodable(contentBytes);
    }
}
