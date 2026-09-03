package com.example.healthreport.render;

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
 * <p>R43b12：DOC/DOCX 识别即拒（UNSUPPORTED_FORMAT），且 DOCX 不得误判为损坏的 OFD。</p>
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
        precheckService = new CapacityPrecheckService(imageContentInspector);
    }

    @Test
    void shouldDetectAndReadAllSupportedFormatsByContent() throws Exception {
        assertDetectedAndReadable(SyntheticFileFactory.pdf(1, "synthetic"), ContentType.PDF);
        assertDetectedAndReadable(SyntheticFileFactory.image("jpg", 100, 100), ContentType.JPG);
        assertDetectedAndReadable(SyntheticFileFactory.image("png", 100, 100), ContentType.PNG);
        assertDetectedAndReadable(SyntheticFileFactory.ofd(1), ContentType.OFD);
    }

    /** R43b12：DOC 与 DOCX 均返回 UNSUPPORTED_FORMAT，不落 file 行、不存对象。 */
    @Test
    void docAndDocxMustBeRejectedAtDetection() throws Exception {
        assertRejectedAsUnsupported(SyntheticFileFactory.docx(1, 0));
        assertRejectedAsUnsupported(SyntheticFileFactory.emptyDocx());
        assertRejectedAsUnsupported(SyntheticFileFactory.oldDoc());
        // DOCX 是 ZIP 容器：必须给「暂不支持该格式」，不得误判成损坏的 OFD 报「文件无法读取」。
        // OFD 同为 ZIP 容器，正常通过。
        assertThat(formatDetector.detect(SyntheticFileFactory.ofd(1))).isEqualTo(ContentType.OFD);
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
        CapacityPrecheckService checker = new CapacityPrecheckService(imageContentInspector);

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
