package com.example.healthreport.llm.extraction;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.healthreport.constants.AllergenKey;
import com.example.healthreport.constants.DietRequirementKey;
import com.example.healthreport.constants.NutritionKey;
import com.example.healthreport.parse.ContentType;
import com.example.healthreport.parse.ParsePlan;
import com.example.healthreport.parse.ParsedFile;
import com.example.healthreport.parse.ParsedPage;
import com.example.healthreport.parse.segment.Segment;
import com.example.healthreport.parse.segment.TextSource;
import com.example.healthreport.support.SensitiveLog;
import com.example.healthreport.task.DegradeAccumulator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 抽取阶段编排：三步顺序，以及交给校验层的 segment 必须与分批用的同源。 */
class ExtractionStageServiceTest {

    @Test
    void shouldRunPlanExecuteValidateInOrder() {
        BatchPlanner planner = mock(BatchPlanner.class);
        ExtractionBatchExecutor executor = mock(ExtractionBatchExecutor.class);
        ExtractionValidationPipeline pipeline = mock(ExtractionValidationPipeline.class);
        ParsePlan parsePlan = parsePlanWithSegments();
        DegradeAccumulator accumulator = new DegradeAccumulator();
        List<ExtractionBatchPlan> batchPlanList = Collections.emptyList();
        List<ExtractionBatchResult> batchResultList = Collections.emptyList();
        ValidatedExtractionOutput expected =
                ValidatedExtractionOutputTestFactory.withUnreferencedSourceText("体检结论");
        when(planner.plan(parsePlan)).thenReturn(batchPlanList);
        when(executor.execute(batchPlanList, accumulator)).thenReturn(batchResultList);
        when(pipeline.validateAndMerge(eq(batchResultList), any(), eq(accumulator)))
                .thenReturn(expected);

        ValidatedExtractionOutput actual = new ExtractionStageService(planner, executor, pipeline)
                .extract(parsePlan, accumulator);

        assertThat(actual).isSameAs(expected);
        InOrder order = inOrder(planner, executor, pipeline);
        order.verify(planner).plan(parsePlan);
        order.verify(executor).execute(batchPlanList, accumulator);
        order.verify(pipeline).validateAndMerge(eq(batchResultList), any(), eq(accumulator));
    }

    @Test
    void segmentsGivenToValidationShouldComeFromThePlanOnly() {
        BatchPlanner planner = mock(BatchPlanner.class);
        ExtractionBatchExecutor executor = mock(ExtractionBatchExecutor.class);
        ExtractionValidationPipeline pipeline = mock(ExtractionValidationPipeline.class);
        ParsePlan parsePlan = parsePlanWithSegments();
        DegradeAccumulator accumulator = new DegradeAccumulator();
        when(planner.plan(any(ParsePlan.class)))
                .thenReturn(Collections.<ExtractionBatchPlan>emptyList());
        when(executor.execute(any(), any(DegradeAccumulator.class)))
                .thenReturn(Collections.<ExtractionBatchResult>emptyList());
        when(pipeline.validateAndMerge(any(), any(), any(DegradeAccumulator.class)))
                .thenReturn(ValidatedExtractionOutputTestFactory.withUnreferencedSourceText("体检结论"));

        new ExtractionStageService(planner, executor, pipeline).extract(parsePlan, accumulator);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Segment>> captor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(pipeline)
                .validateAndMerge(any(), captor.capture(), any(DegradeAccumulator.class));
        // 只能是计划内两页的两个块；被页数预算截断掉的块进来会让来源校验放过没发出去的引用。
        assertThat(captor.getValue()).extracting(Segment::getSegmentId)
                .containsExactly("f0-p1-s0", "f0-p2-s0");
    }

    @Test
    void sensitiveDebugShouldContainValidatedAbnormalFindingsButStayOutOfOrdinaryLogger() {
        BatchPlanner planner = mock(BatchPlanner.class);
        ExtractionBatchExecutor executor = mock(ExtractionBatchExecutor.class);
        ExtractionValidationPipeline pipeline = mock(ExtractionValidationPipeline.class);
        ParsePlan parsePlan = parsePlanWithSegments();
        DegradeAccumulator accumulator = new DegradeAccumulator();
        List<ExtractionBatchPlan> batchPlanList = Collections.emptyList();
        List<ExtractionBatchResult> batchResultList = Collections.emptyList();
        when(planner.plan(parsePlan)).thenReturn(batchPlanList);
        when(executor.execute(batchPlanList, accumulator)).thenReturn(batchResultList);
        when(pipeline.validateAndMerge(eq(batchResultList), any(), eq(accumulator)))
                .thenReturn(outputWithSensitiveFindings());

        Logger sensitiveLogger = (Logger) LoggerFactory.getLogger(
                SensitiveLog.SENSITIVE_LOGGER_NAME);
        Level previousSensitiveLevel = sensitiveLogger.getLevel();
        boolean previousAdditive = sensitiveLogger.isAdditive();
        ListAppender<ILoggingEvent> sensitiveAppender = new ListAppender<ILoggingEvent>();
        sensitiveAppender.start();
        sensitiveLogger.addAppender(sensitiveAppender);
        sensitiveLogger.setAdditive(false);
        sensitiveLogger.setLevel(Level.DEBUG);

        Logger ordinaryLogger = (Logger) LoggerFactory.getLogger(ExtractionStageService.class);
        Level previousOrdinaryLevel = ordinaryLogger.getLevel();
        ListAppender<ILoggingEvent> ordinaryAppender = new ListAppender<ILoggingEvent>();
        ordinaryAppender.start();
        ordinaryLogger.addAppender(ordinaryAppender);
        ordinaryLogger.setLevel(Level.INFO);
        try {
            new ExtractionStageService(planner, executor, pipeline)
                    .extract(parsePlan, accumulator);

            String sensitiveLog = renderedLog(sensitiveAppender);
            assertThat(sensitiveLog)
                    .contains("异常数值指标提取结果", "甘油三酯隐私标记", "2.8")
                    .contains("异常文字检查项提取结果", "结节隐私标记")
                    .contains("健康问题总检结论提取结果", "总检原文隐私标记")
                    .contains("过敏原提取结果", "虾蟹隐私标记", "弱阳性隐私标记")
                    .doesNotContain("正常指标隐私标记", "taskId");
            assertThat(renderedLog(ordinaryAppender))
                    .doesNotContain("甘油三酯隐私标记", "结节隐私标记", "虾蟹隐私标记");
        } finally {
            sensitiveLogger.detachAppender(sensitiveAppender);
            sensitiveLogger.setLevel(previousSensitiveLevel);
            sensitiveLogger.setAdditive(previousAdditive);
            sensitiveAppender.stop();
            ordinaryLogger.detachAppender(ordinaryAppender);
            ordinaryLogger.setLevel(previousOrdinaryLevel);
            ordinaryAppender.stop();
        }
    }

    private ValidatedExtractionOutput outputWithSensitiveFindings() {
        String segmentId = "f0-p1-s0";
        Map<String, Segment> segmentByIdMap = new LinkedHashMap<String, Segment>();
        segmentByIdMap.put(segmentId, new Segment(segmentId, "总检原文隐私标记",
                "总检原文隐私标记", TextSource.OCR, null));
        ValidatedExtractionOutput.Indicator abnormalIndicator =
                new ValidatedExtractionOutput.Indicator(0, 0, 0, 0, 0, 1,
                        Collections.singletonList(segmentId), "甘油三酯隐私标记", "2.8",
                        "mmol/L", "0.45-1.69", "偏高", IndicatorConclusionBasis.REPORT_TEXT, IndicatorStatus.HIGH,
                        false, true, "血脂异常隐私标记");
        ValidatedExtractionOutput.Indicator normalIndicator =
                new ValidatedExtractionOutput.Indicator(0, 0, 0, 1, 1, 1,
                        Collections.singletonList(segmentId), "正常指标隐私标记", "1.0",
                        "mmol/L", "0.45-1.69", "正常", IndicatorConclusionBasis.REPORT_TEXT, IndicatorStatus.NORMAL,
                        false, false, null);
        ValidatedExtractionOutput.TextualFinding textualFinding =
                new ValidatedExtractionOutput.TextualFinding(0, 0, 0, 2, 2, 1,
                        Collections.singletonList(segmentId), "甲状腺", "结节隐私标记",
                        IndicatorStatus.ABNORMAL, true);
        ValidatedExtractionOutput.SummaryConclusion summary =
                new ValidatedExtractionOutput.SummaryConclusion(0, 0, 0, 0, null, 3, 1,
                        Collections.singletonList(segmentId),
                        Collections.singletonList(SummaryCategory.HEALTH_PROBLEM), true);
        ValidatedExtractionOutput.Allergen allergen =
                new ValidatedExtractionOutput.Allergen(0, 0, 0, 0, 4, 1,
                        Collections.singletonList(segmentId), AllergenKey.SHRIMP_CRAB, true,
                        "虾蟹隐私标记", "弱阳性隐私标记", AllergenResultStatus.BORDERLINE);
        return new ValidatedExtractionOutput(
                Collections.<ValidatedExtractionOutput.ReportOverview>emptyList(),
                Collections.<ValidatedExtractionOutput.Section>emptyList(),
                Arrays.asList(abnormalIndicator, normalIndicator),
                Collections.singletonList(textualFinding), Collections.singletonList(summary),
                Collections.singletonList(allergen),
                Collections.<ValidatedExtractionOutput.AdviceItem<NutritionKey>>emptyList(),
                Collections.<ValidatedExtractionOutput.AdviceItem<DietRequirementKey>>emptyList(),
                Collections.<String>emptySet(), Collections.<String>emptySet(), segmentByIdMap);
    }

    private String renderedLog(ListAppender<ILoggingEvent> appender) {
        StringBuilder renderedLog = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            renderedLog.append(event.getFormattedMessage()).append('\n');
        }
        return renderedLog.toString();
    }

    private ParsePlan parsePlanWithSegments() {
        ParsedPage first = new ParsedPage(1,
                Collections.singletonList(segment("f0-p1-s0")), null, false);
        ParsedPage second = new ParsedPage(2,
                Collections.singletonList(segment("f0-p2-s0")), null, false);
        ParsedFile file = new ParsedFile(0, ContentType.PDF, 2, Arrays.asList(first, second));
        return new ParsePlan(Collections.singletonList(file), 2, 2);
    }

    private Segment segment(String segmentId) {
        return new Segment(segmentId, "血脂检查", "血脂检查", TextSource.OCR, null);
    }
}
