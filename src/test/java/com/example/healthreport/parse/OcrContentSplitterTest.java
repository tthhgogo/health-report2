package com.example.healthreport.parse;

import com.example.healthreport.parse.ocr.OcrBlock;
import com.example.healthreport.parse.ocr.OcrContentSplitter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OCR 整页文本切块的确定性行为。
 *
 * <p>三个真实格式样本取自 2026-09-02 的实测：同一个模型、同一条指令、三次调用
 * 分别返回纯文本流、{@code <fcel>} 表格标记、Markdown 表格。
 * <b>三种都必须切出可用的块粒度</b>——只按 {@code \n} 切的话，
 * 第二种整页只有个位数的块，{@code blockRefs} 就失去定位意义。</p>
 */
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

    /** 表格标记：{@code <nl>} 是行分隔符，一行一块；单元格用制表符隔开。 */
    @Test
    void shouldKeepTableMarkupRowAsOneBlockWithCellSeparators() {
        String content = "姓名<fcel>谭豪<fcel>性别<fcel>男<nl>"
                + "项目名称<fcel>检查结果<fcel>单位<fcel>提示<fcel>参考值<nl>"
                + "血糖（GLU）<fcel>4.98<fcel>mmol/L<ecel><fcel>3.9~6.1<nl>";

        List<OcrBlock> blockList = OcrContentSplitter.split(content);

        assertThat(blockList).extracting(OcrBlock::getRawText).containsExactly(
                "姓名\t谭豪\t性别\t男",
                "项目名称\t检查结果\t单位\t提示\t参考值",
                "血糖（GLU）\t4.98\tmmol/L\t\t3.9~6.1");
    }

    /**
     * 空单元格必须保留成空位，否则列会错位。
     * <p>「提示」列为空时，{@code 3.9~6.1} 仍要落在第 5 格；拍平成四个块就分不清了。</p>
     */
    @Test
    void shouldPreserveEmptyCellSoColumnsDoNotShift() {
        List<OcrBlock> blockList = OcrContentSplitter.split(
                "| 血糖（GLU） | 4.98 | mmol/L |  | 3.9~6.1 |");

        assertThat(blockList).hasSize(1);
        assertThat(blockList.get(0).getRawText().split("\t", -1))
                .containsExactly("血糖（GLU）", "4.98", "mmol/L", "", "3.9~6.1");
    }

    /**
     * 首列、末列、连续多个末尾空列都必须保留。
     *
     * <p>{@code trim()} 会连制表符一起删掉——中间的空位保住了、两端的丢了，
     * 整行列位置左移，比全丢更难排查。</p>
     */
    @Test
    void shouldPreserveLeadingAndTrailingEmptyCells() {
        assertThat(cellsOf("|  | 阴性 | 定性 | 阴性 |"))
                .as("首列为空").containsExactly("", "阴性", "定性", "阴性");
        assertThat(cellsOf("| 尿糖 | 阴性 |  |"))
                .as("末列为空").containsExactly("尿糖", "阴性", "");
        assertThat(cellsOf("| 尿糖 | 阴性 |  |  |"))
                .as("连续两个末尾空列").containsExactly("尿糖", "阴性", "", "");
        assertThat(cellsOf("|  | 阴性 |  |"))
                .as("首末都空").containsExactly("", "阴性", "");
    }

    /** 整行全空（只有竖线与空白）不产出块。 */
    @Test
    void shouldDropRowThatIsEntirelyEmpty() {
        assertThat(OcrContentSplitter.split("|  |  |  |")).isEmpty();
        assertThat(OcrContentSplitter.split("<fcel><fcel><fcel><nl>")).isEmpty();
    }

    /**
     * 表格标记里 {@code <fcel>} 是单元格<b>起始</b>标记，不是分隔符。
     * <p>行首那个标记归一化后会多出一个空位，那是转换的产物——只丢这一个，其余全保留。</p>
     */
    @Test
    void shouldDropOnlyTheLeadingArtifactFromCellStartTag() {
        assertThat(cellsOf("<fcel>血糖<fcel>4.98<fcel>mmol/L"))
                .containsExactly("血糖", "4.98", "mmol/L");
        assertThat(cellsOf("<fcel>血糖<fcel>4.98<ecel><ecel>"))
                .as("末尾两个空位仍要保留").containsExactly("血糖", "4.98", "", "");
    }

    /** Markdown 表格：一行一块，分隔行整行丢弃——它是版式不是内容。 */
    @Test
    void shouldKeepMarkdownRowAsOneBlockAndDropSeparatorRow() {
        String content = "| 项目名称 | 检查结果 | 单位 | 提示 | 参考值 |\n"
                + "| --- | :---: | --- | --- | --- |\n"
                + "| 血糖（GLU） | 4.98 | mmol/L |  | 3.9~6.1 |\n"
                + "| 总胆固醇（TC） | 6.47 | mmol/L | ↑ | 0~5.72 |";

        List<OcrBlock> blockList = OcrContentSplitter.split(content);

        assertThat(blockList).extracting(OcrBlock::getRawText).containsExactly(
                "项目名称\t检查结果\t单位\t提示\t参考值",
                "血糖（GLU）\t4.98\tmmol/L\t\t3.9~6.1",
                "总胆固醇（TC）\t6.47\tmmol/L\t↑\t0~5.72");
    }

    /** 正文里偶然出现竖线不得被当成表格行。 */
    @Test
    void shouldNotTreatIncidentalPipeAsMarkdownTable() {
        List<OcrBlock> blockList = OcrContentSplitter.split("血压 120|80 mmHg");

        assertThat(blockList).extracting(OcrBlock::getRawText).containsExactly("血压 120|80 mmHg");
    }

    /** 未知的第四种格式最坏退回按行切，不会更差。 */
    @Test
    void shouldFallBackToLineSplitForUnknownFormat() {
        List<OcrBlock> blockList = OcrContentSplitter.split("<row>甲</row>\n<row>乙</row>");

        assertThat(blockList).hasSize(2);
    }

    /** 取单行切出的块并按制表符还原成单元格数组。 */
    private String[] cellsOf(String line) {
        List<OcrBlock> blockList = OcrContentSplitter.split(line);
        assertThat(blockList).hasSize(1);
        return blockList.get(0).getRawText().split("\t", -1);
    }

    @Test
    void shouldReturnEmptyListForBlankContentAndRejectNull() {
        assertThat(OcrContentSplitter.split("   \n\n \r\n")).isEmpty();
        assertThatThrownBy(() -> OcrContentSplitter.split(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
