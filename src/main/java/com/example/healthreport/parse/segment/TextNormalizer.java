package com.example.healthreport.parse.segment;

import org.springframework.stereotype.Component;

import java.text.Normalizer;

/**
 * 报告文本的确定性规范化入口。
 * <p>固定执行 NFKC、部首补充区映射、全角转半角；从不修改调用方持有的原文。</p>
 */
@Component
public class TextNormalizer {

    /**
     * 必须删除的不可见字符：零宽空格、零宽非连接、零宽连接、BOM、软连字符、字连接符。
     * 【NFKC 一个都不删】——实测七种全部残留。它们在 PDF 与 OCR 文本里很常见，
     * 而 normalizedText 正是三条安全链路的匹配对象：
     * 来源校验的 NATIVE 严格子串、过敏原高风险交叉扫描、阳性行覆盖扫描。
     * 「牛(ZWSP)奶」会让这三处同时匹配不到，而过敏原字段一旦来源校验失败就会被丢弃——
     * 一个看不见的字符足以让整条过敏信息消失。
     */
    private static final String INVISIBLE_CHARACTERS = "\u200B\u200C\u200D\uFEFF\u00AD\u2060";

    /** 规范化文本；未收录的部首补充区字符保留原样，不做任何猜测替换。 */
    public TextNormalizationResult normalize(String rawText) {
        if (rawText == null) {
            throw new IllegalArgumentException("原始文本不能为空");
        }
        String nfkcText = Normalizer.normalize(rawText, Normalizer.Form.NFKC);
        StringBuilder normalizedBuilder = new StringBuilder(nfkcText.length());
        for (int offset = 0; offset < nfkcText.length();) {
            int codePoint = nfkcText.codePointAt(offset);
            String replacement = RadicalNormalizeMap.replacement(codePoint);
            if (replacement != null) {
                normalizedBuilder.append(replacement);
            } else if (isInvisible(codePoint)) {
                // 直接丢弃：它已被清除，不构成「规范化后仍污染」。
                offset += Character.charCount(codePoint);
                continue;
            } else {
                appendHalfWidth(normalizedBuilder, codePoint);
            }
            offset += Character.charCount(codePoint);
        }
        return new TextNormalizationResult(normalizedBuilder.toString());
    }

    /** 判断是否为必须删除的不可见字符。 */
    private boolean isInvisible(int codePoint) {
        return codePoint <= Character.MAX_VALUE
                && INVISIBLE_CHARACTERS.indexOf((char) codePoint) >= 0;
    }

    private void appendHalfWidth(StringBuilder targetBuilder, int codePoint) {
        if (codePoint == 0x3000) {
            targetBuilder.append(' ');
        } else if (codePoint >= 0xFF01 && codePoint <= 0xFF5E) {
            targetBuilder.append((char) (codePoint - 0xFEE0));
        } else {
            targetBuilder.appendCodePoint(codePoint);
        }
    }
}
