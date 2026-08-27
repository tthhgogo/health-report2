package com.example.healthreport.parse.ocr;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 OCR 返回的整页文本切成原子识别块。
 * <p>OpenAI 兼容的对话补全协议只有一个 content 字符串，服务端按阅读顺序逐行输出，
 * 因此「一个识别块」在本接入下就是一行：按行切分是可穷举输入的确定性字符串处理，
 * 属于 Java 的职责，不合并相邻行、不按坐标重排。</p>
 */
public final class OcrContentSplitter {

    private OcrContentSplitter() {
    }

    /**
     * 按行切分并丢弃纯空白行；每块 bbox 为 {@code null}——本协议没有坐标字段可返回。
     *
     * @param content 模型返回的整页文本，不得为 null
     * @return 保持服务端输出顺序的识别块列表
     */
    public static List<OcrBlock> split(String content) {
        if (content == null) {
            throw new IllegalArgumentException("OCR 文本不能为空");
        }
        String[] lineArray = content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<OcrBlock> blockList = new ArrayList<OcrBlock>(lineArray.length);
        for (String line : lineArray) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            blockList.add(new OcrBlock(trimmed, null));
        }
        return blockList;
    }
}
