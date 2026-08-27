package com.example.healthreport.parse;

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
 */
class FormatAndReadabilityTest {

    private ZipBombGuard zipBombGuard;
    private FormatDetector formatDetector;
    private WordDocumentInspector wordDocumentInspector;
    private ReadabilityChecker readabilityChecker;

    @BeforeEach
    void setUp() {
        zipBombGuard = new ZipBombGuard();
        ImageContentInspector imageContentInspector = new ImageContentInspector();
        formatDetector = new FormatDetector(zipBombGuard);
        wordDocumentInspector = new WordDocumentInspector(imageContentInspector, zipBombGuard);
        readabilityChecker = new ReadabilityChecker(imageContentInspector, wordDocumentInspector, zipBombGuard);
    }

    @Test
    void shouldDetectAndReadAllSixFormatsByContent() throws Exception {
        assertDetectedAndReadable(SyntheticFileFactory.pdf(1, "synthetic"), ContentType.PDF);
        assertDetectedAndReadable(SyntheticFileFactory.image("jpg", 100, 100), ContentType.JPG);
        assertDetectedAndReadable(SyntheticFileFactory.image("png", 100, 100), ContentType.PNG);
        assertDetectedAndReadable(SyntheticFileFactory.docx(1, 0), ContentType.DOCX);
        assertDetectedAndReadable(SyntheticFileFactory.ofd(1), ContentType.OFD);
        assertDetectedAndReadable(SyntheticFileFactory.oldDoc(), ContentType.DOC);
    }

    @Test
    void shouldLoadPackagedSyntheticDocFixture() throws Exception {
        byte[] docBytes = SyntheticFileFactory.oldDoc();

        assertThat(docBytes.length).isGreaterThan(8);
        assertThat(formatDetector.detect(docBytes)).isEqualTo(ContentType.DOC);
    }

    @Test
    void shouldRejectOrdinaryZipAndCorruptRecognizedPdf() throws Exception {
        assertThatThrownBy(() -> formatDetector.detect(SyntheticFileFactory.ordinaryZip()))
                .isInstanceOfSatisfying(HealthReportException.class,
                        exception -> assertThat(exception.getFailCode()).isEqualTo(FailCode.UNSUPPORTED_FORMAT));

        byte[] corruptPdf = "%PDF-corrupt".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertThat(formatDetector.detect(corruptPdf)).isEqualTo(ContentType.PDF);
        assertThatThrownBy(() -> readabilityChecker.check(corruptPdf, ContentType.PDF))
                .isInstanceOfSatisfying(HealthReportException.class,
                        exception -> assertThat(exception.getFailCode()).isEqualTo(FailCode.FILE_UNREADABLE));
    }

    @Test
    void shouldRejectEmptyWordButAcceptImageOnlyWord() throws Exception {
        byte[] emptyDocx = SyntheticFileFactory.emptyDocx();
        assertThatThrownBy(() -> readabilityChecker.check(emptyDocx, ContentType.DOCX))
                .isInstanceOfSatisfying(HealthReportException.class,
                        exception -> assertThat(exception.getFailCode()).isEqualTo(FailCode.FILE_UNREADABLE));

        byte[] imageOnlyDocx = SyntheticFileFactory.docx(0, 1);
        readabilityChecker.check(imageOnlyDocx, ContentType.DOCX);
        WordInspection result = wordDocumentInspector.inspectDocx(imageOnlyDocx);
        assertThat(result.getNativeSegmentCount()).isZero();
        assertThat(result.getQualifiedEmbeddedImageCount()).isEqualTo(1);
    }

    @Test
    void shouldRejectPixelBombBeforeFullDecode() throws IOException {
        ImageContentInspector imageContentInspector = mock(ImageContentInspector.class);
        when(imageContentInspector.readDimensions(any(byte[].class)))
                .thenReturn(new ImageDimensions(10000, 8001));
        ReadabilityChecker checker = new ReadabilityChecker(imageContentInspector,
                mock(WordDocumentInspector.class), mock(ZipBombGuard.class));

        assertThatThrownBy(() -> checker.check(new byte[]{1}, ContentType.PNG))
                .isInstanceOfSatisfying(HealthReportException.class,
                        exception -> assertThat(exception.getFailCode()).isEqualTo(FailCode.FILE_TOO_LARGE));
        verify(imageContentInspector, never()).assertActuallyDecodable(any(byte[].class));
    }

    @Test
    void shouldCountLargeDeclaredWordImageWithoutFullDecode() throws Exception {
        ImageContentInspector imageContentInspector = mock(ImageContentInspector.class);
        when(imageContentInspector.readDimensions(any(byte[].class)))
                .thenReturn(new ImageDimensions(20000, 20000));
        WordDocumentInspector inspector = new WordDocumentInspector(imageContentInspector, zipBombGuard);

        WordInspection result = inspector.inspectDocx(SyntheticFileFactory.docx(0, 1));

        assertThat(result.getQualifiedEmbeddedImageCount()).isEqualTo(1);
        verify(imageContentInspector, never()).assertActuallyDecodable(any(byte[].class));
    }

    @Test
    void shouldIgnoreUnsupportedEmbeddedImageWhenWordBodyHasText() throws Exception {
        byte[] docxBytes = SyntheticFileFactory.docxWithOpaqueImage("synthetic segment");

        readabilityChecker.check(docxBytes, ContentType.DOCX);
        WordInspection result = wordDocumentInspector.inspectDocx(docxBytes);

        assertThat(result.isReadable()).isTrue();
        assertThat(result.getNativeSegmentCount()).isEqualTo(1);
        assertThat(result.getQualifiedEmbeddedImageCount()).isZero();
    }

    private void assertDetectedAndReadable(byte[] contentBytes, ContentType expectedType) {
        ContentType actualType = formatDetector.detect(contentBytes);
        assertThat(actualType).isEqualTo(expectedType);
        readabilityChecker.check(contentBytes, actualType);
    }
}
