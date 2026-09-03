package com.example.healthreport.support.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 文本规范化顺序、幂等性与原文保护测试。 */
class TextNormalizerTest {

    private final TextNormalizer normalizer = new TextNormalizer();

    @Test
    void shouldNormalizeBothRadicalRangesAndFullWidthWithoutChangingRawText() {
        String rawText = "\u2F00\u2EA9\uFF21";
        String first = normalizer.normalize(rawText);
        String second = normalizer.normalize(first);

        assertThat(rawText).isEqualTo("\u2F00\u2EA9\uFF21");
        assertThat(first).isEqualTo("\u4E00\u738BA");
        assertThat(second).isEqualTo(first);
    }

    /** 未收录的部首补充区字符原样保留，且反复规范化结果不变。 */
    @Test
    void unmappedRadicalsMustBeLeftUnchangedAndNormalizationMustBeIdempotent() {
        String first = normalizer.normalize("\u2E80\u2E81");
        String second = normalizer.normalize(first);
        String clean = normalizer.normalize("clean");

        assertThat(first).isEqualTo("\u2E80\u2E81");
        assertThat(second).isEqualTo(first);
        assertThat(clean).isEqualTo("clean");
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
            String result = new TextNormalizer().normalize(polluted);
            assertThat(result)
                    .as("含不可见字符的「牛奶」必须能被子串匹配命中")
                    .isEqualTo("牛奶");
        }
    }

    /**
     * 私用区字符原样保留，不猜测替换。
     * <p>子集字体把字形塞进 PUA 是中文报告 PDF 最常见的污染形态。
     * 我们无从知道某个 PUA 码位在这份 PDF 里代表哪个字，猜一个填进去
     * 会让 normalizedText 变成一段看似正常、实则错字的文本，比留着不认识的字符危险得多。</p>
     */
    @Test
    void privateUseAreaCharactersMustBeLeftUnchangedRatherThanGuessed() {
        String result = new TextNormalizer().normalize("血\uE000糖");

        assertThat(result).isEqualTo("血\uE000糖");
    }

    /**
     * 逐条验证部首补充区映射。
     * 这些字符 NFKC 没有兼容分解，只能靠显式表；映射不生效时，
     * 「⻥」这样的字会原样留在 normalizedText 里，导致「鱼」相关的过敏原匹配不到。
     */
    @Test
    void everyMappedRadicalMustNormalizeToItsIdeograph() {
        String[] caseList = {"\u2EC4:西", "\u2EC5:见", "\u2EC6:角", "\u2EC9:贝", "\u2ECB:车", "\u2ED3:长", "\u2ED4:门", "\u2ED8:青", "\u2ED9:韦", "\u2EDB:风", "\u2EDD:食", "\u2EE2:马", "\u2EE3:骨", "\u2EE5:鱼", "\u2EE6:鸟", "\u2EE9:黄", "\u2EEC:齐", "\u2EEE:齿", "\u2EF0:龙", "\u2EA9:王"};
        TextNormalizer normalizer = new TextNormalizer();
        for (String testCase : caseList) {
            String radical = testCase.substring(0, 1);
            String expected = testCase.substring(2);
            String result = normalizer.normalize(radical);
            assertThat(result)
                    .as("部首 U+%04X 应规范化为 %s", (int) radical.charAt(0), expected)
                    .isEqualTo(expected);
        }
    }

    /**
     * 【形近但语义不等的部首一律不得收录】。
     * ⺘ 是提手旁（对应扌）不是手，⺖ 是竖心旁（对应忄）不是心，⺙ 是反文旁不是攵。
     * 把它们替换成整字会改变文本含义；保留原字符才是安全方向。
     * 这条断言防止后来者「照着别处的表补全」时把它们一并抄进来。
     */
    @Test
    void semanticallyDifferentRadicalsMustStayUnmapped() {
        int[] mustNotMapList = {0x2E98, 0x2E96, 0x2E99};
        TextNormalizer normalizer = new TextNormalizer();
        for (int codePoint : mustNotMapList) {
            assertThat(RadicalNormalizeMap.replacement(codePoint))
                    .as("U+%04X 语义不等价，不得收录", codePoint).isNull();
            String radical = new String(Character.toChars(codePoint));
            String result = normalizer.normalize(radical);
            assertThat(result)
                    .as("U+%04X 未收录时必须原样保留，不得被替换成形近整字", codePoint)
                    .isEqualTo(radical);
        }
    }
}