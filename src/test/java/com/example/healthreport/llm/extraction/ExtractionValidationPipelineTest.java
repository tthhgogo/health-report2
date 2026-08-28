package com.example.healthreport.llm.extraction;

import com.example.healthreport.parse.ParsedPage;
import com.example.healthreport.parse.segment.Segment;
import com.example.healthreport.parse.segment.TextNormalizer;
import com.example.healthreport.parse.segment.TextSource;
import com.example.healthreport.safety.AllergenAdmissionFilter;
import com.example.healthreport.safety.AllergenCoverageScanner;
import com.example.healthreport.safety.AllergenSuspectScanner;
import com.example.healthreport.safety.PositiveRowCoverageScanner;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import com.example.healthreport.support.PartialReason;
import com.example.healthreport.task.DegradeAccumulator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** LLM-A 校验、降级、合并和身份冲突的任务级回归测试。 */
class ExtractionValidationPipelineTest {

    private ObjectMapper objectMapper;
    private ExtractionValidationPipeline pipeline;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        TextNormalizer textNormalizer = new TextNormalizer();
        ExtractionSchemaValidator schemaValidator = new ExtractionSchemaValidator(objectMapper);
        SourceEvidenceValidator evidenceValidator =
                new SourceEvidenceValidator(textNormalizer);
        AllergenAdmissionFilter admissionFilter = new AllergenAdmissionFilter();
        pipeline = new ExtractionValidationPipeline(schemaValidator, evidenceValidator,
                new AllergenSuspectScanner(), new AllergenCoverageScanner(),
                new PositiveRowCoverageScanner(),
                admissionFilter, new ReferenceRangeParser(textNormalizer), textNormalizer);
    }

    /** 报告没印结论但结果落在参考范围内：准入，标为系统判定的正常。 */
    /** 定性结果与定性参考值一致：准入，结论依据为参考值匹配。 */
    @Test
    void qualitativeResultMatchingReferenceValueShouldBeAdmitted() {
        Segment segment = segment("f0-p1-s0", "项目甲 亚硝酸盐 阴性");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);
        ObjectNode root = validRoot(plan);
        root.putArray("indicators").add(
                valueMatchedIndicator("阴性", "阴性", "NEGATIVE", "NEGATIVE"));

        ValidatedExtractionOutput output = validate(Collections.singletonList(result(plan, root)),
                Collections.singletonList(segment), new DegradeAccumulator());

        assertThat(output.getIndicatorList()).hasSize(1);
        ValidatedExtractionOutput.Indicator indicator = output.getIndicatorList().get(0);
        assertThat(indicator.getConclusionBasis())
                .isEqualTo(IndicatorConclusionBasis.REFERENCE_VALUE_MATCH);
        assertThat(indicator.getConclusionText()).isNull();
        assertThat(indicator.getStatus()).isEqualTo(IndicatorStatus.NORMAL);
    }

    /**
     * 参考值不是单值时，结果落在允许集合内即算符合。
     * <p>真实场景：尿常规的尿胆原，检查结果「阴性」、参考值「阴性或弱」。</p>
     */
    @Test
    void qualitativeResultShouldMatchWhenReferenceValueAllowsSeveralOutcomes() {
        Segment segment = segment("f0-p1-s0", "项目甲 尿胆原 阴性或弱");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);
        ObjectNode root = validRoot(plan);
        root.putArray("indicators").add(valueMatchedIndicator("阴性", "阴性或弱",
                "NEGATIVE", "NEGATIVE", "WEAK_POSITIVE"));

        ValidatedExtractionOutput output = validate(Collections.singletonList(result(plan, root)),
                Collections.singletonList(segment), new DegradeAccumulator());

        assertThat(output.getIndicatorList()).hasSize(1);
        assertThat(output.getIndicatorList().get(0).getConclusionBasis())
                .isEqualTo(IndicatorConclusionBasis.REFERENCE_VALUE_MATCH);
    }

    /**
     * 结果不在允许集合内就是不符合，<b>绝不按字面子串放行</b>。
     * <p>「阳性」是「弱阳性」的子串——按字面包含判定会把阳性结果判成「符合参考值」，
     * 那是把异常判成正常，最危险的错法。把参考值展开成枚举集合正是为了根除它。</p>
     */
    @Test
    void positiveResultMustNotMatchWeakPositiveReferenceBySubstring() {
        Segment segment = segment("f0-p1-s0", "项目甲 弱阳性");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);
        ObjectNode root = validRoot(plan);
        root.putArray("indicators").add(
                valueMatchedIndicator("阳性", "弱阳性", "POSITIVE", "WEAK_POSITIVE"));

        ValidatedExtractionOutput output = validate(Collections.singletonList(result(plan, root)),
                Collections.singletonList(segment), new DegradeAccumulator());

        assertThat(output.getIndicatorList()).isEmpty();
    }

    /**
     * 归一化取值不同就是不匹配，Java 不认任何同义词。
     * <p>「阴性」与「未检出」是否等价属于医学判断——要认，也得由 LLM-A 在归一化时
     * 把两者统一成同一个枚举，而不是让 Java 在这里补一张同义词表。</p>
     */
    @Test
    void differentComparableValuesMustNotBeTreatedAsSynonyms() {
        Segment segment = segment("f0-p1-s0", "项目甲 阴性 未检出");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);
        ObjectNode root = validRoot(plan);
        root.putArray("indicators").add(
                valueMatchedIndicator("阴性", "未检出", "NEGATIVE", "NOT_DETECTED"));

        ValidatedExtractionOutput output = validate(Collections.singletonList(result(plan, root)),
                Collections.singletonList(segment), new DegradeAccumulator());

        assertThat(output.getIndicatorList()).isEmpty();
    }

    /**
     * 归一化枚举之外的取值由 Schema 直接拦下，整批按契约失败。
     * <p>枚举是契约的一部分，写错就是模型没遵守契约，比「悄悄丢一条指标」更该早暴露。</p>
     */
    @Test
    void comparableValuesOutsideTheApprovedEnumMustFailTheContract() {
        Segment segment = segment("f0-p1-s0", "项目甲 阴性");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);
        ObjectNode root = validRoot(plan);
        root.putArray("indicators").add(
                valueMatchedIndicator("阴性", "阴性", "NEG", "NEG"));

        assertServerError(plan, root, Collections.singletonList(segment));
    }

    @Test
    void indicatorWithoutReportConclusionShouldBeAdmittedWhenValueFallsInReferenceRange() {
        Segment segment = segment("f0-p1-s0", "项目甲 6.2 4.0~10.0");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);
        ObjectNode root = validRoot(plan);
        root.putArray("indicators").add(
                rangeAdmittedIndicator("6.2", "4.0~10.0", "6.2", "4.0", "10.0"));

        ValidatedExtractionOutput output = validate(Collections.singletonList(result(plan, root)),
                Collections.singletonList(segment), new DegradeAccumulator());

        assertThat(output.getIndicatorList()).hasSize(1);
        ValidatedExtractionOutput.Indicator indicator = output.getIndicatorList().get(0);
        assertThat(indicator.getConclusionBasis())
                .isEqualTo(IndicatorConclusionBasis.REFERENCE_RANGE_IN_RANGE);
        assertThat(indicator.getConclusionText()).as("报告没印结论，不许编一个填进来").isNull();
        assertThat(indicator.getStatus()).isEqualTo(IndicatorStatus.NORMAL);
    }

    /** 结果超出参考范围而报告没给结论：不展示，绝不自动生成异常结论。 */
    @Test
    void indicatorOutsideReferenceRangeWithoutReportConclusionMustNotBeAdmitted() {
        Segment segment = segment("f0-p1-s0", "项目甲 12.5 4.0~10.0");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);
        ObjectNode root = validRoot(plan);
        root.putArray("indicators").add(
                rangeAdmittedIndicator("12.5", "4.0~10.0", "12.5", "4.0", "10.0"));

        ValidatedExtractionOutput output = validate(Collections.singletonList(result(plan, root)),
                Collections.singletonList(segment), new DegradeAccumulator());

        assertThat(output.getIndicatorList()).isEmpty();
    }

    /**
     * 模型给的上下界必须能在参考范围原文里逐字找到。
     * <p>没有这条核验，模型可以凭空报一个宽区间让任何值都「正常」，
     * Java 比得再准也拦不住。</p>
     */
    @Test
    void fabricatedBoundsThatCannotBeQuotedFromReferenceRangeMustBeRejected() {
        Segment segment = segment("f0-p1-s0", "项目甲 12.5 4.0~10.0");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);
        ObjectNode root = validRoot(plan);
        // 报告写的是 4.0~10.0，模型却报了一个能把 12.5 装进去的假区间。
        root.putArray("indicators").add(
                rangeAdmittedIndicator("12.5", "4.0~10.0", "12.5", "1.0", "99.0"));

        ValidatedExtractionOutput output = validate(Collections.singletonList(result(plan, root)),
                Collections.singletonList(segment), new DegradeAccumulator());

        assertThat(output.getIndicatorList()).isEmpty();
    }

    /**
     * <b>省略一侧边界</b>不得放行：只报下界时上方就不设限，任何大值都会算成「在参考范围内」。
     * <p>报告写的是 4.0~10.0，结果 12.5 明显偏高；模型把上界报成 null 就能让它显示为正常。</p>
     */
    @Test
    void omittingOneBoundThatTheReferenceRangeActuallyPrintsMustBeRejected() {
        Segment segment = segment("f0-p1-s0", "项目甲 12.5 4.0~10.0");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);
        ObjectNode root = validRoot(plan);
        root.putArray("indicators").add(
                rangeAdmittedIndicator("12.5", "4.0~10.0", "12.5", "4.0", null));

        ValidatedExtractionOutput output = validate(Collections.singletonList(result(plan, root)),
                Collections.singletonList(segment), new DegradeAccumulator());

        assertThat(output.getIndicatorList()).isEmpty();
    }

    /**
     * <b>边界数字恰好是原文子串</b>也不得放行：子串核验拦不住这一手。
     * <p>报告写的是 14.0~20.0，「4.0」正好是它的子串；配上一个同样能找到的上界，
     * 12.5 就被装进了一个报告从没写过的区间。</p>
     */
    @Test
    void boundThatIsOnlyASubstringOfTheReferenceRangeMustBeRejected() {
        Segment segment = segment("f0-p1-s0", "项目甲 12.5 14.0~20.0");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);
        ObjectNode root = validRoot(plan);
        root.putArray("indicators").add(
                rangeAdmittedIndicator("12.5", "14.0~20.0", "12.5", "4.0", "20.0"));

        ValidatedExtractionOutput output = validate(Collections.singletonList(result(plan, root)),
                Collections.singletonList(segment), new DegradeAccumulator());

        assertThat(output.getIndicatorList()).isEmpty();
    }

    /** <b>开闭符号被改</b>也不得放行：报告写 <3.0，结果正好 3.0 时闭区间会判成正常。 */
    @Test
    void flippedInclusivenessAgainstTheReferenceRangeMustBeRejected() {
        Segment segment = segment("f0-p1-s0", "项目甲 3.0 <3.0");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);
        ObjectNode closed = validRoot(plan);
        closed.putArray("indicators").add(
                rangeAdmittedIndicator("3.0", "<3.0", "3.0", null, false, "3.0", true));
        assertThat(validate(Collections.singletonList(result(plan, closed)),
                Collections.singletonList(segment), new DegradeAccumulator())
                .getIndicatorList()).as("原文是开区间，不许报成闭区间").isEmpty();

        Segment lowerSegment = segment("f0-p1-s0", "项目甲 2.5 <3.0");
        ExtractionBatchPlan lowerPlan = plan(0, 0, 1, lowerSegment);
        ObjectNode open = validRoot(lowerPlan);
        open.putArray("indicators").add(
                rangeAdmittedIndicator("2.5", "<3.0", "2.5", null, false, "3.0", false));
        assertThat(validate(Collections.singletonList(result(lowerPlan, open)),
                Collections.singletonList(lowerSegment), new DegradeAccumulator())
                .getIndicatorList()).as("如实报开区间且结果在范围内时照常展示").hasSize(1);
    }

    /**
     * 参考范围给成空串时整批作废：空串是任意原文的子串，能通过一切子串式核验，
     * 等于让该指标带着一个查无实据的参考值进入展示。Schema 的 minLength 是第一道，
     * Java 侧的空白按缺失是第二道。
     */
    @Test
    void blankReferenceRangeMustNeverBeAdmitted() {
        Segment segment = segment("f0-p1-s0", "项目甲 6.2");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);
        ObjectNode root = validRoot(plan);
        root.putArray("indicators").add(
                rangeAdmittedIndicator("6.2", "", "6.2", "4.0", "10.0"));
        assertServerError(plan, root, Collections.singletonList(segment));

        ObjectNode qualitative = validRoot(plan);
        qualitative.putArray("indicators").add(
                valueMatchedIndicator("阴性", "", "NEGATIVE", "NEGATIVE"));
        assertServerError(plan, qualitative, Collections.singletonList(segment));
    }

    /** 比较用的结果值必须与 value 字段是同一个数，允许 6.2 与 6.20 的标度差异。 */
    @Test
    void measuredValueMustMatchTheDeclaredValueNumerically() {
        Segment segment = segment("f0-p1-s0", "项目甲 6.2 4.0~10.0");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);
        ObjectNode sameNumber = validRoot(plan);
        sameNumber.putArray("indicators").add(
                rangeAdmittedIndicator("6.2", "4.0~10.0", "6.20", "4.0", "10.0"));
        assertThat(validate(Collections.singletonList(result(plan, sameNumber)),
                Collections.singletonList(segment), new DegradeAccumulator())
                .getIndicatorList()).as("6.20 与 6.2 是同一个数").hasSize(1);

        ObjectNode differentNumber = validRoot(plan);
        differentNumber.putArray("indicators").add(
                rangeAdmittedIndicator("6.2", "4.0~10.0", "9.9", "4.0", "10.0"));
        assertThat(validate(Collections.singletonList(result(plan, differentNumber)),
                Collections.singletonList(segment), new DegradeAccumulator())
                .getIndicatorList()).as("比较用的数与 value 对不上就丢弃").isEmpty();
    }

    @Test
    void shouldAcceptValidOutputAndPreserveModelSemanticFlags() {
        Segment segment = segment("f0-p1-s0", "检查 项目甲 V1 结论甲");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);
        ObjectNode root = validRoot(plan);
        ObjectNode indicator = indicator(0, 0);
        indicator.put("status", "HIGH");
        indicator.put("statusJudgedByModel", true);
        indicator.put("includeInHealthProblems", false);
        root.withArray("indicators").add(indicator);

        ValidatedExtractionOutput output = validate(Collections.singletonList(result(plan, root)),
                Collections.singletonList(segment), new DegradeAccumulator());

        assertThat(output.getIndicatorList()).hasSize(1);
        assertThat(output.getIndicatorList().get(0).getStatus()).isEqualTo(IndicatorStatus.HIGH);
        assertThat(output.getIndicatorList().get(0).isIncludeInHealthProblems()).isFalse();
        assertThat(output.getIndicatorList().get(0).isStatusJudgedByModel()).isTrue();
        assertThat(output.rawTextList(output.getIndicatorList().get(0).getSegmentIdList()))
                .containsExactly(segment.getRawText());
    }

    @Test
    void statusJudgedByModelCounterShouldOnlyCountTrueItems() {
        Segment segment = segment("f0-p1-s0", "检查 项目甲 V1 结论甲 项目乙 V2 结论乙");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);
        ObjectNode root = validRoot(plan);
        ObjectNode modelJudged = indicator(0, 0);
        modelJudged.put("statusJudgedByModel", true);
        ObjectNode sourceJudged = indicator(0, 1);
        sourceJudged.put("name", "项目乙");
        sourceJudged.put("value", "V2");
        sourceJudged.put("conclusionText", "结论乙");
        sourceJudged.put("statusJudgedByModel", false);
        root.withArray("indicators").add(modelJudged).add(sourceJudged);

        ValidatedExtractionOutput output = validate(Collections.singletonList(result(plan, root)),
                Collections.singletonList(segment), new DegradeAccumulator());

        assertThat(output.getIndicatorList()).hasSize(2);
    }

    @Test
    void shouldPreserveNormalStatusWhenSourceConclusionSaysHigh() {
        Segment segment = segment("f0-p1-s0", "检查 项目甲 V1 ↑偏高");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);
        ObjectNode root = validRoot(plan);
        ObjectNode item = indicator(0, 0);
        item.put("conclusionText", "↑偏高");
        item.put("status", "NORMAL");
        root.withArray("indicators").add(item);
        DegradeAccumulator accumulator = new DegradeAccumulator();

        ValidatedExtractionOutput output = validate(Collections.singletonList(result(plan, root)),
                Collections.singletonList(segment), accumulator);

        assertThat(output.getIndicatorList()).hasSize(1);
        assertThat(output.getIndicatorList().get(0).getStatus()).isEqualTo(IndicatorStatus.NORMAL);
        assertNoValidationSideEffects(accumulator);
    }

    @Test
    void shouldPreserveHealthProblemAdmissionWhenSourceSaysNoAbnormality() {
        Segment segment = segment("f0-p1-s0", "检查 项目甲 V1 未见异常");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);
        ObjectNode root = validRoot(plan);
        ObjectNode item = indicator(0, 0);
        item.put("conclusionText", "未见异常");
        item.put("includeInHealthProblems", true);
        root.withArray("indicators").add(item);
        DegradeAccumulator accumulator = new DegradeAccumulator();

        ValidatedExtractionOutput output = validate(Collections.singletonList(result(plan, root)),
                Collections.singletonList(segment), accumulator);

        assertThat(output.getIndicatorList()).hasSize(1);
        assertThat(output.getIndicatorList().get(0).isIncludeInHealthProblems()).isTrue();
        assertNoValidationSideEffects(accumulator);
    }

    @Test
    void shouldNullOnlyInvalidProblemNameAndKeepIndicator() {
        Segment segment = segment("f0-p1-s0", "检查 项目甲 V1 结论甲");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);
        ObjectNode root = validRoot(plan);
        ObjectNode item = indicator(0, 0);
        item.put("problemName", "模型自造问题名");
        root.withArray("indicators").add(item);

        ValidatedExtractionOutput output = validate(Collections.singletonList(result(plan, root)),
                Collections.singletonList(segment), new DegradeAccumulator());

        assertThat(output.getIndicatorList()).hasSize(1);
        assertThat(output.getIndicatorList().get(0).getProblemName()).isNull();
    }

    @Test
    void shouldRejectMissingRequiredWrongTypeDuplicateAndTooManyReferences() {
        Segment segment = segment("f0-p1-s0", "检查 项目甲 V1 结论甲");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);
        List<ObjectNode> invalidRootList = new ArrayList<ObjectNode>();

        ObjectNode missingRequired = validRoot(plan);
        missingRequired.remove("dietRequirements");
        invalidRootList.add(missingRequired);

        ObjectNode wrongType = validRoot(plan);
        ObjectNode wrongTypeIndicator = indicator(0, 0);
        wrongTypeIndicator.putArray("blockRefs").add("0");
        wrongType.withArray("indicators").add(wrongTypeIndicator);
        invalidRootList.add(wrongType);

        ObjectNode duplicate = validRoot(plan);
        ObjectNode duplicateIndicator = indicator(0, 0);
        duplicateIndicator.putArray("blockRefs").add(0).add(0);
        duplicate.withArray("indicators").add(duplicateIndicator);
        invalidRootList.add(duplicate);

        ObjectNode tooMany = validRoot(plan);
        ObjectNode tooManyIndicator = indicator(0, 0);
        ArrayNode tooManyRefArray = tooManyIndicator.putArray("blockRefs");
        for (int index = 0; index < 33; index++) {
            tooManyRefArray.add(index);
        }
        tooMany.withArray("indicators").add(tooManyIndicator);
        invalidRootList.add(tooMany);

        for (ObjectNode invalidRoot : invalidRootList) {
            assertServerError(plan, invalidRoot, Collections.singletonList(segment));
        }
    }

    @Test
    void shouldTreatOutOfBatchReferenceAsMissingEvidence() {
        Segment segment = segment("f0-p1-s0", "检查 项目甲 V1 结论甲");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);
        ObjectNode root = validRoot(plan);
        ObjectNode item = indicator(0, 0);
        item.putArray("blockRefs").add(1);
        root.withArray("indicators").add(item);

        ValidatedExtractionOutput output = validate(Collections.singletonList(result(plan, root)),
                Collections.singletonList(segment), new DegradeAccumulator());

        assertThat(output.getIndicatorList()).isEmpty();
    }

    @Test
    void shouldFailWholeBatchWhenSectionIndexDoesNotEqualArrayIndex() {
        Segment segment = segment("f0-p1-s0", "检查");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);
        ObjectNode root = validRoot(plan);
        ((ObjectNode) root.withArray("sections").get(0)).put("sectionIndex", 1);

        assertServerError(plan, root, Collections.singletonList(segment));
    }

    @Test
    void shouldDropOnlyItemWithMissingSectionReference() {
        Segment segment = segment("f0-p1-s0", "检查 项目甲 V1 结论甲");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);
        ObjectNode root = validRoot(plan);
        root.withArray("indicators").add(indicator(1, 0));
        root.withArray("indicators").add(indicator(0, 1));

        ValidatedExtractionOutput output = validate(Collections.singletonList(result(plan, root)),
                Collections.singletonList(segment), new DegradeAccumulator());

        assertThat(output.getIndicatorList()).hasSize(1);
        assertThat(output.getIndicatorList().get(0).getItemIndex()).isEqualTo(1);
    }

    @Test
    void shouldDegradeAllergenWithMissingSectionReference() {
        Segment segment = segment("f0-p1-s0", "检查 样本项 阳性");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);
        ObjectNode root = validRoot(plan);
        root.withArray("allergens").add(allergen(1, 0, "阳性", "POSITIVE", "OTHER", true));
        DegradeAccumulator accumulator = new DegradeAccumulator();

        ValidatedExtractionOutput output = validate(Collections.singletonList(result(plan, root)),
                Collections.singletonList(segment), accumulator);

        assertThat(output.getAllergenList()).isEmpty();
        assertThat(accumulator.primaryReason()).isEqualTo(PartialReason.ALLERGEN_SUSPECT_MISS);
    }

    @Test
    void shouldNotSynthesizeNumericItemWithoutModelConclusion() {
        Segment segment = segment("f0-p1-s0", "检查 项目甲 V1");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);

        ValidatedExtractionOutput output = validate(
                Collections.singletonList(result(plan, validRoot(plan))),
                Collections.singletonList(segment), new DegradeAccumulator());

        assertThat(output.getIndicatorList()).isEmpty();
        assertThat(output.getTextualFindingList()).isEmpty();
    }

    @Test
    void shouldDegradeInvalidSectionDisplayNameWithoutDroppingContent() {
        Segment segment = segment("f0-p1-s0", "检查 项目甲 V1 结论甲");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);
        ObjectNode root = validRoot(plan);
        ((ObjectNode) root.withArray("sections").get(0)).put("sectionName", "模型概括标题");
        root.withArray("indicators").add(indicator(0, 0));

        ValidatedExtractionOutput output = validate(Collections.singletonList(result(plan, root)),
                Collections.singletonList(segment), new DegradeAccumulator());

        assertThat(output.getSectionList().get(0).getDisplayName()).isEqualTo("未标注章节");
        assertThat(output.getSectionList().get(0).getSectionSegmentId()).startsWith("X-");
        assertThat(output.getIndicatorList()).hasSize(1);
    }

    @Test
    void shouldKeepOverviewOnlyWhenBothPrintedNumbersMatch() {
        Segment segment = segment("f0-p1-s0", "检查 汇总 12 3");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);
        ObjectNode validRoot = validRoot(plan);
        overview(validRoot, 12, 3, 0);

        assertThat(validate(Collections.singletonList(result(plan, validRoot)),
                Collections.singletonList(segment), new DegradeAccumulator())
                .getReportOverviewList()).hasSize(1);

        ObjectNode invalidRoot = validRoot(plan);
        overview(invalidRoot, 12, 8, 0);
        assertThat(validate(Collections.singletonList(result(plan, invalidRoot)),
                Collections.singletonList(segment), new DegradeAccumulator())
                .getReportOverviewList()).isEmpty();
    }

    @Test
    void shouldDegradeAndDropAllergenWhenSourceValidationFails() {
        Segment segment = segment("f0-p1-s0", "检查 样本项 阴性");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);
        ObjectNode root = validRoot(plan);
        root.withArray("allergenSectionBlockRefs").add(0);
        root.withArray("allergens").add(allergen(0, 0, "不存在结果", "POSITIVE", "OTHER", true));
        DegradeAccumulator accumulator = new DegradeAccumulator();

        ValidatedExtractionOutput output = validate(Collections.singletonList(result(plan, root)),
                Collections.singletonList(segment), accumulator);

        assertThat(output.getAllergenList()).isEmpty();
        assertThat(accumulator.primaryReason()).isEqualTo(PartialReason.ALLERGEN_SUSPECT_MISS);
    }

    @Test
    void shouldDegradeWhenAllergenDataIsNotCoveredByAnyItem() {
        Segment segment = segment("f0-p1-s0", "检查 样本行");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);
        ObjectNode root = validRoot(plan);
        root.withArray("allergenSectionBlockRefs").add(0);
        root.withArray("allergenDataBlockRefs").add(0);
        DegradeAccumulator accumulator = new DegradeAccumulator();

        validate(Collections.singletonList(result(plan, root)), Collections.singletonList(segment),
                accumulator);

        assertThat(accumulator.primaryReason()).isEqualTo(PartialReason.ALLERGEN_SUSPECT_MISS);
    }

    @Test
    void shouldFailWhenAllergenCoverageSetsViolateSubsetContract() {
        Segment first = segment("f0-p1-s0", "检查");
        Segment second = segment("f0-p1-s1", "样本项 阳性");
        ExtractionBatchPlan plan = plan(0, 0, 1, first, second);
        ObjectNode root = validRoot(plan);
        root.withArray("allergenSectionBlockRefs").add(0);
        root.withArray("allergenDataBlockRefs").add(1);

        assertServerError(plan, root, Arrays.asList(first, second));
    }

    @Test
    void shouldDeriveFormalFoodFlagAndTrustOtherAfterAdmission() {
        Segment segment = segment("f0-p1-s0", "检查 牛奶 阳性 样本项 临界");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);
        ObjectNode root = validRoot(plan);
        root.withArray("allergenSectionBlockRefs").add(0);
        root.withArray("allergenDataBlockRefs").add(0);
        root.withArray("allergens").add(allergen(0, 0, "阳性", "POSITIVE", "MILK", false));
        root.withArray("allergens").add(allergen(0, 1, "临界", "BORDERLINE", "OTHER", true));

        ValidatedExtractionOutput output = validate(Collections.singletonList(result(plan, root)),
                Collections.singletonList(segment), new DegradeAccumulator());

        assertThat(output.getAllergenList()).hasSize(2);
        assertThat(output.getAllergenList().get(0).isFoodBorne()).isTrue();
        assertThat(output.getAllergenList().get(1).isFoodBorne()).isTrue();
    }

    @Test
    void shouldNullInvalidPatientFieldWithoutCreatingIdentityConflict() {
        Segment first = segment("f0-p1-s0", "检查 样本甲");
        Segment second = segment("f1-p1-s0", "检查 无身份字段");
        ExtractionBatchPlan firstPlan = plan(0, 0, 2, first);
        ExtractionBatchPlan secondPlan = plan(1, 1, 2, second);
        ObjectNode firstRoot = validRoot(firstPlan);
        patient(firstRoot, "样本甲", 0);
        ObjectNode secondRoot = validRoot(secondPlan);
        patient(secondRoot, "样本乙", 0);

        ValidatedExtractionOutput output = validate(Arrays.asList(result(firstPlan, firstRoot),
                        result(secondPlan, secondRoot)), Arrays.asList(first, second),
                new DegradeAccumulator());

        assertThat(output).isNotNull();
    }

    @Test
    void shouldNullDifferentOcrGendersWithoutEvidenceAndAvoidIdentityConflict() {
        DegradeAccumulator identityAccumulator = new DegradeAccumulator();
        Segment first = segment("f0-p1-s0", "检查项目", TextSource.OCR);
        Segment second = segment("f1-p1-s0", "检查项目", TextSource.OCR);
        ExtractionBatchPlan firstPlan = plan(0, 0, 2, first);
        ExtractionBatchPlan secondPlan = plan(1, 1, 2, second);
        ObjectNode firstRoot = validRoot(firstPlan);
        gender(firstRoot, "男", 0);
        ObjectNode secondRoot = validRoot(secondPlan);
        gender(secondRoot, "女", 0);

        ValidatedExtractionOutput output = validate(Arrays.asList(result(firstPlan, firstRoot),
                        result(secondPlan, secondRoot)), Arrays.asList(first, second),
                identityAccumulator);

        // 两份 OCR 性别都没有证据支撑，应各自降为 null 而不是互相冲突。
        assertThat(output).isNotNull();
        assertThat(identityAccumulator.partial())
                .as("无证据的性别不参与同一性判断，不得因此触发降级").isFalse();
    }

    @Test
    void shouldRejectPatientValueWithoutEvidenceAtSchemaLayer() {
        Segment segment = segment("f0-p1-s0", "检查");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);
        ObjectNode root = validRoot(plan);
        ObjectNode patient = (ObjectNode) root.path("patient");
        patient.put("name", "样本甲");

        assertServerError(plan, root, Collections.singletonList(segment));
    }

    @Test
    void shouldRejectConflictingSourceValidatedIdentity() {
        Segment first = segment("f0-p1-s0", "检查 样本甲");
        Segment second = segment("f1-p1-s0", "检查 样本乙");
        ExtractionBatchPlan firstPlan = plan(0, 0, 2, first);
        ExtractionBatchPlan secondPlan = plan(1, 1, 2, second);
        ObjectNode firstRoot = validRoot(firstPlan);
        patient(firstRoot, "样本甲", 0);
        ObjectNode secondRoot = validRoot(secondPlan);
        patient(secondRoot, "样本乙", 0);

        assertThatThrownBy(() -> validate(Arrays.asList(result(firstPlan, firstRoot),
                        result(secondPlan, secondRoot)), Arrays.asList(first, second),
                new DegradeAccumulator()))
                .isInstanceOfSatisfying(HealthReportException.class,
                        exception -> assertThat(exception.getFailCode())
                                .isEqualTo(FailCode.IDENTITY_MISMATCH));
    }

    @Test
    void shouldIgnoreWhitespaceWhenComparingSourceValidatedNames() {
        Segment first = segment("f0-p1-s0", "检查 样 本甲");
        Segment second = segment("f1-p1-s0", "检查 样本甲");
        ExtractionBatchPlan firstPlan = plan(0, 0, 2, first);
        ExtractionBatchPlan secondPlan = plan(1, 1, 2, second);
        ObjectNode firstRoot = validRoot(firstPlan);
        patient(firstRoot, "样 本甲", 0);
        ObjectNode secondRoot = validRoot(secondPlan);
        patient(secondRoot, "样本甲", 0);

        ValidatedExtractionOutput output = validate(Arrays.asList(result(firstPlan, firstRoot),
                        result(secondPlan, secondRoot)), Arrays.asList(first, second),
                new DegradeAccumulator());

        assertThat(output).isNotNull();
    }

    @Test
    void shouldDeduplicateOnlyOverlappingBatchesBySegmentAndItemIndex() {
        Segment overlapping = segment("f0-p1-s0", "检查 项目甲 V1 结论甲");
        Segment nonOverlapping = segment("f0-p2-s0", "检查 项目甲 V1 结论甲");
        ExtractionBatchPlan firstPlan = plan(0, 0, 3, overlapping);
        ExtractionBatchPlan secondPlan = plan(0, 1, 3, overlapping);
        ExtractionBatchPlan thirdPlan = plan(0, 2, 3, nonOverlapping);
        ObjectNode firstRoot = validRoot(firstPlan);
        ObjectNode secondRoot = validRoot(secondPlan);
        ObjectNode thirdRoot = validRoot(thirdPlan);
        firstRoot.withArray("indicators").add(indicator(0, 0));
        secondRoot.withArray("indicators").add(indicator(0, 0));
        thirdRoot.withArray("indicators").add(indicator(0, 0));

        ValidatedExtractionOutput output = validate(Arrays.asList(result(firstPlan, firstRoot),
                        result(secondPlan, secondRoot), result(thirdPlan, thirdRoot)),
                Arrays.asList(overlapping, nonOverlapping), new DegradeAccumulator());

        assertThat(output.getIndicatorList()).hasSize(2);
        assertThat(output.getIndicatorList()).extracting(ValidatedExtractionOutput.Indicator::getPage)
                .containsExactly(1, 2);
    }

    @Test
    void shouldUseMinimumReferencedPageForCrossPageItem() {
        Segment pageOne = segment("f0-p1-s8", "检查 项目甲");
        Segment pageThree = segment("f0-p3-s9", "V1 结论甲");
        BatchAddressing addressing = new BatchAddressing(0, Arrays.asList(
                new ParsedPage(1, Collections.singletonList(pageOne), null, false),
                new ParsedPage(3, Collections.singletonList(pageThree), null, false)));
        ExtractionBatchInput input = new ExtractionBatchInput("prompt", "version", 0, 0, 1,
                addressing.getPageList());
        ExtractionBatchPlan plan = new ExtractionBatchPlan(input, addressing);
        ObjectNode root = validRoot(plan);
        ObjectNode item = indicator(0, 0);
        item.withArray("blockRefs").add(1);
        root.withArray("indicators").add(item);

        ValidatedExtractionOutput output = validate(Collections.singletonList(result(plan, root)),
                Arrays.asList(pageOne, pageThree), new DegradeAccumulator());

        assertThat(output.getIndicatorList()).hasSize(1);
        assertThat(output.getIndicatorList().get(0).getPage()).isEqualTo(1);
    }

    @Test
    void shouldMechanicallyInheritOnlyContinuationWithinSameFile() {
        Segment first = segment("f0-p1-s0", "检查");
        Segment second = segment("f0-p2-s0", "续页内容");
        ExtractionBatchPlan firstPlan = plan(0, 0, 2, first);
        ExtractionBatchPlan secondPlan = plan(0, 1, 2, second);
        ObjectNode firstRoot = validRoot(firstPlan);
        ObjectNode secondRoot = validRoot(secondPlan);
        ObjectNode continuation = (ObjectNode) secondRoot.withArray("sections").get(0);
        continuation.put("sectionName", "检查");
        continuation.put("sectionRelation", "CONTINUATION");
        continuation.putNull("sectionBlockRef");

        ValidatedExtractionOutput output = validate(Arrays.asList(result(firstPlan, firstRoot),
                        result(secondPlan, secondRoot)), Arrays.asList(first, second),
                new DegradeAccumulator());

        assertThat(output.getSectionList()).hasSize(2);
        assertThat(output.getSectionList().get(1).getSectionSegmentId())
                .isEqualTo(output.getSectionList().get(0).getSectionSegmentId());
        assertThat(output.getSectionList().get(1).getDisplayName()).isEqualTo("检查");
    }

    @Test
    void shouldTreatFirstBatchContinuationAsUnknown() {
        Segment segment = segment("f0-p1-s0", "续页内容");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);
        ObjectNode root = validRoot(plan);
        ObjectNode continuation = (ObjectNode) root.withArray("sections").get(0);
        continuation.put("sectionName", "检查");
        continuation.put("sectionRelation", "CONTINUATION");
        continuation.putNull("sectionBlockRef");

        ValidatedExtractionOutput output = validate(Collections.singletonList(result(plan, root)),
                Collections.singletonList(segment), new DegradeAccumulator());

        assertThat(output.getSectionList().get(0).getRelation()).isEqualTo(SectionRelation.UNKNOWN);
        assertThat(output.getSectionList().get(0).getDisplayName()).isEqualTo("未标注章节");
    }

    @Test
    void shouldNeverInheritContinuationFromPreviousFile() {
        Segment firstFileSegment = segment("f0-p1-s0", "检查");
        Segment secondFileSegment = segment("f1-p1-s0", "续页内容");
        ExtractionBatchPlan firstFilePlan = plan(0, 0, 1, firstFileSegment);
        ExtractionBatchPlan secondFilePlan = plan(1, 0, 1, secondFileSegment);
        ObjectNode firstFileRoot = validRoot(firstFilePlan);
        ObjectNode secondFileRoot = validRoot(secondFilePlan);
        ObjectNode continuation = (ObjectNode) secondFileRoot.withArray("sections").get(0);
        continuation.put("sectionName", "检查");
        continuation.put("sectionRelation", "CONTINUATION");
        continuation.putNull("sectionBlockRef");

        ValidatedExtractionOutput output = validate(Arrays.asList(
                        result(firstFilePlan, firstFileRoot), result(secondFilePlan, secondFileRoot)),
                Arrays.asList(firstFileSegment, secondFileSegment), new DegradeAccumulator());

        assertThat(output.getSectionList()).hasSize(2);
        assertThat(output.getSectionList().get(1).getRelation()).isEqualTo(SectionRelation.UNKNOWN);
        assertThat(output.getSectionList().get(1).getDisplayName()).isEqualTo("未标注章节");
        assertThat(output.getSectionList().get(1).getSectionSegmentId())
                .isNotEqualTo(output.getSectionList().get(0).getSectionSegmentId());
    }

    private ValidatedExtractionOutput validate(List<ExtractionBatchResult> resultList,
                                         List<Segment> segmentList,
                                         DegradeAccumulator accumulator) {
        return pipeline.validateAndMerge(resultList, segmentList, accumulator);
    }

    private void assertServerError(ExtractionBatchPlan plan, ObjectNode root, List<Segment> segmentList) {
        assertThatThrownBy(() -> validate(Collections.singletonList(result(plan, root)),
                segmentList, new DegradeAccumulator()))
                .isInstanceOfSatisfying(HealthReportException.class,
                        exception -> assertThat(exception.getFailCode())
                                .isEqualTo(FailCode.SERVER_ERROR));
    }

    private ObjectNode validRoot(ExtractionBatchPlan plan) {
        ObjectNode root = objectMapper.createObjectNode();
        ExtractionBatchInput input = plan.getInput();
        root.put("fileIndex", input.getFileIndex());
        root.put("batchIndex", input.getBatchIndex());
        root.put("batchCount", input.getBatchCount());
        root.put("batchStatus", "OK");
        ObjectNode patient = root.putObject("patient");
        patient.putNull("name");
        patient.putArray("nameBlockRefs");
        patient.putNull("gender");
        patient.putArray("genderBlockRefs");
        root.putNull("reportOverview");
        ObjectNode section = root.putArray("sections").addObject();
        section.put("sectionName", "检查");
        section.put("sectionIndex", 0);
        section.put("sectionRelation", "CURRENT");
        section.put("sectionBlockRef", 0);
        root.putArray("allergenSectionBlockRefs");
        root.putArray("allergenDataBlockRefs");
        root.putArray("indicators");
        root.putArray("textualFindings");
        root.putArray("summaryConclusions");
        root.putArray("allergens");
        root.putArray("nutritionSupplements");
        root.putArray("dietRequirements");
        return root;
    }

    private ObjectNode indicator(int sectionIndex, int itemIndex) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", "项目甲");
        node.put("value", "V1");
        node.putNull("unit");
        node.putNull("refRange");
        node.put("conclusionText", "结论甲");
        node.put("conclusionBasis", "REPORT_TEXT");
        node.putNull("rangeComparison");
        node.putNull("valueMatch");
        node.put("status", "NORMAL");
        node.put("statusJudgedByModel", false);
        node.put("includeInHealthProblems", true);
        node.putNull("problemName");
        node.put("sectionIndex", sectionIndex);
        node.put("orderInSection", 0);
        node.put("itemIndex", itemIndex);
        node.putArray("blockRefs").add(0);
        return node;
    }

    /**
     * 造一条走参考范围准入的指标：报告只印了结果与参考值，没印结论。
     *
     * @param value 检查结果，必须能在证据段里回切
     * @param refRange 参考范围原文，必须能在证据段里回切
     */
    private ObjectNode rangeAdmittedIndicator(String value, String refRange, String measuredValue,
                                              String lowerBound, String upperBound) {
        return rangeAdmittedIndicator(value, refRange, measuredValue, lowerBound,
                lowerBound != null, upperBound, upperBound != null);
    }

    /** 开闭由用例显式指定，用于核验模型篡改开闭符号的情形。 */
    private ObjectNode rangeAdmittedIndicator(String value, String refRange, String measuredValue,
                                              String lowerBound, boolean lowerInclusive,
                                              String upperBound, boolean upperInclusive) {
        ObjectNode node = indicator(0, 0);
        node.put("value", value);
        node.put("refRange", refRange);
        node.putNull("conclusionText");
        node.put("conclusionBasis", "REFERENCE_RANGE_IN_RANGE");
        node.put("status", "NORMAL");
        node.put("includeInHealthProblems", false);
        ObjectNode comparison = node.putObject("rangeComparison");
        comparison.put("measuredValue", measuredValue);
        if (lowerBound == null) {
            comparison.putNull("lowerBound");
        } else {
            comparison.put("lowerBound", lowerBound);
        }
        comparison.put("lowerInclusive", lowerInclusive);
        if (upperBound == null) {
            comparison.putNull("upperBound");
        } else {
            comparison.put("upperBound", upperBound);
        }
        comparison.put("upperInclusive", upperInclusive);
        return node;
    }

    /**
     * 造一条走定性参考值准入的指标。
     *
     * @param acceptableReferenceValues 参考值允许的全部取值，可为多个（如「阴性或弱」）
     */
    private ObjectNode valueMatchedIndicator(String value, String refRange,
                                             String resultComparable,
                                             String... acceptableReferenceValues) {
        ObjectNode node = indicator(0, 0);
        node.put("value", value);
        node.put("refRange", refRange);
        node.putNull("conclusionText");
        node.put("conclusionBasis", "REFERENCE_VALUE_MATCH");
        node.put("status", "NORMAL");
        node.put("includeInHealthProblems", false);
        node.putNull("rangeComparison");
        ObjectNode match = node.putObject("valueMatch");
        match.put("resultComparableValue", resultComparable);
        ArrayNode acceptableArray = match.putArray("acceptableReferenceValues");
        for (String acceptableValue : acceptableReferenceValues) {
            acceptableArray.add(acceptableValue);
        }
        return node;
    }

    private ObjectNode allergen(int sectionIndex, int itemIndex, String rawResult,
                                String resultStatus, String enumKey, boolean modelFoodBorne) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("enumKey", enumKey);
        node.put("isFoodBorne", modelFoodBorne);
        node.put("rawName", enumKey.equals("MILK") ? "牛奶" : "样本项");
        node.put("rawResult", rawResult);
        node.put("resultStatus", resultStatus);
        node.put("sectionIndex", sectionIndex);
        node.put("sourceOrder", 0);
        node.put("itemIndex", itemIndex);
        node.putArray("blockRefs").add(0);
        return node;
    }

    private void patient(ObjectNode root, String name, int blockRef) {
        ObjectNode patient = (ObjectNode) root.path("patient");
        patient.put("name", name);
        patient.putArray("nameBlockRefs").add(blockRef);
    }

    private void gender(ObjectNode root, String gender, int blockRef) {
        ObjectNode patient = (ObjectNode) root.path("patient");
        patient.put("gender", gender);
        patient.putArray("genderBlockRefs").add(blockRef);
    }

    private void assertNoValidationSideEffects(DegradeAccumulator accumulator) {
        assertThat(accumulator.partial()).isFalse();
    }

    private void overview(ObjectNode root, int totalCount, int abnormalCount, int blockRef) {
        ObjectNode overview = objectMapper.createObjectNode();
        overview.put("totalCount", totalCount);
        overview.put("abnormalCount", abnormalCount);
        overview.putArray("blockRefs").add(blockRef);
        root.set("reportOverview", overview);
    }

    private ExtractionBatchResult result(ExtractionBatchPlan plan, ObjectNode root) {
        return new ExtractionBatchResult(plan, BatchStatus.OK, root.toString());
    }

    private ExtractionBatchPlan plan(int fileIndex, int batchIndex, int batchCount,
                               Segment... segmentArray) {
        List<Segment> segmentList = Arrays.asList(segmentArray);
        int page = page(segmentArray[0].getSegmentId());
        ParsedPage parsedPage = new ParsedPage(page, segmentList, null, false);
        BatchAddressing addressing = new BatchAddressing(fileIndex,
                Collections.singletonList(parsedPage));
        ExtractionBatchInput input = new ExtractionBatchInput("prompt", "version", fileIndex, batchIndex,
                batchCount, addressing.getPageList());
        return new ExtractionBatchPlan(input, addressing);
    }

    private int page(String segmentId) {
        int pageMarker = segmentId.indexOf("-p");
        int sequenceMarker = segmentId.indexOf("-s", pageMarker + 2);
        return Integer.parseInt(segmentId.substring(pageMarker + 2, sequenceMarker));
    }

    private Segment segment(String segmentId, String text) {
        return new Segment(segmentId, text, text, TextSource.NATIVE, null);
    }

    private Segment segment(String segmentId, String text, TextSource source) {
        return new Segment(segmentId, text, text, source, null);
    }
}
