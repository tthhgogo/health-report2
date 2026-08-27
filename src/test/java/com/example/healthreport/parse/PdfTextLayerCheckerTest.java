package com.example.healthreport.parse;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PDF 原生文字层逐字形阈值测试，不允许引入聚行行为。
 */
class PdfTextLayerCheckerTest {

    private final PdfTextLayerChecker checker = new PdfTextLayerChecker();

    @Test
    void shouldRequireFiftyCharactersPerPage() throws Exception {
        try (PDDocument below = PDDocument.load(SyntheticFileFactory.pdf(1, repeat('a', 49)));
             PDDocument boundary = PDDocument.load(SyntheticFileFactory.pdf(1, repeat('a', 50)))) {
            assertThat(checker.hasUsableTextLayer(below)).isFalse();
            assertThat(checker.hasUsableTextLayer(boundary)).isTrue();
        }
    }

    @Test
    void shouldRequireThirtyPercentNonWhitespaceCharacters() throws Exception {
        String belowRatio = repeat('a', 14) + repeat(' ', 36);
        String boundaryRatio = repeat('a', 15) + repeat(' ', 35);
        try (PDDocument below = PDDocument.load(SyntheticFileFactory.pdf(1, belowRatio));
             PDDocument boundary = PDDocument.load(SyntheticFileFactory.pdf(1, boundaryRatio))) {
            assertThat(checker.hasUsableTextLayer(below)).isFalse();
            assertThat(checker.hasUsableTextLayer(boundary)).isTrue();
        }
    }

    private String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }
}
