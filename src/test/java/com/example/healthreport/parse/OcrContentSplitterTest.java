package com.example.healthreport.parse;

import com.example.healthreport.parse.ocr.OcrBlock;
import com.example.healthreport.parse.ocr.OcrContentSplitter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** OCR 整页文本切块的确定性行为：保持顺序、丢空白行、不合并相邻行。 */
class OcrContentSplitterTest {

    @Test
    void shouldSplitByLineKeepOrderAndDropBlankLines() {
        List<OcrBlock> blockList = OcrContentSplitter.split(
                "  血脂检查  \r\n\r\n甘油三酯 1.85\r  \n低密度脂蛋白 3.2\n\n");

        assertThat(blockList).extracting(OcrBlock::getRawText)
                .containsExactly("血脂检查", "甘油三酯 1.85", "低密度脂蛋白 3.2");
        assertThat(blockList).allSatisfy(block -> assertThat(block.getBbox()).isNull());
    }

    @Test
    void shouldNotMergeAdjacentLinesEvenWhenTheyLookLikeOneRow() {
        List<OcrBlock> blockList = OcrContentSplitter.split("项目\n结果\n单位");

        assertThat(blockList).hasSize(3);
    }

    @Test
    void shouldReturnEmptyListForBlankContentAndRejectNull() {
        assertThat(OcrContentSplitter.split("   \n\n \r\n")).isEmpty();
        assertThatThrownBy(() -> OcrContentSplitter.split(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
