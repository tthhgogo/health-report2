package com.example.healthreport.support.text;

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
     * 【NFKC 一个都不删】——实测六种全部残留。
     *
     * <p><b>新链路里本类主要挡的是全角与合字，不是零宽字符</b>（开发方案 §5.2）：
     * 模型看的是渲染图，零宽字符画不出来、它读不到自己看不见的东西。真正常见的是
     * 模型照抄报告排版带来的「０.５６～１.７０」全角数字/波浪号、「㎎」合字、全角括号与
     * U+3000 全角空格——这些由 NFKC 与全角转半角处理。不可见字符的删除仍保留，
     * 但来源换成了人手写的 Java 常量表（网页/Word 粘贴带进的 NBSP）与外部导入的菜品数据；
     * 消费方：tagHash 计算、高危表述闸、健康问题 name 逐段校验、食材差集、同一性姓名比对。</p>
     */
    private static final String INVISIBLE_CHARACTERS = "\u200B\u200C\u200D\uFEFF\u00AD\u2060";

    /** 规范化文本；未收录的部首补充区字符保留原样，不做任何猜测替换。 */
    public String normalize(String rawText) {
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
        return normalizedBuilder.toString();
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
