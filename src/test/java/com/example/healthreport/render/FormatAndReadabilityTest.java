package com.example.healthreport.render;

import com.example.healthreport.render.doc.DocToPdfConverter;
import com.example.healthreport.render.docx.DocxToPdfConverter;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 格式判定与读取边界回归测试。
 * <p>R43b12（2026-09-05 改版）：DOCX 与旧版 DOC 均按内容识别并进入排版转换；
 * 非 Word 的 OLE2 与普通 ZIP 识别即拒（UNSUPPORTED_FORMAT）；残缺 DOCX 容器在预检按不可读拒绝。</p>
 */
class FormatAndReadabilityTest {

    private ZipBombGuard zipBombGuard;
    private FormatDetector formatDetector;
    private CapacityPrecheckService precheckService;

    @BeforeEach
    void setUp() {
        zipBombGuard = new ZipBombGuard();
        ImageContentInspector imageContentInspector = new ImageContentInspector();
        formatDetector = new FormatDetector(zipBombGuard);
        precheckService = new CapacityPrecheckService(imageContentInspector,
                new DocxToPdfConverter(), new DocToPdfConverter());
    }

    @Test
    void shouldDetectAndReadAllSupportedFormatsByContent() throws Exception {
        assertDetectedAndReadable(SyntheticFileFactory.pdf(1, "synthetic"), ContentType.PDF);
        assertDetectedAndReadable(SyntheticFileFactory.image("jpg", 100, 100), ContentType.JPG);
        assertDetectedAndReadable(SyntheticFileFactory.image("png", 100, 100), ContentType.PNG);
        assertDetectedAndReadable(SyntheticFileFactory.ofd(1), ContentType.OFD);
        if (DocxToPdfConverter.cjkFontEnvironmentAvailable()) {
            assertDetectedAndReadable(SyntheticFileFactory.validDocx("合成用例段落"), ContentType.DOCX);
        }
        if (DocToPdfConverter.fontEnvironmentAvailable()) {
            assertDetectedAndReadable(SyntheticFileFactory.doc(), ContentType.DOC);
        }
    }

    /** R43b12（2026-09-05 改版）：DOCX/DOC 均按内容识别；非 Word OLE2 即拒；残缺 DOCX 容器预检不可读。 */
    @Test
    void wordFormatsAreAcceptedWhileNonWordAndBrokenContainersAreRejected() throws Exception {
        assertThat(formatDetector.detect(SyntheticFileFactory.validDocx("合成"))).isEqualTo(ContentType.DOCX);
        // ZIP 容器互不误判：OFD 仍为 OFD，不因 DOCX 放行而混淆。
        assertThat(formatDetector.detect(SyntheticFileFactory.ofd(1))).isEqualTo(ContentType.OFD);
        // OLE2 容器互不误判：DOC 按 WordDocument 流识别，形似 XLS 的 OLE2 仍被拒。
        assertThat(formatDetector.detect(SyntheticFileFactory.doc())).isEqualTo(ContentType.DOC);
        assertRejectedAsUnsupported(SyntheticFileFactory.nonWordOle2());

        // 残缺容器：够得上 DOCX 识别（含 word/document.xml），但 docx4j 无法排版——
        // 按「文件无法读取」拒绝，不是「暂不支持该格式」。
        byte[] brokenDocx = SyntheticFileFactory.docx(1, 0);
        assertThat(formatDetector.detect(brokenDocx)).isEqualTo(ContentType.DOCX);
        assertThatThrownBy(() -> precheckService.precheckPages(brokenDocx, ContentType.DOCX))
                .isInstanceOfSatisfying(HealthReportException.class,
                        exception -> assertThat(exception.getFailCode()).isEqualTo(FailCode.FILE_UNREADABLE));
    }

    @Test
    void shouldRejectOrdinaryZipAndCorruptRecognizedPdf() throws Exception {
        assertRejectedAsUnsupported(SyntheticFileFactory.ordinaryZip());

        byte[] corruptPdf = "%PDF-corrupt".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertThat(formatDetector.detect(corruptPdf)).isEqualTo(ContentType.PDF);
        assertThatThrownBy(() -> precheckService.precheckPages(corruptPdf, ContentType.PDF))
                .isInstanceOfSatisfying(HealthReportException.class,
                        exception -> assertThat(exception.getFailCode()).isEqualTo(FailCode.FILE_UNREADABLE));
    }

    @Test
    void shouldRejectPixelBombBeforeFullDecode() throws IOException {
        ImageContentInspector imageContentInspector = mock(ImageContentInspector.class);
        when(imageContentInspector.readDimensions(any(byte[].class)))
                .thenReturn(new ImageDimensions(10000, 8001));
        CapacityPrecheckService checker = new CapacityPrecheckService(imageContentInspector,
                new DocxToPdfConverter(), new DocToPdfConverter());

        assertThatThrownBy(() -> checker.precheckPages(new byte[]{1}, ContentType.PNG))
                .isInstanceOfSatisfying(HealthReportException.class,
                        exception -> assertThat(exception.getFailCode()).isEqualTo(FailCode.FILE_TOO_LARGE));
        verify(imageContentInspector, never()).assertActuallyDecodable(any(byte[].class));
    }

    private void assertRejectedAsUnsupported(byte[] contentBytes) {
        assertThatThrownBy(() -> formatDetector.detect(contentBytes))
                .isInstanceOfSatisfying(HealthReportException.class,
                        exception -> assertThat(exception.getFailCode())
                                .isEqualTo(FailCode.UNSUPPORTED_FORMAT));
    }

    private void assertDetectedAndReadable(byte[] contentBytes, ContentType expectedType) {
        ContentType actualType = formatDetector.detect(contentBytes);
        assertThat(actualType).isEqualTo(expectedType);
        // 可读性并入容量预检：一次解析同时完成，页数必须为正。
        assertThat(precheckService.precheckPages(contentBytes, actualType)).isGreaterThanOrEqualTo(1);
    }
}
