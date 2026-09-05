package com.example.healthreport.llm.extraction;

import com.example.healthreport.infra.HealthReportAnalysisModelClient;
import com.example.healthreport.render.PageImageSequence;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import com.example.healthreport.task.DegradeAccumulator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 三次严格串行调用的编排（设计方案 §4.1）。
 *
 * <p>顺序契约：指标 → 问题 → 饮食标签；前一阶段未通过校验不发起后一阶段，
 * 任一阶段失败整任务失败、不交付部分结果。三次请求共用同一 {@code PageImageSequence}，
 * 不为任何一次重新渲染或重排。阶段间不传递任何业务结果（§4.2.1）。</p>
 */
@Slf4j
@Service
public class ExtractionOrchestrator {

    private final ExtractionRequestFactory requestFactory;
    private final HealthReportAnalysisModelClient modelClient;
    private final ExtractionSchemaValidator schemaValidator;
    private final StructuralValidator structuralValidator;
    private final IdentityGuard identityGuard;
    private final ObjectMapper objectMapper;

    public ExtractionOrchestrator(ExtractionRequestFactory requestFactory,
                                  HealthReportAnalysisModelClient modelClient,
                                  ExtractionSchemaValidator schemaValidator,
                                  StructuralValidator structuralValidator,
                                  IdentityGuard identityGuard,
                                  ObjectMapper objectMapper) {
        this.requestFactory = requestFactory;
        this.modelClient = modelClient;
        this.schemaValidator = schemaValidator;
        this.structuralValidator = structuralValidator;
        this.identityGuard = identityGuard;
        this.objectMapper = objectMapper;
    }

    /** 顺序执行三阶段并返回全部已校验结果；任意阶段失败直接抛出，不写部分结果。 */
    public ExtractionOutcome extract(PageImageSequence images, DegradeAccumulator accumulator) {
        if (images == null || accumulator == null) {
            throw new IllegalArgumentException("抽取编排参数不能为空");
        }
        long startMillis = System.currentTimeMillis();

        // 阶段一：健康指标 + 同一性临时字段。
        SchemaValidationOutcome indicatorsOutcome = callAndValidate(ExtractionCall.INDICATORS, images);
        IndicatorsResult indicatorsRaw = map(indicatorsOutcome.getValidatedNode(), IndicatorsResult.class);
        assertReportStatusOk(ExtractionCall.INDICATORS, indicatorsRaw.getReportStatus());
        IndicatorsResult indicators = structuralValidator.validateIndicators(
                indicatorsRaw, images.size(), indicatorsOutcome.getDroppedItemCount());
        recordItemDrops(accumulator, indicatorsOutcome.getDroppedItemCount()
                + indicatorCount(indicatorsRaw) - indicatorCount(indicators));
        identityGuard.check(indicators.getPatients(), images);
        // 同一性校验完成后立即剥离身份字段，不进入任何后续结构。
        indicators = indicators.withoutPatients();

        // 阶段二：健康问题。
        SchemaValidationOutcome problemsOutcome = callAndValidate(ExtractionCall.PROBLEMS, images);
        ProblemsResult problemsRaw = map(problemsOutcome.getValidatedNode(), ProblemsResult.class);
        assertReportStatusOk(ExtractionCall.PROBLEMS, problemsRaw.getReportStatus());
        ProblemsResult problems = structuralValidator.validateProblems(
                problemsRaw, images.size(), problemsOutcome.getDroppedItemCount());
        recordItemDrops(accumulator, problemsOutcome.getDroppedItemCount()
                + problemsRaw.getProblems().size() - problems.getProblems().size());

        // 阶段三：饮食建议与标签。
        SchemaValidationOutcome dietOutcome = callAndValidate(ExtractionCall.DIET_ADVICE, images);
        DietAdviceResult dietRaw = map(dietOutcome.getValidatedNode(), DietAdviceResult.class);
        assertReportStatusOk(ExtractionCall.DIET_ADVICE, dietRaw.getReportStatus());
        StructuralValidator.DietTagsValidationResult dietValidation =
                structuralValidator.validateDietTags(dietRaw, images.size(),
                        dietOutcome.getDroppedItemCount());
        if (dietValidation.isDroppedAnyTag()) {
            accumulator.recordDietTagDropped();
        }

        log.info("三阶段抽取全部完成，总页数={}，总耗时={}ms",
                images.size(), System.currentTimeMillis() - startMillis);
        return new ExtractionOutcome(indicators, problems, dietValidation.getResult());
    }

    private SchemaValidationOutcome callAndValidate(ExtractionCall call, PageImageSequence images) {
        long startMillis = System.currentTimeMillis();
        ExtractionCallInput input = requestFactory.create(call, images);
        String content = modelClient.call(input);
        SchemaValidationOutcome outcome = schemaValidator.validate(call, content);
        log.info("模型调用与 Schema 校验完成，call={}，剔除条目数={}，耗时={}ms",
                call, outcome.getDroppedItemCount(), System.currentTimeMillis() - startMillis);
        return outcome;
    }

    private <T> T map(JsonNode validatedNode, Class<T> type) {
        try {
            return objectMapper.treeToValue(validatedNode, type);
        } catch (Exception exception) {
            // Schema 已通过仍映射失败说明 DTO 与契约漂移；异常消息可能含健康数据，不记原文。
            log.error("已校验输出映射失败，type={}，异常类型={}",
                    type.getSimpleName(), exception.getClass().getName());
            throw new HealthReportException(FailCode.SERVER_ERROR, 500);
        }
    }

    /** NO_REPORT_FEATURE → NOT_HEALTH_REPORT；UNREADABLE → UNREADABLE（§6.5）。 */
    private void assertReportStatusOk(ExtractionCall call, String reportStatus) {
        if ("OK".equals(reportStatus)) {
            return;
        }
        log.info("阶段返回非 OK 状态，call={}，reportStatus={}", call, reportStatus);
        if ("NO_REPORT_FEATURE".equals(reportStatus)) {
            throw new HealthReportException(FailCode.NOT_HEALTH_REPORT, 400);
        }
        if ("UNREADABLE".equals(reportStatus)) {
            throw new HealthReportException(FailCode.UNREADABLE, 400);
        }
        throw new HealthReportException(FailCode.SERVER_ERROR, 500);
    }

    private void recordItemDrops(DegradeAccumulator accumulator, int droppedTotal) {
        if (droppedTotal > 0) {
            accumulator.recordSchemaItemDropped();
        }
    }

    private int indicatorCount(IndicatorsResult result) {
        int count = 0;
        for (IndicatorsResult.Section section : result.getSections()) {
            count += section.getIndicators().size();
        }
        return count;
    }
}
