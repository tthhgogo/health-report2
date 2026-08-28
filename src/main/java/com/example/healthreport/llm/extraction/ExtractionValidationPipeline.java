package com.example.healthreport.llm.extraction;

import com.example.healthreport.constants.AllergenKey;
import com.example.healthreport.constants.DietRequirementKey;
import com.example.healthreport.constants.NutritionKey;
import com.example.healthreport.parse.segment.Segment;
import com.example.healthreport.parse.segment.TextNormalizer;
import com.example.healthreport.safety.AllergenAdmissionFilter;
import com.example.healthreport.safety.AllergenCoverageScanner;
import com.example.healthreport.safety.AllergenSuspectScanner;
import com.example.healthreport.safety.PositiveRowCoverageScanner;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import com.example.healthreport.task.DegradeAccumulator;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * LLM-A 输出的任务级确定性校验与合并入口。
 * <p>执行顺序固定为 Schema、blockRef 展开、引用完整性、来源校验、安全扫描、准入过滤、
 * 重叠批次去重和同一性校验。任何契约失败均不触发模型重试。</p>
 */
@Service
public class ExtractionValidationPipeline {

    /** CURRENT 章节标题回切失败或 UNKNOWN 时使用的固定文案。 */
    private static final String UNKNOWN_SECTION_DISPLAY_NAME = "未标注章节";

    /** UNSECTIONED 章节标题回切失败时使用的固定文案。 */
    private static final String UNSECTIONED_DISPLAY_NAME = "未归入章节的内容";

    /** 同一性比较按方案移除姓名中的全部 Unicode 空白。 */
    private static final Pattern IDENTITY_WHITESPACE_PATTERN = Pattern.compile("\\s+");

    private final ExtractionSchemaValidator schemaValidator;
    private final SourceEvidenceValidator sourceEvidenceValidator;
    private final AllergenSuspectScanner allergenSuspectScanner;
    private final AllergenCoverageScanner allergenCoverageScanner;
    private final PositiveRowCoverageScanner positiveRowCoverageScanner;
    private final AllergenAdmissionFilter allergenAdmissionFilter;
    private final ReferenceRangeParser referenceRangeParser;
    private final TextNormalizer textNormalizer;

    public ExtractionValidationPipeline(ExtractionSchemaValidator schemaValidator,
                                  SourceEvidenceValidator sourceEvidenceValidator,
                                  AllergenSuspectScanner allergenSuspectScanner,
                                  AllergenCoverageScanner allergenCoverageScanner,
                                  PositiveRowCoverageScanner positiveRowCoverageScanner,
                                  AllergenAdmissionFilter allergenAdmissionFilter,
                                  ReferenceRangeParser referenceRangeParser,
                                  TextNormalizer textNormalizer) {
        if (schemaValidator == null || sourceEvidenceValidator == null
                || allergenSuspectScanner == null || allergenCoverageScanner == null
                || positiveRowCoverageScanner == null
                || allergenAdmissionFilter == null || referenceRangeParser == null
                || textNormalizer == null) {
            throw new IllegalArgumentException("LLM-A 校验依赖不能为空");
        }
        this.schemaValidator = schemaValidator;
        this.sourceEvidenceValidator = sourceEvidenceValidator;
        this.allergenSuspectScanner = allergenSuspectScanner;
        this.allergenCoverageScanner = allergenCoverageScanner;
        this.positiveRowCoverageScanner = positiveRowCoverageScanner;
        this.allergenAdmissionFilter = allergenAdmissionFilter;
        this.referenceRangeParser = referenceRangeParser;
        this.textNormalizer = textNormalizer;
    }

    /**
     * 校验全部 OK 批次并合并成不含 blockRef 的任务级结果。
     *
     * @throws HealthReportException Schema、引用结构、覆盖结构或人员信息冲突
     */
    public ValidatedExtractionOutput validateAndMerge(List<ExtractionBatchResult> batchResultList,
                                                List<Segment> allSegmentList,
                                                DegradeAccumulator degradeAccumulator) {
        if (batchResultList == null || batchResultList.isEmpty() || allSegmentList == null
                || degradeAccumulator == null) {
            throw new IllegalArgumentException("LLM-A 校验参数不能为空");
        }
        Map<String, Segment> segmentByIdMap = indexSegments(allSegmentList);
        List<ExtractionBatchResult> orderedResultList = new ArrayList<ExtractionBatchResult>(batchResultList);
        Collections.sort(orderedResultList, batchResultComparator());
        List<BatchData> batchDataList = new ArrayList<BatchData>(orderedResultList.size());
        for (ExtractionBatchResult batchResult : orderedResultList) {
            batchDataList.add(validateBatch(batchResult, segmentByIdMap, degradeAccumulator));
        }

        MergeData mergeData = merge(batchDataList, segmentByIdMap);
        allergenCoverageScanner.scan(mergeData.allergenSectionSegmentIdSet,
                mergeData.allergenDataSegmentIdSet, mergeData.allergenItemSegmentIdSet,
                degradeAccumulator);
        allergenSuspectScanner.scan(allSegmentList, mergeData.allergenSectionSegmentIdSet,
                mergeData.sourceAllergenList.size(), degradeAccumulator);
        positiveRowCoverageScanner.scan(allSegmentList, mergeData.allergenItemSegmentIdSet,
                degradeAccumulator);
        List<ValidatedExtractionOutput.Allergen> admittedAllergenList =
                allergenAdmissionFilter.filter(mergeData.sourceAllergenList);
        assertIdentity(mergeData.patientIdentityList);

        return new ValidatedExtractionOutput(mergeData.reportOverviewList, mergeData.sectionList,
                mergeData.indicatorList, mergeData.textualFindingList,
                mergeData.summaryConclusionList, admittedAllergenList,
                mergeData.nutritionSupplementList, mergeData.dietRequirementList,
                existingSegmentIdSet(mergeData.allergenSectionSegmentIdSet, segmentByIdMap),
                existingSegmentIdSet(mergeData.allergenDataSegmentIdSet, segmentByIdMap),
                segmentByIdMap);
    }

    private BatchData validateBatch(ExtractionBatchResult batchResult, Map<String, Segment> segmentByIdMap,
                                    DegradeAccumulator degradeAccumulator) {
        if (batchResult == null || batchResult.getBatchStatus() != BatchStatus.OK) {
            throw serverError();
        }
        JsonNode rootNode = schemaValidator.validate(batchResult.getRawContent());
        assertMetadata(rootNode, batchResult);
        BatchContext context = new BatchContext(batchResult, segmentByIdMap);

        List<ExpandedSection> expandedSectionList = expandSections(rootNode.path("sections"), context);
        assertSections(expandedSectionList);
        Set<Integer> sectionIndexSet = sectionIndexes(expandedSectionList);
        BatchData batchData = new BatchData(context.fileIndex, context.batchIndex,
                new LinkedHashSet<String>(context.inputSegmentIdSet));

        batchData.allergenSectionSegmentIdSet.addAll(expandRefArray(
                rootNode.path("allergenSectionBlockRefs"), context));
        batchData.allergenDataSegmentIdSet.addAll(expandRefArray(
                rootNode.path("allergenDataBlockRefs"), context));

        List<ExpandedItem> indicatorNodeList = expandItems(rootNode.path("indicators"), context,
                sectionIndexSet, false, degradeAccumulator);
        List<ExpandedItem> textualNodeList = expandItems(rootNode.path("textualFindings"), context,
                sectionIndexSet, false, degradeAccumulator);
        List<ExpandedItem> summaryNodeList = expandItems(rootNode.path("summaryConclusions"), context,
                sectionIndexSet, false, degradeAccumulator);
        List<ExpandedItem> allergenNodeList = expandItems(rootNode.path("allergens"), context,
                sectionIndexSet, true, degradeAccumulator);
        List<ExpandedItem> nutritionNodeList = expandItems(rootNode.path("nutritionSupplements"), context,
                sectionIndexSet, false, degradeAccumulator);
        List<ExpandedItem> dietNodeList = expandItems(rootNode.path("dietRequirements"), context,
                sectionIndexSet, false, degradeAccumulator);

        Map<Integer, List<String>> coveredSegmentIdListBySectionMap = new HashMap<Integer, List<String>>();
        collectSectionCoverage(coveredSegmentIdListBySectionMap, indicatorNodeList);
        collectSectionCoverage(coveredSegmentIdListBySectionMap, textualNodeList);
        collectSectionCoverage(coveredSegmentIdListBySectionMap, summaryNodeList);
        collectSectionCoverage(coveredSegmentIdListBySectionMap, allergenNodeList);
        collectSectionCoverage(coveredSegmentIdListBySectionMap, nutritionNodeList);
        collectSectionCoverage(coveredSegmentIdListBySectionMap, dietNodeList);

        validatePatient(rootNode.path("patient"), context, batchData);
        validateOverview(rootNode.path("reportOverview"), context, batchData);
        validateSections(expandedSectionList, coveredSegmentIdListBySectionMap, context, batchData);
        validateIndicators(indicatorNodeList, context, batchData);
        validateTextualFindings(textualNodeList, context, batchData);
        validateSummaryConclusions(summaryNodeList, context, batchData);
        validateAllergens(allergenNodeList, context, batchData, degradeAccumulator);
        validateAdvice(nutritionNodeList, NutritionKey.class, context, batchData.nutritionSupplementList);
        validateAdvice(dietNodeList, DietRequirementKey.class, context, batchData.dietRequirementList);
        return batchData;
    }

    private void assertMetadata(JsonNode rootNode, ExtractionBatchResult batchResult) {
        ExtractionBatchInput input = batchResult.getPlan().getInput();
        if (rootNode.path("fileIndex").asInt() != input.getFileIndex()
                || rootNode.path("batchIndex").asInt() != input.getBatchIndex()
                || rootNode.path("batchCount").asInt() != input.getBatchCount()
                || !rootNode.path("batchStatus").asText().equals(batchResult.getBatchStatus().name())) {
            throw serverError();
        }
    }

    private List<ExpandedSection> expandSections(JsonNode sectionArrayNode, BatchContext context) {
        List<ExpandedSection> sectionList = new ArrayList<ExpandedSection>(sectionArrayNode.size());
        for (JsonNode sectionNode : sectionArrayNode) {
            String sectionSegmentId = null;
            JsonNode blockRefNode = sectionNode.path("sectionBlockRef");
            if (!blockRefNode.isNull()) {
                sectionSegmentId = expandRef(blockRefNode.asInt(), context);
            }
            sectionList.add(new ExpandedSection(sectionNode, sectionSegmentId));
        }
        return sectionList;
    }

    private void assertSections(List<ExpandedSection> sectionList) {
        Set<Integer> sectionIndexSet = new HashSet<Integer>(sectionList.size());
        for (int index = 0; index < sectionList.size(); index++) {
            int sectionIndex = sectionList.get(index).node.path("sectionIndex").asInt();
            if (sectionIndex != index || !sectionIndexSet.add(sectionIndex)) {
                throw serverError();
            }
        }
    }

    private Set<Integer> sectionIndexes(List<ExpandedSection> sectionList) {
        Set<Integer> sectionIndexSet = new HashSet<Integer>(sectionList.size());
        for (ExpandedSection section : sectionList) {
            sectionIndexSet.add(section.node.path("sectionIndex").asInt());
        }
        return sectionIndexSet;
    }

    private List<ExpandedItem> expandItems(JsonNode arrayNode, BatchContext context,
                                           Set<Integer> sectionIndexSet, boolean allergen,
                                           DegradeAccumulator degradeAccumulator) {
        List<ExpandedItem> itemList = new ArrayList<ExpandedItem>(arrayNode.size());
        for (JsonNode itemNode : arrayNode) {
            if (!sectionIndexSet.contains(itemNode.path("sectionIndex").asInt())) {
                if (allergen) {
                    recordAllergenSuspect(degradeAccumulator);
                }
                continue;
            }
            itemList.add(new ExpandedItem(itemNode,
                    expandRefArray(itemNode.path("blockRefs"), context)));
        }
        return itemList;
    }

    private List<String> expandRefArray(JsonNode refArrayNode, BatchContext context) {
        List<String> segmentIdList = new ArrayList<String>(refArrayNode.size());
        for (JsonNode refNode : refArrayNode) {
            segmentIdList.add(expandRef(refNode.asInt(), context));
        }
        return segmentIdList;
    }

    private String expandRef(int blockRef, BatchContext context) {
        List<String> mappingList = context.batchResult.getPlan().getAddressing().getSegmentIdByBlockRef();
        if (blockRef < 0 || blockRef >= mappingList.size() || mappingList.get(blockRef) == null) {
            return "missing-f" + context.fileIndex + "-b" + context.batchIndex + "-r" + blockRef;
        }
        return mappingList.get(blockRef);
    }

    private void collectSectionCoverage(Map<Integer, List<String>> coverageMap,
                                        List<ExpandedItem> itemList) {
        for (ExpandedItem item : itemList) {
            int sectionIndex = item.node.path("sectionIndex").asInt();
            List<String> segmentIdList = coverageMap.get(sectionIndex);
            if (segmentIdList == null) {
                segmentIdList = new ArrayList<String>();
                coverageMap.put(sectionIndex, segmentIdList);
            }
            for (String segmentId : item.segmentIdList) {
                if (!segmentIdList.contains(segmentId)) {
                    segmentIdList.add(segmentId);
                }
            }
        }
    }

    private void validatePatient(JsonNode patientNode, BatchContext context, BatchData batchData) {
        String name = nullableText(patientNode.get("name"));
        if (name != null && !sourceEvidenceValidator.matches(name,
                expandRefArray(patientNode.path("nameBlockRefs"), context), context.segmentByIdMap)) {
            name = null;
        }
        String gender = nullableText(patientNode.get("gender"));
        if (gender != null && !sourceEvidenceValidator.matches(gender,
                expandRefArray(patientNode.path("genderBlockRefs"), context), context.segmentByIdMap)) {
            gender = null;
        }
        batchData.patientIdentityList.add(
                new ValidatedExtractionOutput.PatientIdentity(context.fileIndex, name, gender));
    }

    private void validateOverview(JsonNode overviewNode, BatchContext context, BatchData batchData) {
        if (overviewNode.isNull()) {
            return;
        }
        List<String> segmentIdList = expandRefArray(overviewNode.path("blockRefs"), context);
        int totalCount = overviewNode.path("totalCount").asInt();
        int abnormalCount = overviewNode.path("abnormalCount").asInt();
        if (sourceEvidenceValidator.matches(Integer.toString(totalCount), segmentIdList,
                context.segmentByIdMap)
                && sourceEvidenceValidator.matches(Integer.toString(abnormalCount), segmentIdList,
                context.segmentByIdMap)) {
            batchData.reportOverviewList.add(new ValidatedExtractionOutput.ReportOverview(
                    context.fileIndex, context.batchIndex, totalCount, abnormalCount, segmentIdList));
        }
    }

    private void validateSections(List<ExpandedSection> sectionList,
                                  Map<Integer, List<String>> coverageMap,
                                  BatchContext context, BatchData batchData) {
        for (ExpandedSection expandedSection : sectionList) {
            JsonNode node = expandedSection.node;
            int sectionIndex = node.path("sectionIndex").asInt();
            SectionRelation relation = SectionRelation.valueOf(node.path("sectionRelation").asText());
            String sectionName = node.path("sectionName").asText();
            List<String> coveredSegmentIdList = coverageMap.get(sectionIndex);
            if (coveredSegmentIdList == null) {
                coveredSegmentIdList = Collections.emptyList();
            }
            coveredSegmentIdList = existingSegmentIdList(coveredSegmentIdList,
                    context.segmentByIdMap);
            String displayName = sectionName;
            String sectionSegmentId = expandedSection.sectionSegmentId;
            if (relation == SectionRelation.CURRENT) {
                if (!sourceEvidenceValidator.matches(sectionName,
                        Collections.singletonList(expandedSection.sectionSegmentId),
                        context.segmentByIdMap)) {
                    displayName = UNKNOWN_SECTION_DISPLAY_NAME;
                    sectionSegmentId = syntheticSectionId("X-", context.fileIndex,
                            context.batchIndex, sectionIndex, coveredSegmentIdList);
                }
            } else if (relation == SectionRelation.UNSECTIONED) {
                if (!sourceEvidenceValidator.matches(sectionName, coveredSegmentIdList,
                        context.segmentByIdMap)) {
                    displayName = UNSECTIONED_DISPLAY_NAME;
                }
            } else if (relation == SectionRelation.UNKNOWN) {
                displayName = UNKNOWN_SECTION_DISPLAY_NAME;
            }
            batchData.sectionList.add(new ValidatedExtractionOutput.Section(context.fileIndex,
                    context.batchIndex, sectionIndex, relation, sectionSegmentId,
                    displayName, coveredSegmentIdList));
        }
    }

    private void validateIndicators(List<ExpandedItem> itemList, BatchContext context,
                                    BatchData batchData) {
        for (ExpandedItem item : itemList) {
            JsonNode node = item.node;
            if (!requiredIndicatorFieldsMatch(node, item.segmentIdList, context.segmentByIdMap)) {
                continue;
            }
            String problemName = nullableText(node.get("problemName"));
            if (problemName != null && !sourceEvidenceValidator.matches(problemName,
                    item.segmentIdList, context.segmentByIdMap)) {
                problemName = null;
            }
            // 原样透传给展示层，Java 不因这个标记改判状态；计数已于 2026-08-27 全部下线。
            boolean statusJudgedByModel = node.path("statusJudgedByModel").asBoolean();
            IndicatorConclusionBasis conclusionBasis =
                    IndicatorConclusionBasis.valueOf(node.path("conclusionBasis").asText());
            if (conclusionBasis == IndicatorConclusionBasis.REFERENCE_RANGE_IN_RANGE
                    && !referenceRangeAdmits(node)) {
                // 比较条件对不上原文、或结果没落在范围内：该指标不展示。
                // 【绝不改判为异常】——报告没给结论，系统就不生成一个报告没写过的结论。
                continue;
            }
            if (conclusionBasis == IndicatorConclusionBasis.REFERENCE_VALUE_MATCH
                    && !referenceValueAdmits(node)) {
                continue;
            }
            batchData.indicatorList.add(new ValidatedExtractionOutput.Indicator(context.fileIndex,
                    context.batchIndex, node.path("sectionIndex").asInt(),
                    node.path("orderInSection").asInt(), node.path("itemIndex").asInt(),
                    minimumPage(item.segmentIdList), item.segmentIdList, node.path("name").asText(),
                    node.path("value").asText(), nullableText(node.get("unit")),
                    nullableText(node.get("refRange")), nullableText(node.get("conclusionText")),
                    conclusionBasis,
                    IndicatorStatus.valueOf(node.path("status").asText()), statusJudgedByModel,
                    node.path("includeInHealthProblems").asBoolean(), problemName));
        }
    }

    private boolean requiredIndicatorFieldsMatch(JsonNode node, List<String> segmentIdList,
                                                 Map<String, Segment> segmentByIdMap) {
        if (!sourceEvidenceValidator.matches(node.path("name").asText(), segmentIdList, segmentByIdMap)
                || !sourceEvidenceValidator.matches(node.path("value").asText(), segmentIdList,
                segmentByIdMap)) {
            return false;
        }
        // conclusionText 为 null 说明报告没印结论，没有原文可回切；其余字段照常校验。
        String conclusionText = nullableText(node.get("conclusionText"));
        if (conclusionText != null && !sourceEvidenceValidator.matches(conclusionText,
                segmentIdList, segmentByIdMap)) {
            return false;
        }
        String unit = nullableText(node.get("unit"));
        String refRange = nullableText(node.get("refRange"));
        return (unit == null || sourceEvidenceValidator.matches(unit, segmentIdList, segmentByIdMap))
                && (refRange == null || sourceEvidenceValidator.matches(refRange, segmentIdList,
                segmentByIdMap));
    }

    /**
     * 参考范围准入：核验模型拆出的比较条件，并判断结果是否落在范围内。
     *
     * <p><b>回溯核验是这条路径的防伪根基。</b> 模型可以凭空报一个宽区间让任何值都「正常」，
     * 所以整组比较条件必须与 {@code refRange} 原文里写着的某一个区间<b>完全一致</b>
     * （见 {@link ReferenceRangeParser}），结果值必须与已通过回切的 {@code value} 数值相等。
     * 核验不过一律丢弃。</p>
     *
     * <p><b>只核验单个边界是不够的</b>：{@code 4.0~10.0} 只报下界、{@code 14.0~20.0} 报一个恰好是
     * 子串的 {@code 4.0}、{@code <3.0} 报成闭区间，三种都能让系统对着报告没写过的范围宣布「正常」。
     * 因此下界、上界、两侧开闭必须一起对上，缺一侧就是缺一侧。</p>
     *
     * <p>数值一律用 {@link BigDecimal#compareTo} 而不是字符串：{@code 6.2} 与 {@code 6.20}
     * 是同一个数的合法写法；而 {@code 40} 与 {@code 4.0} 不是，比对不过会被拒。</p>
     */
    private boolean referenceRangeAdmits(JsonNode node) {
        JsonNode comparisonNode = node.get("rangeComparison");
        String refRange = nullableText(node.get("refRange"));
        if (comparisonNode == null || !comparisonNode.isObject() || refRange == null) {
            return false;
        }
        BigDecimal measuredValue = decimalOrNull(comparisonNode.get("measuredValue"));
        BigDecimal lowerBound = decimalOrNull(comparisonNode.get("lowerBound"));
        BigDecimal upperBound = decimalOrNull(comparisonNode.get("upperBound"));
        if (measuredValue == null) {
            return false;
        }
        BigDecimal declaredValue = decimalOrNull(node.get("value"));
        if (declaredValue == null || measuredValue.compareTo(declaredValue) != 0) {
            return false;
        }
        boolean lowerInclusive = comparisonNode.path("lowerInclusive").asBoolean();
        boolean upperInclusive = comparisonNode.path("upperInclusive").asBoolean();
        if (!referenceRangeParser.admits(refRange, lowerBound, lowerInclusive, upperBound,
                upperInclusive)) {
            // 报上来的区间不是参考范围原文里写着的那一个：没有可核验的准入条件，不展示。
            return false;
        }
        final IndicatorRangeComparison comparison;
        try {
            comparison = new IndicatorRangeComparison(measuredValue, lowerBound,
                    lowerInclusive, upperBound, upperInclusive);
        } catch (IllegalArgumentException exception) {
            // 上下界自相矛盾或都不设限：不是可用的准入条件。
            return false;
        }
        return comparison.inRange();
    }

    /**
     * 定性项目准入：两侧归一化取值必须精确相等。
     *
     * <p>Java 在这里<b>只做集合包含</b>。参考值常常不是单值——「阴性或弱」允许阴性与弱阳性两种——
     * 所以模型把它展开成 {@code acceptableReferenceValues} 列表，Java 判断结果在不在列表里。</p>
     *
     * <p><b>刻意不做字面子串匹配。</b>「阳性」是「弱阳性」的子串，
     * 按字面包含会把阳性结果判成「符合参考值」——正是最危险的那种错法。
     * 同理「0」是「0-2/HP」的子串。展开成枚举集合之后，这类误判从根上不存在。</p>
     *
     * <p><b>与数值路径的信任边界不同，这里必须说清楚</b>：数值路径的上下界能回到
     * {@code refRange} 原文逐字核验，而归一化枚举<b>无法回溯原文</b>——
     * 「阴性」映射成哪个枚举，Java 无从验证。能兜住的只有：
     * {@code value} 与 {@code refRange} 本身都已通过来源回切，确实是报告上的原话。
     * 因此这条路径对模型归一化的正确性是<b>信任</b>关系，不是校验关系。</p>
     */
    private boolean referenceValueAdmits(JsonNode node) {
        JsonNode matchNode = node.get("valueMatch");
        if (matchNode == null || !matchNode.isObject() || nullableText(node.get("refRange")) == null) {
            return false;
        }
        ComparableQualitativeValue resultValue =
                qualitativeOrNull(matchNode.get("resultComparableValue"));
        JsonNode acceptableNode = matchNode.get("acceptableReferenceValues");
        if (resultValue == null || acceptableNode == null || !acceptableNode.isArray()
                || acceptableNode.size() == 0) {
            return false;
        }
        for (JsonNode acceptableValueNode : acceptableNode) {
            if (resultValue == qualitativeOrNull(acceptableValueNode)) {
                return true;
            }
        }
        return false;
    }

    /** 解析归一化取值；枚举外的写法一律当作缺失，不做近似匹配。 */
    private ComparableQualitativeValue qualitativeOrNull(JsonNode valueNode) {
        String text = nullableText(valueNode);
        if (text == null) {
            return null;
        }
        try {
            return ComparableQualitativeValue.valueOf(text);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** 解析十进制字符串；非十进制一律当作缺失，绝不做近似。 */
    private BigDecimal decimalOrNull(JsonNode valueNode) {
        String text = nullableText(valueNode);
        if (text == null) {
            return null;
        }
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private void validateTextualFindings(List<ExpandedItem> itemList, BatchContext context,
                                         BatchData batchData) {
        for (ExpandedItem item : itemList) {
            JsonNode node = item.node;
            if (!sourceEvidenceValidator.matches(node.path("title").asText(), item.segmentIdList,
                    context.segmentByIdMap)
                    || !sourceEvidenceValidator.matches(node.path("conclusionText").asText(),
                    item.segmentIdList, context.segmentByIdMap)) {
                continue;
            }
            batchData.textualFindingList.add(new ValidatedExtractionOutput.TextualFinding(
                    context.fileIndex, context.batchIndex, node.path("sectionIndex").asInt(),
                    node.path("orderInSection").asInt(), node.path("itemIndex").asInt(),
                    minimumPage(item.segmentIdList), item.segmentIdList, node.path("title").asText(),
                    node.path("conclusionText").asText(),
                    IndicatorStatus.valueOf(node.path("status").asText()),
                    node.path("includeInHealthProblems").asBoolean()));
        }
    }

    private void validateSummaryConclusions(List<ExpandedItem> itemList, BatchContext context,
                                            BatchData batchData) {
        for (ExpandedItem item : itemList) {
            if (!allSegmentsExist(item.segmentIdList, context.segmentByIdMap)) {
                continue;
            }
            JsonNode node = item.node;
            List<SummaryCategory> categoryList = new ArrayList<SummaryCategory>(
                    node.path("categories").size());
            for (JsonNode categoryNode : node.path("categories")) {
                categoryList.add(SummaryCategory.valueOf(categoryNode.asText()));
            }
            batchData.summaryConclusionList.add(new ValidatedExtractionOutput.SummaryConclusion(
                    context.fileIndex, context.batchIndex, node.path("sectionIndex").asInt(),
                    node.path("sourceOrder").asInt(), nullableInteger(node.get("itemNo")),
                    node.path("itemIndex").asInt(), minimumPage(item.segmentIdList),
                    item.segmentIdList, categoryList,
                    node.path("includeInHealthProblems").asBoolean()));
        }
    }

    private void validateAllergens(List<ExpandedItem> itemList, BatchContext context,
                                   BatchData batchData, DegradeAccumulator degradeAccumulator) {
        for (ExpandedItem item : itemList) {
            JsonNode node = item.node;
            if (!sourceEvidenceValidator.matches(node.path("rawName").asText(), item.segmentIdList,
                    context.segmentByIdMap)
                    || !sourceEvidenceValidator.matches(node.path("rawResult").asText(),
                    item.segmentIdList, context.segmentByIdMap)) {
                recordAllergenSuspect(degradeAccumulator);
                continue;
            }
            AllergenKey enumKey = AllergenKey.valueOf(node.path("enumKey").asText());
            boolean foodBorne = allergenAdmissionFilter.resolveFoodBorne(enumKey,
                    node.path("isFoodBorne").asBoolean());
            batchData.sourceAllergenList.add(new ValidatedExtractionOutput.Allergen(context.fileIndex,
                    context.batchIndex, node.path("sectionIndex").asInt(),
                    node.path("sourceOrder").asInt(), node.path("itemIndex").asInt(),
                    minimumPage(item.segmentIdList), item.segmentIdList, enumKey, foodBorne,
                    node.path("rawName").asText(), node.path("rawResult").asText(),
                    AllergenResultStatus.valueOf(node.path("resultStatus").asText())));
        }
    }

    /** 解析适用范围；缺失或非法一律按 UNCERTAIN，由准入政策保守处理。 */
    private AdviceApplicability adviceApplicability(JsonNode node) {
        String text = nullableText(node.get("applicability"));
        if (text == null) {
            return AdviceApplicability.UNCERTAIN;
        }
        try {
            return AdviceApplicability.valueOf(text);
        } catch (IllegalArgumentException exception) {
            return AdviceApplicability.UNCERTAIN;
        }
    }

    /** 解析建议性质；缺失或非法一律按 UNCERTAIN。 */
    private AdviceStructuredSafety adviceStructuredSafety(JsonNode node) {
        String text = nullableText(node.get("structuredSafety"));
        if (text == null) {
            return AdviceStructuredSafety.UNCERTAIN;
        }
        try {
            return AdviceStructuredSafety.valueOf(text);
        } catch (IllegalArgumentException exception) {
            return AdviceStructuredSafety.UNCERTAIN;
        }
    }

    private <T extends Enum<T>> void validateAdvice(List<ExpandedItem> itemList, Class<T> enumType,
                                                    BatchContext context,
                                                    List<ValidatedExtractionOutput.AdviceItem<T>> targetList) {
        for (ExpandedItem item : itemList) {
            if (!allSegmentsExist(item.segmentIdList, context.segmentByIdMap)) {
                continue;
            }
            JsonNode node = item.node;
            String adviceQuote = nullableText(node.get("adviceQuote"));
            if (adviceQuote == null || !sourceEvidenceValidator.matches(adviceQuote,
                    item.segmentIdList, context.segmentByIdMap)) {
                // 建议原文回切不过就整条丢弃：它是安全检查的唯一输入，
                // 让一句无法核验的话去决定要不要抑制，等于没有检查。
                continue;
            }
            targetList.add(new ValidatedExtractionOutput.AdviceItem<T>(context.fileIndex,
                    context.batchIndex, node.path("sectionIndex").asInt(),
                    node.path("sourceOrder").asInt(), nullableInteger(node.get("itemNo")),
                    node.path("itemIndex").asInt(), minimumPage(item.segmentIdList),
                    item.segmentIdList, Enum.valueOf(enumType, node.path("enumKey").asText()),
                    adviceQuote, adviceApplicability(node), adviceStructuredSafety(node)));
        }
    }

    private MergeData merge(List<BatchData> batchDataList, Map<String, Segment> segmentByIdMap) {
        MergeData result = new MergeData();
        Map<String, Set<String>> inputSegmentIdSetByBatchKeyMap = new HashMap<String, Set<String>>();
        for (BatchData batchData : batchDataList) {
            inputSegmentIdSetByBatchKeyMap.put(batchKey(batchData.fileIndex, batchData.batchIndex),
                    batchData.inputSegmentIdSet);
            result.patientIdentityList.addAll(batchData.patientIdentityList);
            result.reportOverviewList.addAll(batchData.reportOverviewList);
            result.sectionList.addAll(batchData.sectionList);
            result.allergenSectionSegmentIdSet.addAll(batchData.allergenSectionSegmentIdSet);
            result.allergenDataSegmentIdSet.addAll(batchData.allergenDataSegmentIdSet);
        }
        for (BatchData batchData : batchDataList) {
            mergeEvidenceItems(result.indicatorList, batchData.indicatorList,
                    inputSegmentIdSetByBatchKeyMap);
            mergeEvidenceItems(result.textualFindingList, batchData.textualFindingList,
                    inputSegmentIdSetByBatchKeyMap);
            mergeEvidenceItems(result.summaryConclusionList, batchData.summaryConclusionList,
                    inputSegmentIdSetByBatchKeyMap);
            mergeEvidenceItems(result.sourceAllergenList, batchData.sourceAllergenList,
                    inputSegmentIdSetByBatchKeyMap);
            mergeEvidenceItems(result.nutritionSupplementList, batchData.nutritionSupplementList,
                    inputSegmentIdSetByBatchKeyMap);
            mergeEvidenceItems(result.dietRequirementList, batchData.dietRequirementList,
                    inputSegmentIdSetByBatchKeyMap);
        }
        result.sectionList = resolveSectionContinuations(result.sectionList);
        for (ValidatedExtractionOutput.Allergen allergen : result.sourceAllergenList) {
            result.allergenItemSegmentIdSet.addAll(allergen.getSegmentIdList());
        }
        return result;
    }

    private <T extends ValidatedExtractionOutput.EvidenceItem> void mergeEvidenceItems(
            List<T> targetList, List<T> sourceList,
            Map<String, Set<String>> inputSegmentIdSetByBatchKeyMap) {
        for (T candidate : sourceList) {
            int duplicateIndex = duplicateIndex(targetList, candidate,
                    inputSegmentIdSetByBatchKeyMap);
            if (duplicateIndex < 0) {
                targetList.add(candidate);
            } else if (candidate.getPage() < targetList.get(duplicateIndex).getPage()) {
                targetList.set(duplicateIndex, candidate);
            }
        }
    }

    private <T extends ValidatedExtractionOutput.EvidenceItem> int duplicateIndex(
            List<T> targetList, T candidate,
            Map<String, Set<String>> inputSegmentIdSetByBatchKeyMap) {
        for (int index = 0; index < targetList.size(); index++) {
            T existing = targetList.get(index);
            if (existing.getFileIndex() == candidate.getFileIndex()
                    && existing.getBatchIndex() != candidate.getBatchIndex()
                    && existing.getItemIndex() == candidate.getItemIndex()
                    && batchesOverlap(existing, candidate, inputSegmentIdSetByBatchKeyMap)
                    && intersects(existing.getSegmentIdList(), candidate.getSegmentIdList())) {
                return index;
            }
        }
        return -1;
    }

    private boolean batchesOverlap(ValidatedExtractionOutput.EvidenceItem left,
                                   ValidatedExtractionOutput.EvidenceItem right,
                                   Map<String, Set<String>> inputSegmentIdSetByBatchKeyMap) {
        Set<String> leftSet = inputSegmentIdSetByBatchKeyMap.get(
                batchKey(left.getFileIndex(), left.getBatchIndex()));
        Set<String> rightSet = inputSegmentIdSetByBatchKeyMap.get(
                batchKey(right.getFileIndex(), right.getBatchIndex()));
        return leftSet != null && rightSet != null && intersects(leftSet, rightSet);
    }

    private List<ValidatedExtractionOutput.Section> resolveSectionContinuations(
            List<ValidatedExtractionOutput.Section> sourceSectionList) {
        List<ValidatedExtractionOutput.Section> resolvedSectionList =
                new ArrayList<ValidatedExtractionOutput.Section>(sourceSectionList.size());
        Map<Integer, ValidatedExtractionOutput.Section> previousSectionByFileMap =
                new HashMap<Integer, ValidatedExtractionOutput.Section>();
        for (ValidatedExtractionOutput.Section section : sourceSectionList) {
            ValidatedExtractionOutput.Section resolved = section;
            if (section.getRelation() == SectionRelation.CONTINUATION) {
                ValidatedExtractionOutput.Section previous = previousSectionByFileMap.get(section.getFileIndex());
                if (previous == null) {
                    resolved = new ValidatedExtractionOutput.Section(section.getFileIndex(),
                            section.getBatchIndex(), section.getSectionIndex(), SectionRelation.UNKNOWN,
                            syntheticSectionId("X-", section), UNKNOWN_SECTION_DISPLAY_NAME,
                            section.getCoveredSegmentIdList());
                } else {
                    resolved = new ValidatedExtractionOutput.Section(section.getFileIndex(),
                            section.getBatchIndex(), section.getSectionIndex(), SectionRelation.CONTINUATION,
                            previous.getSectionSegmentId(), previous.getDisplayName(),
                            section.getCoveredSegmentIdList());
                }
            } else if (section.getRelation() == SectionRelation.UNSECTIONED) {
                resolved = new ValidatedExtractionOutput.Section(section.getFileIndex(),
                        section.getBatchIndex(), section.getSectionIndex(), section.getRelation(),
                        syntheticSectionId("U-", section), section.getDisplayName(),
                        section.getCoveredSegmentIdList());
            } else if (section.getRelation() == SectionRelation.UNKNOWN) {
                resolved = new ValidatedExtractionOutput.Section(section.getFileIndex(),
                        section.getBatchIndex(), section.getSectionIndex(), section.getRelation(),
                        syntheticSectionId("X-", section), section.getDisplayName(),
                        section.getCoveredSegmentIdList());
            }
            resolvedSectionList.add(resolved);
            if (resolved.getRelation() == SectionRelation.CURRENT
                    || resolved.getRelation() == SectionRelation.CONTINUATION) {
                previousSectionByFileMap.put(resolved.getFileIndex(), resolved);
            }
        }
        return resolvedSectionList;
    }

    private String syntheticSectionId(String prefix, ValidatedExtractionOutput.Section section) {
        return syntheticSectionId(prefix, section.getFileIndex(), section.getBatchIndex(),
                section.getSectionIndex(), section.getCoveredSegmentIdList());
    }

    private String syntheticSectionId(String prefix, int fileIndex, int batchIndex,
                                      int sectionIndex, List<String> coveredSegmentIdList) {
        if (!coveredSegmentIdList.isEmpty()) {
            return prefix + Collections.min(coveredSegmentIdList);
        }
        return prefix + "f" + fileIndex + "-b" + batchIndex + "-i" + sectionIndex;
    }

    /**
     * 多文件同一性校验。
     * <p>【已知限制：不做繁简转换】（2026-08-26 产品确认）只做 NFKC + 去空白，
     * 因此「张伟」与「張偉」会被判成不同人，整任务落 IDENTITY_MISMATCH。
     * 方向是 fail-safe 的——只会误拒，不会把两个人的报告合并——故接受。
     * <b>不得用拼音或不完整映射表顶替</b>：那会制造误合并，方向就反了。</p>
     */
    private void assertIdentity(List<ValidatedExtractionOutput.PatientIdentity> identityList) {
        String expectedName = null;
        String expectedGender = null;
        for (ValidatedExtractionOutput.PatientIdentity identity : identityList) {
            if (identity.getName() != null) {
                String normalizedName = normalizeIdentityName(identity.getName());
                if (expectedName == null) {
                    expectedName = normalizedName;
                } else if (!expectedName.equals(normalizedName)) {
                    throw new HealthReportException(FailCode.IDENTITY_MISMATCH, 400);
                }
            }
            if (identity.getGender() != null) {
                if (expectedGender == null) {
                    expectedGender = identity.getGender();
                } else if (!expectedGender.equals(identity.getGender())) {
                    throw new HealthReportException(FailCode.IDENTITY_MISMATCH, 400);
                }
            }
        }
    }

    private String normalizeIdentityName(String name) {
        String normalized = textNormalizer.normalize(name).getNormalizedText();
        return IDENTITY_WHITESPACE_PATTERN.matcher(normalized).replaceAll("");
    }

    private Map<String, Segment> indexSegments(List<Segment> allSegmentList) {
        Map<String, Segment> segmentByIdMap = new LinkedHashMap<String, Segment>(allSegmentList.size());
        for (Segment segment : allSegmentList) {
            if (segment == null || segmentByIdMap.put(segment.getSegmentId(), segment) != null) {
                throw new IllegalArgumentException("segment 列表包含空值或重复 ID");
            }
        }
        return segmentByIdMap;
    }

    private boolean allSegmentsExist(List<String> segmentIdList,
                                     Map<String, Segment> segmentByIdMap) {
        if (segmentIdList.isEmpty()) {
            return false;
        }
        for (String segmentId : segmentIdList) {
            if (!segmentByIdMap.containsKey(segmentId)) {
                return false;
            }
        }
        return true;
    }

    private List<String> existingSegmentIdList(List<String> sourceSegmentIdList,
                                               Map<String, Segment> segmentByIdMap) {
        List<String> existingSegmentIdList = new ArrayList<String>(sourceSegmentIdList.size());
        for (String segmentId : sourceSegmentIdList) {
            if (segmentByIdMap.containsKey(segmentId)) {
                existingSegmentIdList.add(segmentId);
            }
        }
        return existingSegmentIdList;
    }

    private Set<String> existingSegmentIdSet(Set<String> sourceSegmentIdSet,
                                             Map<String, Segment> segmentByIdMap) {
        Set<String> existingSegmentIdSet = new LinkedHashSet<String>();
        for (String segmentId : sourceSegmentIdSet) {
            if (segmentByIdMap.containsKey(segmentId)) {
                existingSegmentIdSet.add(segmentId);
            }
        }
        return existingSegmentIdSet;
    }

    private int minimumPage(List<String> segmentIdList) {
        int minimumPage = Integer.MAX_VALUE;
        for (String segmentId : segmentIdList) {
            int pageMarker = segmentId.indexOf("-p");
            int sequenceMarker = segmentId.indexOf("-s", pageMarker + 2);
            if (pageMarker < 0 || sequenceMarker < 0) {
                return Integer.MAX_VALUE;
            }
            minimumPage = Math.min(minimumPage,
                    Integer.parseInt(segmentId.substring(pageMarker + 2, sequenceMarker)));
        }
        return minimumPage;
    }

    private void recordAllergenSuspect(DegradeAccumulator degradeAccumulator) {
        degradeAccumulator.recordAllergenSuspectMiss();
    }

    private boolean intersects(Iterable<String> left, Iterable<String> right) {
        Set<String> leftSet = new HashSet<String>();
        for (String value : left) {
            leftSet.add(value);
        }
        for (String value : right) {
            if (leftSet.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private String batchKey(int fileIndex, int batchIndex) {
        return fileIndex + ":" + batchIndex;
    }

    /**
     * 取可空文本；<b>空串与纯空白一律按缺失处理</b>。
     *
     * <p>「字段在、但没有内容」不是内容：空串能通过所有子串式校验，
     * 等于让该字段绕过来源回切与各条准入判断。</p>
     */
    private String nullableText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return text.trim().isEmpty() ? null : text;
    }

    private Integer nullableInteger(JsonNode node) {
        return node == null || node.isNull() ? null : Integer.valueOf(node.asInt());
    }

    private HealthReportException serverError() {
        return new HealthReportException(FailCode.SERVER_ERROR, 500);
    }

    private Comparator<ExtractionBatchResult> batchResultComparator() {
        return new Comparator<ExtractionBatchResult>() {
            @Override
            public int compare(ExtractionBatchResult left, ExtractionBatchResult right) {
                int fileComparison = Integer.compare(left.getPlan().getInput().getFileIndex(),
                        right.getPlan().getInput().getFileIndex());
                if (fileComparison != 0) {
                    return fileComparison;
                }
                return Integer.compare(left.getPlan().getInput().getBatchIndex(),
                        right.getPlan().getInput().getBatchIndex());
            }
        };
    }

    /** 已展开但尚未做来源校验的章节。 */
    private static final class ExpandedSection {

        private final JsonNode node;
        private final String sectionSegmentId;

        private ExpandedSection(JsonNode node, String sectionSegmentId) {
            this.node = node;
            this.sectionSegmentId = sectionSegmentId;
        }
    }

    /** 已展开且章节引用有效的普通条目。 */
    private static final class ExpandedItem {

        private final JsonNode node;
        private final List<String> segmentIdList;

        private ExpandedItem(JsonNode node, List<String> segmentIdList) {
            this.node = node;
            this.segmentIdList = segmentIdList;
        }
    }

    /** 单批处理需要的不可持久化上下文。 */
    private static final class BatchContext {

        private final ExtractionBatchResult batchResult;
        private final int fileIndex;
        private final int batchIndex;
        private final Map<String, Segment> segmentByIdMap;
        private final Set<String> inputSegmentIdSet;

        private BatchContext(ExtractionBatchResult batchResult, Map<String, Segment> segmentByIdMap) {
            this.batchResult = batchResult;
            this.fileIndex = batchResult.getPlan().getInput().getFileIndex();
            this.batchIndex = batchResult.getPlan().getInput().getBatchIndex();
            this.segmentByIdMap = segmentByIdMap;
            this.inputSegmentIdSet = new LinkedHashSet<String>(
                    batchResult.getPlan().getAddressing().getSegmentIdByBlockRef());
        }
    }

    /** 单批通过来源校验后的中间结果。 */
    private static final class BatchData {

        private final int fileIndex;
        private final int batchIndex;
        private final Set<String> inputSegmentIdSet;
        private final List<ValidatedExtractionOutput.PatientIdentity> patientIdentityList =
                new ArrayList<ValidatedExtractionOutput.PatientIdentity>();
        private final List<ValidatedExtractionOutput.ReportOverview> reportOverviewList =
                new ArrayList<ValidatedExtractionOutput.ReportOverview>();
        private final List<ValidatedExtractionOutput.Section> sectionList =
                new ArrayList<ValidatedExtractionOutput.Section>();
        private final List<ValidatedExtractionOutput.Indicator> indicatorList =
                new ArrayList<ValidatedExtractionOutput.Indicator>();
        private final List<ValidatedExtractionOutput.TextualFinding> textualFindingList =
                new ArrayList<ValidatedExtractionOutput.TextualFinding>();
        private final List<ValidatedExtractionOutput.SummaryConclusion> summaryConclusionList =
                new ArrayList<ValidatedExtractionOutput.SummaryConclusion>();
        private final List<ValidatedExtractionOutput.Allergen> sourceAllergenList =
                new ArrayList<ValidatedExtractionOutput.Allergen>();
        private final List<ValidatedExtractionOutput.AdviceItem<NutritionKey>> nutritionSupplementList =
                new ArrayList<ValidatedExtractionOutput.AdviceItem<NutritionKey>>();
        private final List<ValidatedExtractionOutput.AdviceItem<DietRequirementKey>> dietRequirementList =
                new ArrayList<ValidatedExtractionOutput.AdviceItem<DietRequirementKey>>();
        private final Set<String> allergenSectionSegmentIdSet = new LinkedHashSet<String>();
        private final Set<String> allergenDataSegmentIdSet = new LinkedHashSet<String>();

        private BatchData(int fileIndex, int batchIndex, Set<String> inputSegmentIdSet) {
            this.fileIndex = fileIndex;
            this.batchIndex = batchIndex;
            this.inputSegmentIdSet = inputSegmentIdSet;
        }
    }

    /** 跨批去重完成、尚未执行过敏准入的任务级中间结果。 */
    private static final class MergeData {

        private final List<ValidatedExtractionOutput.PatientIdentity> patientIdentityList =
                new ArrayList<ValidatedExtractionOutput.PatientIdentity>();
        private final List<ValidatedExtractionOutput.ReportOverview> reportOverviewList =
                new ArrayList<ValidatedExtractionOutput.ReportOverview>();
        private List<ValidatedExtractionOutput.Section> sectionList =
                new ArrayList<ValidatedExtractionOutput.Section>();
        private final List<ValidatedExtractionOutput.Indicator> indicatorList =
                new ArrayList<ValidatedExtractionOutput.Indicator>();
        private final List<ValidatedExtractionOutput.TextualFinding> textualFindingList =
                new ArrayList<ValidatedExtractionOutput.TextualFinding>();
        private final List<ValidatedExtractionOutput.SummaryConclusion> summaryConclusionList =
                new ArrayList<ValidatedExtractionOutput.SummaryConclusion>();
        private final List<ValidatedExtractionOutput.Allergen> sourceAllergenList =
                new ArrayList<ValidatedExtractionOutput.Allergen>();
        private final List<ValidatedExtractionOutput.AdviceItem<NutritionKey>> nutritionSupplementList =
                new ArrayList<ValidatedExtractionOutput.AdviceItem<NutritionKey>>();
        private final List<ValidatedExtractionOutput.AdviceItem<DietRequirementKey>> dietRequirementList =
                new ArrayList<ValidatedExtractionOutput.AdviceItem<DietRequirementKey>>();
        private final Set<String> allergenSectionSegmentIdSet = new LinkedHashSet<String>();
        private final Set<String> allergenDataSegmentIdSet = new LinkedHashSet<String>();
        private final Set<String> allergenItemSegmentIdSet = new LinkedHashSet<String>();
    }
}
