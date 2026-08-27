package com.example.healthreport.parse.word;

import lombok.Getter;

/** Word OCR 完成后的精确容量结果。 */
@Getter
public final class WordCapacityResult {

    private final int exactSegmentCount;
    private final int embeddedImageCount;
    private final int exactWordPages;

    public WordCapacityResult(int exactSegmentCount, int embeddedImageCount, int exactWordPages) {
        this.exactSegmentCount = exactSegmentCount;
        this.embeddedImageCount = embeddedImageCount;
        this.exactWordPages = exactWordPages;
    }
}
