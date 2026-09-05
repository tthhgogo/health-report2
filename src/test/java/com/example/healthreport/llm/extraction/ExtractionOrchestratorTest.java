package com.example.healthreport.llm.extraction;

import com.example.healthreport.infra.HealthReportAnalysisCallException;
import com.example.healthreport.infra.HealthReportAnalysisModelClient;
import com.example.healthreport.render.PageImageSequence;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.BusinessException;
import com.example.healthreport.llm.schema.ModelOutputSchemaRegistry;
import com.example.healthreport.task.DegradeAccumulator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R21m/R44/R45 相关：三次严格串行、前序失败即停止、身份字段用后即弃。
 * <p>除模型客户端外全部使用真实实现（生产 Schema、结构校验、同一性校验）。</p>
 */
class ExtractionOrchestratorTest {

    private static final String INDICATORS_OK = "{\"reportStatus\":\"OK\","
            + "\"patients\":[{\"page\":1,\"name\":\"张三\",\"gender\":\"男\"},"
            + "{\"page\":2,\"name\":\"张　三\",\"gender\":null}],"
            + "\"overview\":{\"totalCount\":10,\"abnormalCount\":2,\"source\":\"REPORT\"},"
            + "\"sections\":[{\"section\":\"血脂检查\",\"page\":1,\"indicators\":["
            + "{\"name\":\"甘油三酯\",\"value\":\"2.8\",\"unit\":\"mmol/L\","
            + "\"refRange\":\"0.56~1.70\",\"conclusionGenerated\":false,\"status\":\"HIGH\"}]}]}";

    private static final String PROBLEMS_OK = "{\"reportStatus\":\"OK\",\"problems\":[]}";

    private static final String DIET_ADVICE_OK = "{\"reportStatus\":\"OK\",\"recommend\":[],\"reject\":[]}";

    /** 三次调用严格按 指标 → 问题 → 饮食标签 顺序；身份字段在结果中已被剥离。 */
    @Test
    void threeCallsMustRunInOrderAndPatientsMustBeStripped() {
        RecordingClient client = new RecordingClient(INDICATORS_OK, PROBLEMS_OK, DIET_ADVICE_OK);
        DegradeAccumulator accumulator = new DegradeAccumulator();

        ExtractionOutcome outcome = orchestrator(client).extract(twoFileImages(), accumulator);

        assertThat(client.callOrder).containsExactly(ExtractionCall.INDICATORS,
                ExtractionCall.PROBLEMS, ExtractionCall.DIET_ADVICE);
        // 「张三」与「张　三」（全角空格）规范化后同一人，不误报冲突；比对完即剥离。
        assertThat(outcome.getIndicators().getPatients()).isEmpty();
        assertThat(accumulator.partial()).isFalse();
    }

    /** 阶段一失败（如 NO_REPORT_FEATURE）后，阶段二、三的调用次数必须为 0。 */
    @Test
    void firstStageFailureMustStopBeforeAnyLaterCall() {
        RecordingClient client = new RecordingClient(
                "{\"reportStatus\":\"NO_REPORT_FEATURE\",\"patients\":[],\"overview\":null,\"sections\":[]}",
                PROBLEMS_OK, DIET_ADVICE_OK);

        assertThatThrownBy(() -> orchestrator(client).extract(twoFileImages(), new DegradeAccumulator()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getFailCode())
                                .isEqualTo(FailCode.NOT_HEALTH_REPORT));
        assertThat(client.callOrder).containsExactly(ExtractionCall.INDICATORS);
    }

    /** 跨文件身份明确冲突：阶段一之后立即失败，阶段二、三不发起。 */
    @Test
    void identityConflictMustFailBeforeSecondCall() {
        String conflicting = "{\"reportStatus\":\"OK\","
                + "\"patients\":[{\"page\":1,\"name\":\"张三\",\"gender\":null},"
                + "{\"page\":2,\"name\":\"李四\",\"gender\":null}],"
                + "\"overview\":null,\"sections\":[]}";
        RecordingClient client = new RecordingClient(conflicting, PROBLEMS_OK, DIET_ADVICE_OK);

        assertThatThrownBy(() -> orchestrator(client).extract(twoFileImages(), new DegradeAccumulator()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getFailCode())
                                .isEqualTo(FailCode.IDENTITY_MISMATCH));
        assertThat(client.callOrder).containsExactly(ExtractionCall.INDICATORS);
    }

    /** 阶段二模型调用失败：阶段三调用次数为 0（零重试，fail-fast）。 */
    @Test
    void secondStageClientFailureMustStopBeforeThirdCall() {
        RecordingClient client = new RecordingClient(INDICATORS_OK, null, DIET_ADVICE_OK) {
            @Override
            public String call(ExtractionCallInput input) {
                if (input.getCall() == ExtractionCall.PROBLEMS) {
                    callOrder.add(input.getCall());
                    throw new HealthReportAnalysisCallException(FailCode.SERVER_ERROR, 0);
                }
                return super.call(input);
            }
        };

        assertThatThrownBy(() -> orchestrator(client).extract(twoFileImages(), new DegradeAccumulator()))
                .isInstanceOf(HealthReportAnalysisCallException.class);
        assertThat(client.callOrder).containsExactly(ExtractionCall.INDICATORS, ExtractionCall.PROBLEMS);
    }

    private ExtractionOrchestrator orchestrator(HealthReportAnalysisModelClient client) {
        ObjectMapper objectMapper = new ObjectMapper();
        return new ExtractionOrchestrator(
                new ExtractionRequestFactory(new ExtractionPromptProvider()),
                client,
                new ExtractionSchemaValidator(objectMapper, new ModelOutputSchemaRegistry(objectMapper)),
                new StructuralValidator(),
                new IdentityGuard(),
                objectMapper);
    }

    /** 两个文件各一页：page 1 → file 0，page 2 → file 1。 */
    private PageImageSequence twoFileImages() {
        return new PageImageSequence.Builder()
                .addPage(0, 1, new byte[]{1})
                .addPage(1, 1, new byte[]{2})
                .build();
    }

    /** 记录调用顺序并按阶段返回固定正文的假客户端。 */
    private static class RecordingClient implements HealthReportAnalysisModelClient {

        final List<ExtractionCall> callOrder = new ArrayList<ExtractionCall>();
        private final String indicatorsContent;
        private final String problemsContent;
        private final String dietTagsContent;

        RecordingClient(String indicatorsContent, String problemsContent, String dietTagsContent) {
            this.indicatorsContent = indicatorsContent;
            this.problemsContent = problemsContent;
            this.dietTagsContent = dietTagsContent;
        }

        @Override
        public String call(ExtractionCallInput input) {
            callOrder.add(input.getCall());
            switch (input.getCall()) {
                case INDICATORS:
                    return indicatorsContent;
                case PROBLEMS:
                    return problemsContent;
                case DIET_ADVICE:
                default:
                    return dietTagsContent;
            }
        }
    }
}
