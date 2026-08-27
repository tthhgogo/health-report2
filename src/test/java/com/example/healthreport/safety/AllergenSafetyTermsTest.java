package com.example.healthreport.safety;

import org.junit.jupiter.api.Test;

import java.text.Normalizer;

import static org.assertj.core.api.Assertions.assertThat;

/** 安全扫描词表的可命中性测试。 */
class AllergenSafetyTermsTest {

    /**
     * 扫描对象是 Segment.normalizedText，它已经过 NFKC 归一。
     * 因此任何「NFKC 之后会变形」的词条都永远命中不了，属于死词：
     * 写在表里给人一种覆盖到了的错觉，实际这条路径从不生效。
     *
     * <p>这条断言同时覆盖两张表，将来任何人补入全角变体都会立刻失败。</p>
     */
    @Test
    void everyTermMustSurviveNfkcOtherwiseItCanNeverMatch() {
        assertNoDeadTerms(AllergenSafetyTerms.SECTION_TERM_LIST);
        assertNoDeadTerms(AllergenSafetyTerms.ADMITTED_RESULT_MARK_LIST);
    }

    /**
     * 反证：全角「＋」在 NFKC 后会折成半角，确实会被上面的断言判为死词。
     * 没有这条，上面那条断言是否真的能失败无从得知。
     */
    @Test
    void fullWidthVariantIsProvablyDeadSoTheGuardIsMeaningful() {
        String fullWidthPlus = "＋";
        assertThat(Normalizer.normalize(fullWidthPlus, Normalizer.Form.NFKC))
                .isNotEqualTo(fullWidthPlus)
                .isEqualTo("+");
        assertThat(AllergenSafetyTerms.ADMITTED_RESULT_MARK_LIST).doesNotContain(fullWidthPlus);
    }

    private void assertNoDeadTerms(java.util.List<String> termList) {
        for (String term : termList) {
            assertThat(Normalizer.normalize(term, Normalizer.Form.NFKC))
                    .as("词条「%s」经 NFKC 会变形，在 normalizedText 上永远命中不了", term)
                    .isEqualTo(term);
        }
    }
}
