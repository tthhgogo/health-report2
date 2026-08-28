package com.example.healthreport.llm.extraction;

import com.example.healthreport.assemble.indicator.IndicatorAssembler;
import com.example.healthreport.assemble.problem.ProblemAssembler;
import com.example.healthreport.assemble.dietadvice.DietAdviceAssembler;
import com.example.healthreport.assemble.dietadvice.DietAdviceInput;
import com.example.healthreport.assemble.dietadvice.DietAdviceInputFactory;
import com.example.healthreport.assemble.sort.DisplayOrder;
import com.example.healthreport.constants.AllergenKey;
import com.example.healthreport.constants.DietRequirementKey;
import com.example.healthreport.constants.EmptyStateConstants;
import com.example.healthreport.constants.NutritionKey;
import com.example.healthreport.parse.segment.Segment;
import com.example.healthreport.parse.segment.TextNormalizer;
import com.example.healthreport.parse.segment.TextSource;
import com.example.healthreport.safety.HighRiskAdviceGate;
import com.example.healthreport.safety.StructuredAdmission;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** R2/R3/R15/R19/R26~R32：排序、三个展示模块和安全降级的集中回归。 */
class DisplayAndModuleAssemblerTest {

    /** 测试报告数量，用于验证同名章节跨文件不合并和展示名前缀。 */
    private static final int TWO_REPORTS = 2;

    @Test
    void shouldUseOnlyDisplayOrderAndKeepGroupsStableAcrossBatchesAndFiles() {
        Fixture fixture = fixture();
        DisplayOrder.DisplayPlan plan = new DisplayOrder().plan(fixture.output, TWO_REPORTS);

        // R27：同文件 CURRENT 与 CONTINUATION 已由校验层解析为同一个有效章节 ID。
        assertThat(plan.groupOf(fixture.primaryIndicator))
                .isSameAs(plan.groupOf(fixture.continuationIndicator));
        // R26：同页不同章节的 sourceOrder 都为零，仍先按 groupOrder 排列。
        assertThat(plan.getSummaryConclusionList())
                .containsExactly(fixture.firstGroupSummary, fixture.secondGroupSummary);
        // R29：跨页引用条目采用引用页的最小值，组页码也取全部组内条目的最小值。
        assertThat(fixture.primaryIndicator.getPage()).isEqualTo(1);
        assertThat(plan.groupOf(fixture.primaryIndicator).getPage()).isEqualTo(1);
        // R30：两份报告的同名章节独立分组，并带报告序号。
        assertThat(plan.groupOf(fixture.primaryIndicator).getGroupKey())
                .isNotEqualTo(plan.groupOf(fixture.otherFileIndicator).getGroupKey());
        assertThat(plan.groupOf(fixture.primaryIndicator).getDisplayName()).isEqualTo("报告1-检查章节");
        assertThat(plan.groupOf(fixture.otherFileIndicator).getDisplayName()).isEqualTo("报告2-检查章节");
    }

    @Test
    void moduleOneShouldKeepModelStatusOriginalTextAndReportOverview() {
        Fixture fixture = fixture();
        IndicatorAssembler.Result result = new IndicatorAssembler(new DisplayOrder())
                .assemble(fixture.output, TWO_REPORTS);

        IndicatorAssembler.Card card = result.getGroupList().get(0).getCardList().get(0);
        // R2：Java 不因原文方向词纠正模型状态，标签文字也保持报告原文。
        assertThat(card.getStatus()).isEqualTo(IndicatorStatus.NORMAL);
        assertThat(card.getConclusionText()).isEqualTo("↑");
        assertThat(card.getRefRange()).isEqualTo("报告未提供");
        assertThat(result.getOverview().isReportOriginal()).isTrue();
        assertThat(result.getOverview().getTotalCount()).isEqualTo(8);
        assertThat(result.getOverview().getAbnormalCount()).isEqualTo(2);
        assertThat(result.getOverview().getSourceMark()).isEqualTo("（报告原文）");
    }

    @Test
    void moduleTwoShouldTrustAdmissionAndOnlyNumericItemsShouldHaveIndicatorId() {
        Fixture fixture = fixture();
        ProblemAssembler.Result result = new ProblemAssembler(new DisplayOrder())
                .assemble(fixture.output, TWO_REPORTS);

        ProblemAssembler.Item numeric = result.getItemList().get(0);
        // R31：缺失 problemName 时只连接两段报告字段，不生成归一化方向词。
        assertThat(numeric.getDisplayName()).isEqualTo("甘油三酯 ↑");
        assertThat(numeric.isDisplayNameGenerated()).isTrue();
        assertThat(numeric.getDisplayName()).doesNotContain("偏高");
        assertThat(numeric.getIndicatorId()).isNotNull();

        ProblemAssembler.Item textual = result.getItemList().get(1);
        // R3/R19：即使原文是正常陈述，只要模型准入就展示；文字项没有跳转目标。
        assertThat(textual.getRawText()).isEqualTo("未见异常");
        assertThat(textual.getIndicatorId()).isNull();
        assertThat(textual.getSourceType()).isEqualTo(ProblemAssembler.SourceType.INDICATOR_TEXTUAL);
        assertThat(result.getItemList().subList(2, result.getItemList().size()))
                .allMatch(item -> item.getSourceType() == ProblemAssembler.SourceType.SUMMARY);
    }

    @Test
    void moduleThreeShouldSuppressHighRiskTextWithoutChangingEnumOrInventingItemNumber() {
        Fixture fixture = fixture();
        DisplayOrder displayOrder = new DisplayOrder();
        DietAdviceInput input = new DietAdviceInputFactory(displayOrder)
                .create(fixture.output, TWO_REPORTS);
        DietAdviceAssembler.Result result = new DietAdviceAssembler(new StructuredAdmission(new HighRiskAdviceGate(new TextNormalizer())))
                .assemble(input);

        DietAdviceAssembler.NutritionCard card = result.getNutritionSection().getCardList().get(0);
        // R15：安全闸只关闭结构化输出，模型枚举保留用于归因。
        assertThat(card.isStructuredOutputSuppressed()).isTrue();
        assertThat(card.getEnumKey()).isEqualTo(NutritionKey.PROTEIN);
        assertThat(card.isStructuredContentAvailable()).isFalse();
        assertThat(card.getRecommendableFoodList()).isEmpty();
        // R32：itemNo 为空时不显示条号，更不能拿 sourceOrder=9 冒充。
        assertThat(card.getSourceLabel()).isEqualTo("报告1-检查章节");
        assertThat(card.getSourceLabel()).doesNotContain("第9条");

        // 内容审核快照已激活，非高危且非 OTHER 的结构化内容应进入输出。
        assertThat(result.getDietSection().getCardList().get(0).isStructuredContentAvailable())
                .isTrue();
        assertThat(result.getDietSection().getCardList().get(0).getDisplayOnlyFoodList()).isNotEmpty();
        assertThat(result.getAllergenSection().getCardList().get(0).getAvoidIngredientList()).isNotEmpty();
    }

    /**
     * 高危表述安全闸【不作用于过敏原】（2026-08-26 产品确认）。
     * 过敏忌口本身就是要展示的安全信息，被「妊娠 / 儿童」这类无关词连带抑制方向就反了。
     */
    @Test
    void allergenCardsMustNotBeSuppressedByHighRiskGate() {
        Fixture fixture = fixture();
        DisplayOrder displayOrder = new DisplayOrder();
        DietAdviceInput input = new DietAdviceInputFactory(displayOrder)
                .create(fixture.output, TWO_REPORTS);
        DietAdviceAssembler.Result result = new DietAdviceAssembler(new StructuredAdmission(new HighRiskAdviceGate(new TextNormalizer())))
                .assemble(input);

        for (DietAdviceAssembler.AllergenCard card : result.getAllergenSection().getCardList()) {
            assertThat(card.isStructuredOutputSuppressed())
                    .as("过敏原卡片不得被高危闸抑制").isFalse();
        }
    }

    @Test
    void highRiskGateShouldFailSafeWithoutBroadeningMedicalSemantics() {
        HighRiskAdviceGate gate = new HighRiskAdviceGate(new TextNormalizer());
        List<String> noEvidenceList = Collections.<String>emptyList();

        assertThat(gate.shouldSuppress(null, noEvidenceList)).as("没有可检查的对象时 fail-safe").isTrue();
        assertThat(gate.shouldSuppress("低脂饮食", null)).as("证据原文缺失同样 fail-safe").isTrue();
        assertThat(gate.shouldSuppress("优质低蛋白饮食", noEvidenceList)).as("方向性限制必须命中").isTrue();
        assertThat(gate.shouldSuppress("低钾饮食", noEvidenceList)).isTrue();
        assertThat(gate.shouldSuppress("一般饮食建议", noEvidenceList)).isFalse();
        assertThat(gate.shouldSuppress("", noEvidenceList)).isFalse();
        // 人群名词已移出词表：它不是限制表述，指向谁由 LLM-A 判断（见 StructuredAdmissionTest）。
        assertThat(gate.shouldSuppress("孕期饮食", noEvidenceList)).as("人群名词不再由词表兜底").isFalse();
        assertThat(gate.shouldSuppress("14岁以下儿童除外", noEvidenceList)).isFalse();
    }

    @Test
    void emptyModulesShouldRemainVisibleAndNeverCreateGenericAdvice() {
        ValidatedExtractionOutput emptyOutput = emptyOutput();
        DisplayOrder displayOrder = new DisplayOrder();
        IndicatorAssembler.Result moduleOne = new IndicatorAssembler(displayOrder)
                .assemble(emptyOutput, 1);
        IndicatorAssembler.Result reportOverviewWithoutCards = new IndicatorAssembler(displayOrder)
                .assemble(overviewWithoutCardsOutput(), 1);
        ProblemAssembler.Result moduleTwo = new ProblemAssembler(displayOrder)
                .assemble(emptyOutput, 1);
        DietAdviceAssembler.Result moduleThree = new DietAdviceAssembler(new StructuredAdmission(new HighRiskAdviceGate(new TextNormalizer())))
                .assemble(new DietAdviceInputFactory(displayOrder).create(emptyOutput, 1));

        assertThat(moduleOne.getOverview().getTotalCount()).isZero();
        assertThat(moduleOne.getEmptyState()).isEqualTo(EmptyStateConstants.MODULE_ONE);
        assertThat(reportOverviewWithoutCards.getOverview().getTotalCount()).isZero();
        assertThat(reportOverviewWithoutCards.getEmptyState()).isEqualTo(EmptyStateConstants.MODULE_ONE);
        assertThat(moduleTwo.getEmptyState()).isEqualTo(EmptyStateConstants.MODULE_TWO);
        assertThat(moduleThree.getAllergenSection().getCardList()).isEmpty();
        assertThat(moduleThree.getAllergenSection().getEmptyState())
                .isEqualTo(EmptyStateConstants.MODULE_THREE_ALLERGEN);
        assertThat(moduleThree.getNutritionSection().getEmptyState())
                .isEqualTo(EmptyStateConstants.MODULE_THREE_NUTRITION);
        assertThat(moduleThree.getDietSection().getEmptyState())
                .isEqualTo(EmptyStateConstants.MODULE_THREE_DIET);
    }

    private Fixture fixture() {
        String firstId = "f0-p1-s90";
        String laterId = "f0-p3-s1";
        String textualId = "f0-p1-s70";
        String firstSummaryId = "f0-p1-s60";
        String secondSummaryId = "f0-p1-s50";
        String continuationId = "f0-p2-s40";
        String dietId = "f0-p1-s30";
        String allergenId = "f0-p1-s20";
        String otherFileId = "f1-p1-s10";

        Map<String, Segment> segmentByIdMap = new LinkedHashMap<String, Segment>();
        addSegment(segmentByIdMap, firstId, "甘油三酯 ↑");
        addSegment(segmentByIdMap, laterId, "复核段");
        addSegment(segmentByIdMap, textualId, "未见异常");
        addSegment(segmentByIdMap, firstSummaryId, "总结甲");
        addSegment(segmentByIdMap, secondSummaryId, "总结乙");
        addSegment(segmentByIdMap, continuationId, "低蛋白饮食");
        addSegment(segmentByIdMap, dietId, "低脂饮食");
        addSegment(segmentByIdMap, allergenId, "过敏原阳性");
        addSegment(segmentByIdMap, otherFileId, "另一报告条目");

        List<ValidatedExtractionOutput.Section> sectionList = Arrays.asList(
                new ValidatedExtractionOutput.Section(0, 0, 0, SectionRelation.CURRENT,
                        "section-a", "检查章节", Arrays.asList(firstId, laterId, textualId,
                        firstSummaryId)),
                new ValidatedExtractionOutput.Section(0, 0, 1, SectionRelation.CURRENT,
                        "section-b", "其他章节", Arrays.asList(secondSummaryId, dietId, allergenId)),
                new ValidatedExtractionOutput.Section(0, 1, 0, SectionRelation.CONTINUATION,
                        "section-a", "检查章节", Collections.singletonList(continuationId)),
                new ValidatedExtractionOutput.Section(1, 0, 0, SectionRelation.CURRENT,
                        "section-c", "检查章节", Collections.singletonList(otherFileId)));

        ValidatedExtractionOutput.Indicator primaryIndicator = new ValidatedExtractionOutput.Indicator(
                0, 0, 0, 0, 0, 1, Arrays.asList(firstId, laterId), "甘油三酯", "1.0", "u",
                null, "↑", IndicatorConclusionBasis.REPORT_TEXT, IndicatorStatus.NORMAL, true, true, null);
        ValidatedExtractionOutput.Indicator continuationIndicator = new ValidatedExtractionOutput.Indicator(
                0, 1, 0, 0, 0, 2, Collections.singletonList(continuationId), "续页项", "2.0", "u",
                "0-3", "原文结论", IndicatorConclusionBasis.REPORT_TEXT, IndicatorStatus.HIGH, false, false, null);
        ValidatedExtractionOutput.Indicator otherFileIndicator = new ValidatedExtractionOutput.Indicator(
                1, 0, 0, 0, 0, 1, Collections.singletonList(otherFileId), "另一项", "3.0", "u",
                "0-4", "原文结论", IndicatorConclusionBasis.REPORT_TEXT, IndicatorStatus.NORMAL, false, false, null);
        ValidatedExtractionOutput.TextualFinding textualFinding = new ValidatedExtractionOutput.TextualFinding(
                0, 0, 0, 1, 0, 1, Collections.singletonList(textualId), "文字检查",
                "未见异常", IndicatorStatus.NORMAL, true);

        ValidatedExtractionOutput.SummaryConclusion firstGroupSummary =
                new ValidatedExtractionOutput.SummaryConclusion(0, 0, 0, 0, 1, 0, 1,
                        Collections.singletonList(firstSummaryId),
                        Collections.singletonList(SummaryCategory.HEALTH_PROBLEM), true);
        ValidatedExtractionOutput.SummaryConclusion secondGroupSummary =
                new ValidatedExtractionOutput.SummaryConclusion(0, 0, 1, 0, 1, 0, 1,
                        Collections.singletonList(secondSummaryId),
                        Collections.singletonList(SummaryCategory.DIET_ADVICE), true);

        ValidatedExtractionOutput.Allergen allergen = new ValidatedExtractionOutput.Allergen(
                0, 0, 1, 0, 0, 1, Collections.singletonList(allergenId), AllergenKey.SHRIMP_CRAB,
                true, "过敏原", "阳性", AllergenResultStatus.POSITIVE);
        ValidatedExtractionOutput.AdviceItem<NutritionKey> highRiskNutrition =
                new ValidatedExtractionOutput.AdviceItem<NutritionKey>(0, 1, 0, 9, null, 0, 2,
                        Collections.singletonList(continuationId), NutritionKey.PROTEIN,
                        // 面向特殊人群的建议：确实给本人，但需专业指导，不进结构化链路。
                        "妊娠期应保证优质蛋白摄入", AdviceApplicability.CURRENT_PATIENT,
                        AdviceStructuredSafety.SPECIAL_POPULATION);
        ValidatedExtractionOutput.AdviceItem<DietRequirementKey> diet =
                new ValidatedExtractionOutput.AdviceItem<DietRequirementKey>(0, 0, 1, 0, 2, 0, 1,
                        Collections.singletonList(dietId), DietRequirementKey.LOW_FAT,
                        "低脂饮食", AdviceApplicability.CURRENT_PATIENT,
                        AdviceStructuredSafety.NORMAL);

        ValidatedExtractionOutput output = new ValidatedExtractionOutput(
                Collections.singletonList(new ValidatedExtractionOutput.ReportOverview(
                        0, 0, 8, 2, Collections.singletonList(firstId))),
                sectionList,
                Arrays.asList(otherFileIndicator, continuationIndicator, primaryIndicator),
                Collections.singletonList(textualFinding),
                Arrays.asList(secondGroupSummary, firstGroupSummary),
                Collections.singletonList(allergen),
                Collections.singletonList(highRiskNutrition),
                Collections.singletonList(diet),
                new LinkedHashSet<String>(), new LinkedHashSet<String>(), segmentByIdMap);
        return new Fixture(output, primaryIndicator, continuationIndicator, otherFileIndicator,
                firstGroupSummary, secondGroupSummary);
    }

    private ValidatedExtractionOutput emptyOutput() {
        return new ValidatedExtractionOutput(
                Collections.<ValidatedExtractionOutput.ReportOverview>emptyList(),
                Collections.<ValidatedExtractionOutput.Section>emptyList(),
                Collections.<ValidatedExtractionOutput.Indicator>emptyList(),
                Collections.<ValidatedExtractionOutput.TextualFinding>emptyList(),
                Collections.<ValidatedExtractionOutput.SummaryConclusion>emptyList(),
                Collections.<ValidatedExtractionOutput.Allergen>emptyList(),
                Collections.<ValidatedExtractionOutput.AdviceItem<NutritionKey>>emptyList(),
                Collections.<ValidatedExtractionOutput.AdviceItem<DietRequirementKey>>emptyList(),
                Collections.<String>emptySet(), Collections.<String>emptySet(),
                Collections.<String, Segment>emptyMap());
    }

    private ValidatedExtractionOutput overviewWithoutCardsOutput() {
        String segmentId = "f0-p1-s0";
        Map<String, Segment> segmentByIdMap = new LinkedHashMap<String, Segment>();
        addSegment(segmentByIdMap, segmentId, "汇总 8 2");
        return new ValidatedExtractionOutput(
                Collections.singletonList(new ValidatedExtractionOutput.ReportOverview(
                        0, 0, 8, 2, Collections.singletonList(segmentId))),
                Collections.<ValidatedExtractionOutput.Section>emptyList(),
                Collections.<ValidatedExtractionOutput.Indicator>emptyList(),
                Collections.<ValidatedExtractionOutput.TextualFinding>emptyList(),
                Collections.<ValidatedExtractionOutput.SummaryConclusion>emptyList(),
                Collections.<ValidatedExtractionOutput.Allergen>emptyList(),
                Collections.<ValidatedExtractionOutput.AdviceItem<NutritionKey>>emptyList(),
                Collections.<ValidatedExtractionOutput.AdviceItem<DietRequirementKey>>emptyList(),
                Collections.<String>emptySet(), Collections.<String>emptySet(), segmentByIdMap);
    }

    private void addSegment(Map<String, Segment> segmentByIdMap, String segmentId, String rawText) {
        segmentByIdMap.put(segmentId, new Segment(segmentId, rawText, rawText,
                TextSource.NATIVE, null));
    }

    /** 测试所需引用集合，避免依赖生产对象的内容字符串做查找。 */
    private static final class Fixture {
        private final ValidatedExtractionOutput output;
        private final ValidatedExtractionOutput.Indicator primaryIndicator;
        private final ValidatedExtractionOutput.Indicator continuationIndicator;
        private final ValidatedExtractionOutput.Indicator otherFileIndicator;
        private final ValidatedExtractionOutput.SummaryConclusion firstGroupSummary;
        private final ValidatedExtractionOutput.SummaryConclusion secondGroupSummary;

        private Fixture(ValidatedExtractionOutput output,
                        ValidatedExtractionOutput.Indicator primaryIndicator,
                        ValidatedExtractionOutput.Indicator continuationIndicator,
                        ValidatedExtractionOutput.Indicator otherFileIndicator,
                        ValidatedExtractionOutput.SummaryConclusion firstGroupSummary,
                        ValidatedExtractionOutput.SummaryConclusion secondGroupSummary) {
            this.output = output;
            this.primaryIndicator = primaryIndicator;
            this.continuationIndicator = continuationIndicator;
            this.otherFileIndicator = otherFileIndicator;
            this.firstGroupSummary = firstGroupSummary;
            this.secondGroupSummary = secondGroupSummary;
        }
    }
}
