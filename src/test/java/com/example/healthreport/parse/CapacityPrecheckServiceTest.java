package com.example.healthreport.parse;

import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 上传容量预检边界测试。
 */
class CapacityPrecheckServiceTest {

    private CapacityPrecheckService service;

    @BeforeEach
    void setUp() {
        ZipBombGuard guard = new ZipBombGuard();
        WordDocumentInspector inspector = new WordDocumentInspector(new ImageContentInspector(), guard);
        service = new CapacityPrecheckService(inspector, guard);
    }

    @Test
    void shouldUseExactPagesAndCeilingWordSegments() throws Exception {
        assertThat(service.precheckPages(SyntheticFileFactory.pdf(3, null), ContentType.PDF)).isEqualTo(3);
        assertThat(service.precheckPages(SyntheticFileFactory.ofd(2), ContentType.OFD)).isEqualTo(2);
        assertThat(service.precheckPages(SyntheticFileFactory.image("png", 100, 100), ContentType.PNG))
                .isEqualTo(1);
        assertThat(service.precheckPages(SyntheticFileFactory.docx(81, 0), ContentType.DOCX)).isEqualTo(3);
    }

    @Test
    void shouldApplyWordBoundariesAndAllowZeroForImageOnlyWord() throws Exception {
        assertThat(service.precheckPages(SyntheticFileFactory.docx(1200, 30), ContentType.DOCX))
                .isEqualTo(30);
        assertThat(service.precheckPages(SyntheticFileFactory.docx(0, 30), ContentType.DOCX)).isZero();

        assertPageLimit(SyntheticFileFactory.docx(1201, 0));
        assertPageLimit(SyntheticFileFactory.docx(0, 31));
    }

    private void assertPageLimit(byte[] contentBytes) {
        assertThatThrownBy(() -> service.precheckPages(contentBytes, ContentType.DOCX))
                .isInstanceOfSatisfying(HealthReportException.class,
                        exception -> assertThat(exception.getFailCode()).isEqualTo(FailCode.PAGE_LIMIT_EXCEEDED));
    }
}
