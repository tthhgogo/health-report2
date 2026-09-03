package com.example.healthreport.parse.ocr;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 OCR 返回的整页文本切成原子识别块。
 *
 * <p>OpenAI 兼容的对话补全协议只有一个 content 字符串、没有任何坐标字段，
 * 所以每块 bbox 恒为 {@code null}，块的粒度完全由这里的切分决定。</p>
 *
 * <p><b>为什么不能只按 {@code \n} 切。</b> 2026-09-02 用真实报告实测，同一个模型、
 * 同一条指令、三次调用返回了三种格式：纯文本流、Markdown 表格、以及
 * {@code <fcel>/<lcel>/<nl>} 表格标记。<b>第三种的行分隔符是 {@code <nl>} 而不是
 * {@code \n}</b>——只按 {@code \n} 切会把整页切成个位数的块，
 * 而 {@code blockRefs} 的全部意义就是定位到「哪一块」：粒度一垮，
 * 证据包含性校验、行归属判断、400 块/页 的密度闸就同时失效。</p>
 *
 * <p><b>所以这里做归一化，而不是指望模型听话。</b> 提示词已经要求逐行输出
 * （{@code PaddleOcrVlClient.TRANSCRIBE_INSTRUCTION}），但那是「尽量」；
 * 本类是不依赖模型配合的那一半。再冒出第四种格式，最坏也只是退回按行切。</p>
 *
 * <p><b>块的粒度是「一行」，行内用制表符保留单元格边界与空位。</b>
 * 曾经把每个单元格拆成独立块，那样<b>行边界和空单元格一起丢了</b>：
 * {@code 血糖 | 4.98 | mmol/L | (空) | 3.9~6.1} 变成四个平铺的块之后，
 * 模型既不知道下一行从哪开始，也无法判断 {@code 3.9~6.1} 落在「提示」还是「参考值」列。
 * <b>Word 内嵌图尤其致命</b>——Word 不向 LLM-A 发页面图（{@code FileParseService.parseWord}），
 * OCR 块是那一页仅有的信息。</p>
 *
 * <p>空单元格保留成相邻的两个制表符，列位置因此不会错位。这是对 OCR
 * <b>显式声明</b>的表格结构做确定性转换，不是 Java 在推断版面（§0-2）。</p>
 */
public final class OcrContentSplitter {

    /** 表格标记里的换行符；PaddleOCR-VL 用它分行，不用 {@code \n}。 */
    private static final String ROW_SEPARATOR_TAG = "<nl>";

    /** 单元格标记：首格、跨列续格、跨行续格、交叉续格、空格。 */
    private static final String[] CELL_TAG_ARRAY = {"<fcel>", "<lcel>", "<ucel>", "<xcel>", "<ecel>"};

    /** 归一化后统一使用的换行符。 */
    private static final char LINE_FEED = '\n';

    /** 归一化后统一使用的单元格分隔符；选制表符是因为它不会出现在 OCR 文本里。 */
    private static final char CELL_SEPARATOR = '\t';

    private OcrContentSplitter() {
    }

    /**
     * 归一化各种表格标记后按行与单元格切分，丢弃纯空白块。
     *
     * @param content 模型返回的整页文本，不得为 null
     * @return 保持服务端输出顺序的识别块列表；每块 bbox 为 {@code null}
     */
    public static List<OcrBlock> split(String content) {
        if (content == null) {
            throw new IllegalArgumentException("OCR 文本不能为空");
        }
        String normalized = normalize(content);
        List<OcrBlock> blockList = new ArrayList<OcrBlock>();
        for (String line : normalized.split(String.valueOf(LINE_FEED), -1)) {
            appendRow(line, blockList);
        }
        return blockList;
    }

    /** 把三种已观察到的格式统一成「换行分行、制表符分单元格」。 */
    private static String normalize(String content) {
        String normalized = content.replace("\r\n", "\n").replace('\r', LINE_FEED);
        normalized = normalized.replace(ROW_SEPARATOR_TAG, String.valueOf(LINE_FEED));
        for (String cellTag : CELL_TAG_ARRAY) {
            normalized = normalized.replace(cellTag, String.valueOf(CELL_SEPARATOR));
        }
        return normalized;
    }

    /**
     * 把一行整理成一个块：单元格之间统一用制表符，空单元格保留成空位。
     *
     * <p>Markdown 表格行（首尾都是 {@code |}）按 {@code |} 切；分隔行
     * （{@code |---|---|}）整行丢弃，它是版式不是内容。其余行原样保留——
     * 没有单元格标记时整行就是一块，与最初的按行切完全一致。</p>
     */
    private static void appendRow(String line, List<OcrBlock> blockList) {
        String rowText;
        if (isMarkdownTableRow(line.trim())) {
            String trimmedLine = line.trim();
            if (isMarkdownSeparatorRow(trimmedLine)) {
                return;
            }
            // 去掉首尾竖线再切，否则两端会各多出一个空单元格。
            // 【剩下的空位一个都不能动】——首列或末列本来就空时，那是真实的列位置。
            String inner = trimmedLine.substring(1, trimmedLine.length() - 1);
            rowText = joinCells(inner.split("\\|", -1));
        } else if (line.indexOf(CELL_SEPARATOR) >= 0) {
            // 表格标记里 <fcel> 是【单元格起始标记】而不是分隔符：行首那个标记归一化后
            // 会多出一个空位，它是转换的产物，不是真实的空列。只丢这一个，其余全保留。
            String withoutLeadingArtifact = line.charAt(0) == CELL_SEPARATOR
                    ? line.substring(1) : line;
            rowText = joinCells(withoutLeadingArtifact.split(String.valueOf(CELL_SEPARATOR), -1));
        } else {
            rowText = line.trim();
        }
        if (!isBlankRow(rowText)) {
            blockList.add(new OcrBlock(rowText, null));
        }
    }

    /** 逐个单元格去空白后用制表符连接；<b>空单元格保留</b>，否则列会错位。 */
    private static String joinCells(String[] cellArray) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < cellArray.length; index++) {
            if (index > 0) {
                builder.append(CELL_SEPARATOR);
            }
            builder.append(cellArray[index].trim());
        }
        return builder.toString();
    }

    /**
     * 整行只有分隔符与空白时视为空行。
     * <p><b>不做 trim。</b> Java 的 {@code trim()} 会连制表符一起删掉，
     * 那样首列或末列的空位就没了，整行的列位置随之左移——
     * 中间的空位保住了、两端的丢了，比全丢更难排查。</p>
     */
    private static boolean isBlankRow(String rowText) {
        for (int index = 0; index < rowText.length(); index++) {
            char character = rowText.charAt(index);
            if (character != CELL_SEPARATOR && !Character.isWhitespace(character)) {
                return false;
            }
        }
        return true;
    }

    /** 首尾都是竖线才算表格行；正文里偶然出现一个竖线不触发。 */
    private static boolean isMarkdownTableRow(String line) {
        return line.length() >= 2 && line.charAt(0) == '|' && line.charAt(line.length() - 1) == '|';
    }

    /** {@code |---|:---:|} 这类分隔行只有竖线、短横、冒号与空白。 */
    private static boolean isMarkdownSeparatorRow(String line) {
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character != '|' && character != '-' && character != ':'
                    && !Character.isWhitespace(character)) {
                return false;
            }
        }
        return true;
    }
}
