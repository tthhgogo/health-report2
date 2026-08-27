package com.example.healthreport.parse.segment;

import lombok.Getter;

/** 文本规范化结果，只暴露规范化文本与残留非标准字符数量。 */
@Getter
public final class TextNormalizationResult {

    private final String normalizedText;
    private final int residualNonStandardCount;

    public TextNormalizationResult(String normalizedText, int residualNonStandardCount) {
        this.normalizedText = normalizedText;
        this.residualNonStandardCount = residualNonStandardCount;
    }
}
