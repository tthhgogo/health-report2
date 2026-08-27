package com.example.healthreport.parse.segment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 文本规范化顺序、幂等性与原文保护测试。 */
class TextNormalizerTest {

    private final TextNormalizer normalizer = new TextNormalizer();

    @Test
    void shouldNormalizeBothRadicalRangesAndFullWidthWithoutChangingRawText() {
        String rawText = "\u2F00\u2EA9\uFF21";
        TextNormalizationResult first = normalizer.normalize(rawText);
        TextNormalizationResult second = normalizer.normalize(first.getNormalizedText());

        assertThat(rawText).isEqualTo("\u2F00\u2EA9\uFF21");
        assertThat(first.getNormalizedText()).isEqualTo("\u4E00\u738BA");
        assertThat(second.getNormalizedText()).isEqualTo(first.getNormalizedText());
        assertThat(first.getResidualNonStandardCount()).isZero();
        assertThat(second.getResidualNonStandardCount()).isZero();
    }

    @Test
    void shouldCountResidualCharactersPerResultButRecordOnlyAffectedSegments() {
        TextNormalizationResult first = normalizer.normalize("\u2E80\u2E81");
        TextNormalizationResult second = normalizer.normalize("\u2E80\u2E81");
        TextNormalizationResult clean = normalizer.normalize("clean");

        assertThat(first.getNormalizedText()).isEqualTo("\u2E80\u2E81");
        assertThat(first.getResidualNonStandardCount()).isEqualTo(2);
        assertThat(second.getResidualNonStandardCount()).isEqualTo(2);
        assertThat(normalizer.residualNonStandardCount()).isZero();

        normalizer.recordResidualSegment(first);
        normalizer.recordResidualSegment(second);
        normalizer.recordResidualSegment(clean);

        assertThat(normalizer.residualNonStandardCount()).isEqualTo(2L);
    }

    @Test
    void shouldRejectMissingNormalizationResultAtParserBoundary() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> normalizer.recordResidualSegment(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 不可见字符必须删除。【NFKC 一个都不删】——实测七种全部残留。
     * normalizedText 是三条安全链路的匹配对象（来源校验 NATIVE 严格子串、
     * 过敏原高风险交叉扫描、阳性行覆盖扫描），「牛(ZWSP)奶」会让三处同时失效，
     * 而过敏原字段一旦来源校验失败就会被丢弃——一个看不见的字符足以让整条过敏信息消失。
     */
    @Test
    void invisibleCharactersMustBeStrippedSoAllergenMatchingStillWorks() {
        String[] pollutedList = {
                "牛\u200B奶", "牛\u200C奶", "牛\u200D奶",
                "牛\uFEFF奶", "牛\u00AD奶", "牛\u2060奶"};
        for (String polluted : pollutedList) {
            TextNormalizationResult result = new TextNormalizer().normalize(polluted);
            assertThat(result.getNormalizedText())
                    .as("含不可见字符的「牛奶」必须能被子串匹配命中")
                    .isEqualTo("牛奶");
            assertThat(result.getResidualNonStandardCount())
                    .as("已清除的不可见字符不算残留污染").isZero();
        }
    }

    /**
     * 私用区必须计入 residualNonStandardCount。
     * 子集字体把字形塞进 PUA 是中文报告 PDF 最常见的污染形态；只统计部首补充区
     * 会让残留计数严重低报，掩盖「这份报告其实根本没抽对字」。
     */
    @Test
    void privateUseAreaMustCountAsResidualPollution() {
        TextNormalizationResult result = new TextNormalizer().normalize("血\uE000糖");

        assertThat(result.getResidualNonStandardCount()).isEqualTo(1);
        // 未确认映射的字符保留原样，不猜测替换。
        assertThat(result.getNormalizedText()).isEqualTo("血\uE000糖");
    }

    /**
     * 逐条验证部首补充区映射。
     * 这些字符 NFKC 没有兼容分解，只能靠显式表；映射不生效时，
     * 「⻥」这样的字会原样留在 normalizedText 里，导致「鱼」相关的过敏原匹配不到。
     */
    @Test
    void everyMappedRadicalMustNormalizeToItsIdeographAndLeaveNoResidual() {
        String[] caseList = {"\u2EC4:西", "\u2EC5:见", "\u2EC6:角", "\u2EC9:贝", "\u2ECB:车", "\u2ED3:长", "\u2ED4:门", "\u2ED8:青", "\u2ED9:韦", "\u2EDB:风", "\u2EDD:食", "\u2EE2:马", "\u2EE3:骨", "\u2EE5:鱼", "\u2EE6:鸟", "\u2EE9:黄", "\u2EEC:齐", "\u2EEE:齿", "\u2EF0:龙", "\u2EA9:王"};
        TextNormalizer normalizer = new TextNormalizer();
        for (String testCase : caseList) {
            String radical = testCase.substring(0, 1);
            String expected = testCase.substring(2);
            TextNormalizationResult result = normalizer.normalize(radical);
            assertThat(result.getNormalizedText())
                    .as("部首 U+%04X 应规范化为 %s", (int) radical.charAt(0), expected)
                    .isEqualTo(expected);
            assertThat(result.getResidualNonStandardCount())
                    .as("已收录的部首不应计入残留").isZero();
        }
    }

    /**
     * 【形近但语义不等的部首一律不得收录】。
     * ⺘ 是提手旁（对应扌）不是手，⺖ 是竖心旁（对应忄）不是心，⺙ 是反文旁不是攵。
     * 把它们替换成整字会改变文本含义；保留原字符并计入残留才是安全方向。
     * 这条断言防止后来者「照着别处的表补全」时把它们一并抄进来。
     */
    @Test
    void semanticallyDifferentRadicalsMustStayUnmappedAndCountAsResidual() {
        int[] mustNotMapList = {0x2E98, 0x2E96, 0x2E99};
        TextNormalizer normalizer = new TextNormalizer();
        for (int codePoint : mustNotMapList) {
            assertThat(RadicalNormalizeMap.replacement(codePoint))
                    .as("U+%04X 语义不等价，不得收录", codePoint).isNull();
            TextNormalizationResult result =
                    normalizer.normalize(new String(Character.toChars(codePoint)));
            assertThat(result.getResidualNonStandardCount()).isEqualTo(1);
        }
    }
}