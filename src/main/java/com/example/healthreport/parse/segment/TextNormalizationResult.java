package com.example.healthreport.parse.segment;

import lombok.Getter;

/** 一次文本规范化的结果。 */
@Getter
public final class TextNormalizationResult {

    private final String normalizedText;

    public TextNormalizationResult(String normalizedText) {
        this.normalizedText = normalizedText;
    }
}
