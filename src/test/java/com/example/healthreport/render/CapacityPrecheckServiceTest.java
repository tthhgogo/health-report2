package com.example.healthreport.render;

import com.example.healthreport.render.docx.DocxToPdfConverter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R43b1：容量预检对全部支持格式都是精确值（PDF/OFD 真实页数、图片恒为 1、
 * DOCX 为确定性排版转换后的页数）。旧版 DOC 在格式判定阶段识别即拒（§5.4）。
 */
class CapacityPrecheckServiceTest {

    private CapacityPrecheckService service;

    @BeforeEach
    void setUp() {
        service = new CapacityPrecheckService(new ImageContentInspector(), new DocxToPdfConverter());
    }

    @Test
    void shouldUseExactPagesForAllSupportedFormats() throws Exception {
        assertThat(service.precheckPages(SyntheticFileFactory.pdf(3, null), ContentType.PDF)).isEqualTo(3);
        assertThat(service.precheckPages(SyntheticFileFactory.ofd(2), ContentType.OFD)).isEqualTo(2);
        assertThat(service.precheckPages(SyntheticFileFactory.image("png", 100, 100), ContentType.PNG))
                .isEqualTo(1);
        assertThat(service.precheckPages(SyntheticFileFactory.image("jpg", 100, 100), ContentType.JPG))
                .isEqualTo(1);
    }

    @Test
    void docxPagesShouldComeFromDeterministicLayoutConversion() throws Exception {
        Assumptions.assumeTrue(DocxToPdfConverter.cjkFontEnvironmentAvailable(), "构建机无 CJK 字体，跳过");
        byte[] docxBytes = SyntheticFileFactory.validDocx("血脂检查：甘油三酯 2.8 mmol/L 偏高");
        int firstPassPages = service.precheckPages(docxBytes, ContentType.DOCX);
        assertThat(firstPassPages).isGreaterThanOrEqualTo(1);
        // Worker 复核会用同一份字节再转一次：页数必须与上传预检一致，这是 R43b13 等值校验的前提。
        assertThat(service.precheckPages(docxBytes, ContentType.DOCX)).isEqualTo(firstPassPages);
    }
}
