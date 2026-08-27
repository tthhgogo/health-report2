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
    private ExtractionValidationCounters counters;
    private ExtractionValidationPipeline pipeline;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        counters = new ExtractionValidationCounters();
        TextNormalizer textNormalizer = new TextNormalizer();
        ExtractionSchemaValidator schemaValidator = new ExtractionSchemaValidator(objectMapper, counters);
        SourceEvidenceValidator evidenceValidator =
                new SourceEvidenceValidator(textNormalizer, counters);
        AllergenAdmissionFilter admissionFilter = new AllergenAdmissionFilter(counters);
        pipeline = new ExtractionValidationPipeline(schemaValidator, evidenceValidator,
                new AllergenSuspectScanner(counters), new AllergenCoverageScanner(counters),
                new PositiveRowCoverageScanner(counters),
                admissionFilter, counters, textNormalizer);
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
        assertThat(counters.getStatusJudgedByModelCount().get()).isEqualTo(1L);
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
        assertThat(counters.getStatusJudgedByModelCount().get()).isEqualTo(1L);
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
        assertThat(counters.getSchemaMissCount().get()).isEqualTo(4L);
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
        assertThat(counters.getEvidenceMissCount().get()).isEqualTo(1L);
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
        assertThat(counters.getSectionRefMissCount().get()).isEqualTo(1L);
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
        assertThat(counters.getSectionRefMissCount().get()).isEqualTo(1L);
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
        assertThat(counters.getEvidenceMissCount().get()).isEqualTo(1L);
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
                new DegradeAccumulator());

        assertThat(output).isNotNull();
        assertThat(counters.getOcrFuzzyMatchCount().get()).isZero();
    }

    @Test
    void shouldRejectPatientValueWithoutEvidenceAtSchemaLayer() {
        Segment segment = segment("f0-p1-s0", "检查");
        ExtractionBatchPlan plan = plan(0, 0, 1, segment);
        ObjectNode root = validRoot(plan);
        ObjectNode patient = (ObjectNode) root.path("patient");
        patient.put("name", "样本甲");

        assertServerError(plan, root, Collections.singletonList(segment));
        assertThat(counters.getSchemaMissCount().get()).isEqualTo(1L);
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
        assertThat(counters.getSectionUnknownCount().get()).isEqualTo(1L);
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
        assertThat(counters.getSectionUnknownCount().get()).isEqualTo(1L);
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
        assertThat(counters.getSchemaMissCount().get()).isZero();
        assertThat(counters.getEvidenceMissCount().get()).isZero();
        assertThat(counters.getOcrFuzzyMatchCount().get()).isZero();
        assertThat(counters.getAllergenSuspectMissCount().get()).isZero();
        assertThat(counters.getAllergenPositiveUncoveredCount().get()).isZero();
        assertThat(counters.getAllergenUnknownCount().get()).isZero();
        assertThat(counters.getSectionRefMissCount().get()).isZero();
        assertThat(counters.getStatusJudgedByModelCount().get()).isZero();
        assertThat(counters.getSectionUnknownCount().get()).isZero();
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
