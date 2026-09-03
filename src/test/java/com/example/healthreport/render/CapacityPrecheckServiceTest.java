package com.example.healthreport.render;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R43b1：容量预检对全部支持格式都是精确值（PDF/OFD 真实页数、图片恒为 1）。
 * <p>Word 已在格式判定阶段识别即拒（§5.4），预检不再有折算分支。</p>
 */
class CapacityPrecheckServiceTest {

    private CapacityPrecheckService service;

    @BeforeEach
    void setUp() {
        service = new CapacityPrecheckService(new ImageContentInspector());
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
}
