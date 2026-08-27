package com.example.healthreport.parse.segment;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 报告文本的确定性规范化入口。
 * <p>固定执行 NFKC、部首补充区映射、全角转半角；从不修改调用方持有的原文。</p>
 */
@Component
public class TextNormalizer {

    /** 进程级计数：规范化后仍含污染字符的解析段数。只记数量，不保留任何原文。 */
    private final AtomicLong residualNonStandardCount = new AtomicLong();

    /**
     * 必须删除的不可见字符：零宽空格、零宽非连接、零宽连接、BOM、软连字符、字连接符。
     * 【NFKC 一个都不删】——实测七种全部残留。它们在 PDF 与 OCR 文本里很常见，
     * 而 normalizedText 正是三条安全链路的匹配对象：
     * 来源校验的 NATIVE 严格子串、过敏原高风险交叉扫描、阳性行覆盖扫描。
     * 「牛(ZWSP)奶」会让这三处同时匹配不到，而过敏原字段一旦来源校验失败就会被丢弃——
     * 一个看不见的字符足以让整条过敏信息消失。
     */
    private static final String INVISIBLE_CHARACTERS = "\u200B\u200C\u200D\uFEFF\u00AD\u2060";

    /** 私用区起点；中文报告 PDF 的子集字体常把字形映射进这里，是最常见的污染源。 */
    private static final int PRIVATE_USE_AREA_START = 0xE000;

    /** 私用区终点。 */
    private static final int PRIVATE_USE_AREA_END = 0xF8FF;

    /** CJK 部首补充区起点；NFKC 对该区没有兼容分解，必须靠显式映射表。 */
    private static final int CJK_RADICAL_SUPPLEMENT_START = 0x2E80;

    /** CJK 部首补充区终点。 */
    private static final int CJK_RADICAL_SUPPLEMENT_END = 0x2EFF;

    /** 规范化文本，并只以计数形式返回未收录的部首补充区字符。 */
    public TextNormalizationResult normalize(String rawText) {
        if (rawText == null) {
            throw new IllegalArgumentException("原始文本不能为空");
        }
        String nfkcText = Normalizer.normalize(rawText, Normalizer.Form.NFKC);
        StringBuilder normalizedBuilder = new StringBuilder(nfkcText.length());
        int residualNonStandardCharacterCount = 0;
        for (int offset = 0; offset < nfkcText.length();) {
            int codePoint = nfkcText.codePointAt(offset);
            String replacement = RadicalNormalizeMap.replacement(codePoint);
            if (replacement != null) {
                normalizedBuilder.append(replacement);
            } else if (isInvisible(codePoint)) {
                // 直接丢弃，且不计入残留：它已被清除，不构成「规范化后仍污染」。
                offset += Character.charCount(codePoint);
                continue;
            } else {
                appendHalfWidth(normalizedBuilder, codePoint);
                if (isResidualPollution(codePoint)) {
                    residualNonStandardCharacterCount++;
                }
            }
            offset += Character.charCount(codePoint);
        }
        return new TextNormalizationResult(normalizedBuilder.toString(),
                residualNonStandardCharacterCount);
    }

    /**
     * 在解析器实际构造一个 Segment 时记录残留污染；同一段无论含几个残留字符都只加一。
     */
    public void recordResidualSegment(TextNormalizationResult normalizationResult) {
        if (normalizationResult == null) {
            throw new IllegalArgumentException("文本规范化结果不能为空");
        }
        if (normalizationResult.getResidualNonStandardCount() > 0) {
            residualNonStandardCount.incrementAndGet();
        }
    }

    /** 返回进程内累计的残留污染段数；不保留原文或规范化文本。 */
    public long residualNonStandardCount() {
        return residualNonStandardCount.get();
    }

    /** 判断是否为必须删除的不可见字符。 */
    private boolean isInvisible(int codePoint) {
        return codePoint <= Character.MAX_VALUE
                && INVISIBLE_CHARACTERS.indexOf((char) codePoint) >= 0;
    }

    /**
     * 判断规范化之后仍然污染的字符。
     * 【私用区必须计入】：子集字体把字形塞进 PUA 是中文报告 PDF 最常见的污染形态，
     * 只统计部首补充区会让 residualNonStandardCount 严重低报，
     * 掩盖「这份报告其实根本没抽对字」这件事。
     */
    private boolean isResidualPollution(int codePoint) {
        return (codePoint >= CJK_RADICAL_SUPPLEMENT_START && codePoint <= CJK_RADICAL_SUPPLEMENT_END)
                || (codePoint >= PRIVATE_USE_AREA_START && codePoint <= PRIVATE_USE_AREA_END);
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
