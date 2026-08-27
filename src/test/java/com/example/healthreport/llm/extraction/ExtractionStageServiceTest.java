package com.example.healthreport.llm.extraction;

import com.example.healthreport.parse.ContentType;
import com.example.healthreport.parse.ParsePlan;
import com.example.healthreport.parse.ParsedFile;
import com.example.healthreport.parse.ParsedPage;
import com.example.healthreport.parse.segment.Segment;
import com.example.healthreport.parse.segment.TextSource;
import com.example.healthreport.task.DegradeAccumulator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
