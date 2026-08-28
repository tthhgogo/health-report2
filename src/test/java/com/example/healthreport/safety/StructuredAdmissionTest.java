package com.example.healthreport.safety;

import com.example.healthreport.llm.extraction.AdviceApplicability;
import com.example.healthreport.llm.extraction.AdviceStructuredSafety;
import com.example.healthreport.parse.segment.TextNormalizer;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结构化准入政策断言。
 *
 * <p>改造前这里只有「孕期饮食必须命中」「一般饮食建议不命中」两条，
 * <b>没有任何上下文误杀用例</b>——F3 那次事故正好落在缺口上：
 * 「(孕妇和14岁以下儿童除外)」的后半截被 PDF 排版切进了饮食建议同一块，
 * 「儿童」命中黑名单，5 个维度被一起抑制、菜品推荐整个清空。
 * 本类补的就是这批负例。</p>
 */
class StructuredAdmissionTest {

    private final StructuredAdmission admission = new StructuredAdmission(new HighRiskAdviceGate(new TextNormalizer()));

    @Test
    void onlyAdviceForCurrentPatientWithNormalNatureShouldEnterStructuredChain() {
        assertThat(suppress(AdviceApplicability.CURRENT_PATIENT,
                AdviceStructuredSafety.NORMAL, "低脂、低糖饮食")).isFalse();
    }

    /**
     * 人群名词出现在<b>邻句</b>里不得抑制本条建议。
     * <p>这正是 F3 的现场：报告原文是
     * 「体重指数(BMI)=…>28为肥胖(孕妇和 / 14岁以下儿童除外)。请您戒烟忌酒，低脂、低糖饮食…」，
     * 前半句的免责说明被切进了同一个绘制单元。现在安全检查只看 adviceQuote 这一句，
     * 且词表里已不含「儿童」，两重保险。</p>
     */
    @Test
    void populationNounInNeighbouringSentenceMustNotSuppressTheAdvice() {
        assertThat(suppress(AdviceApplicability.CURRENT_PATIENT,
                AdviceStructuredSafety.NORMAL, "请您戒烟忌酒，低脂、低糖饮食，控制食量"))
                .as("建议本身没有任何限制表述，不该被抑制").isFalse();

        // 即使模型不慎把整块塞进 adviceQuote，词表里也已经没有「儿童」了。
        assertThat(suppress(AdviceApplicability.CURRENT_PATIENT,
                AdviceStructuredSafety.NORMAL,
                "14岁以下儿童除外)。请您戒烟忌酒，低脂、低糖饮食，控制食量"))
                .as("人群名词不是限制表述，不再进黑名单").isFalse();
    }

    /** 科普段落里的饮食表述不进结构化链路，靠的是适用范围而不是词表。 */
    @Test
    void generalInformationMustNotEnterStructuredChain() {
        assertThat(suppress(AdviceApplicability.GENERAL_INFORMATION,
                AdviceStructuredSafety.NORMAL, "血尿酸长期增高可致痛风")).isTrue();
    }

    /** 给他人的建议同样不进链路——家属或既往史里的人不是本次受检者。 */
    @Test
    void adviceForOtherPersonMustNotEnterStructuredChain() {
        assertThat(suppress(AdviceApplicability.OTHER_PERSON,
                AdviceStructuredSafety.NORMAL, "建议家属同查")).isTrue();
    }

    /**
     * 特殊人群建议即使确实给本人也要抑制。
     * <p>「您已绝经，应注意补钙」是 CURRENT_PATIENT + SPECIAL_POPULATION：
     * 建议给的就是本人，但涉及特殊人群，仍需专业指导。</p>
     */
    @Test
    void specialPopulationAdviceMustBeSuppressedEvenForCurrentPatient() {
        assertThat(suppress(AdviceApplicability.CURRENT_PATIENT,
                AdviceStructuredSafety.SPECIAL_POPULATION, "您已绝经，应注意补钙")).isTrue();
    }

    /** 拿不准的一律抑制：fail-closed 是刻意选的方向。 */
    @Test
    void uncertainOnEitherDimensionMustBeSuppressed() {
        assertThat(suppress(AdviceApplicability.UNCERTAIN,
                AdviceStructuredSafety.NORMAL, "低脂饮食")).isTrue();
        assertThat(suppress(AdviceApplicability.CURRENT_PATIENT,
                AdviceStructuredSafety.UNCERTAIN, "低脂饮食")).isTrue();
        assertThat(suppress(null, null, "低脂饮食")).isTrue();
    }

    /**
     * 词表是<b>不可被模型推翻</b>的那一半。
     * <p>模型判成常规建议、且确实给本人，但原文写着方向性限制时仍然抑制——
     * 「低蛋白」「限钾」这类必须由医嘱个体化，系统不能配上食材清单去推荐。</p>
     */
    @Test
    void directionalRestrictionInQuoteMustOverrideModelJudgement() {
        assertThat(suppress(AdviceApplicability.CURRENT_PATIENT,
                AdviceStructuredSafety.NORMAL, "建议优质低蛋白饮食")).isTrue();
        assertThat(suppress(AdviceApplicability.CURRENT_PATIENT,
                AdviceStructuredSafety.NORMAL, "低钾饮食，限制高钾水果")).isTrue();
    }

    /** 建议原文缺失时按 fail-safe 抑制：没有可检查的对象就不能放行。 */
    @Test
    void missingAdviceQuoteMustBeSuppressed() {
        assertThat(suppress(AdviceApplicability.CURRENT_PATIENT,
                AdviceStructuredSafety.NORMAL, null)).isTrue();
    }

    /**
     * <b>摘句绕过</b>：原文写着「低蛋白」，模型只摘走后半句并判成常规建议。
     * <p>只扫 adviceQuote 时这条会一路进到菜品推荐；扫上证据原文才拦得住。</p>
     */
    @Test
    void truncatedQuoteMustNotEscapeTheDirectionalRestrictionInEvidence() {
        List<String> evidenceTextList = Arrays.asList("建议低蛋白、低脂饮食，控制食量");

        assertThat(admission.shouldSuppress(AdviceApplicability.CURRENT_PATIENT,
                AdviceStructuredSafety.NORMAL, "低脂饮食", evidenceTextList))
                .as("摘句里没有限制词，但原文里有").isTrue();
    }

    /**
     * 反过来，证据段被 OCR 漏识一个字时，摘出的那一句仍要拦住。
     * <p>两个入参各自独立命中，任一命中即抑制——这正是同时扫两处的意义。</p>
     */
    @Test
    void quoteMustStillSuppressWhenEvidenceLostOneCharacterToOcr() {
        List<String> evidenceTextList = Arrays.asList("建议低蛋日、低脂饮食");

        assertThat(admission.shouldSuppress(AdviceApplicability.CURRENT_PATIENT,
                AdviceStructuredSafety.NORMAL, "建议低蛋白饮食", evidenceTextList)).isTrue();
    }

    /** 证据原文里没有任何方向性限制词时不得抑制：扫原文不能变成新的误杀来源。 */
    @Test
    void evidenceWithoutDirectionalRestrictionMustNotSuppress() {
        List<String> evidenceTextList = Arrays.asList(
                "体重指数(BMI)>28为肥胖(孕妇和14岁以下儿童除外)。请您戒烟忌酒，低脂、低糖饮食");

        assertThat(admission.shouldSuppress(AdviceApplicability.CURRENT_PATIENT,
                AdviceStructuredSafety.NORMAL, "低脂、低糖饮食", evidenceTextList))
                .as("F3 那一整块里没有方向性限制词，不该被抑制").isFalse();
    }

    /** 大多数用例只关心摘出的那一句；扫证据原文的用例单独显式传表。 */
    private boolean suppress(AdviceApplicability applicability,
                             AdviceStructuredSafety structuredSafety, String adviceQuote) {
        return admission.shouldSuppress(applicability, structuredSafety, adviceQuote,
                Collections.<String>emptyList());
    }
}
