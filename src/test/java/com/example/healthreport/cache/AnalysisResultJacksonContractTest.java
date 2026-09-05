package com.example.healthreport.cache;

import com.example.healthreport.assemble.TestAnalysisModulesFactory;
import com.example.healthreport.task.DegradeAccumulator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 强类型四模块结构的 Jackson 往返契约：能写、能读、且无损。
 * <p>断言失败时先确认是否发生了真实的结构变更——是的话必须 bump {@code ResultSchemaVersion}；
 * 若只是 Jackson 升级改变了字段输出顺序，按新基线修断言即可，不是结构变更。</p>
 */
class AnalysisResultJacksonContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void fullyPopulatedResultShouldRoundTripLosslessly() throws Exception {
        AnalysisResult result = AnalysisResult.create(new DegradeAccumulator(), 2, 2,
                TestAnalysisModulesFactory.populated());

        String firstJson = objectMapper.writeValueAsString(result);
        AnalysisResult restored = objectMapper.readValue(firstJson, AnalysisResult.class);
        String secondJson = objectMapper.writeValueAsString(restored);

        assertThat(secondJson).isEqualTo(firstJson);
        assertThat(restored.getModules().getHealthIndicators()).isNotNull();
        assertThat(restored.getModules().getHealthProblems()).isNotNull();
        assertThat(restored.getModules().getDietAdvice()).isNotNull();
        assertThat(restored.getModules().getDishRecommendations()).isNotNull();
    }

    @Test
    void suppressedDishRecommendResultShouldRoundTripWithNullModuleFour() throws Exception {
        DegradeAccumulator accumulator = new DegradeAccumulator();
        accumulator.recordDietTagDropped();
        AnalysisResult result = AnalysisResult.create(accumulator, 2, 2,
                TestAnalysisModulesFactory.populated().withoutDishRecommend());

        String firstJson = objectMapper.writeValueAsString(result);
        AnalysisResult restored = objectMapper.readValue(firstJson, AnalysisResult.class);

        assertThat(objectMapper.writeValueAsString(restored)).isEqualTo(firstJson);
        assertThat(restored.getModules().getDishRecommendations()).isNull();
        assertThat(restored.getModules().getDietAdvice()).isNotNull();
    }

    @Test
    void emptyModulesShouldRoundTripLosslessly() throws Exception {
        AnalysisResult result = AnalysisResult.create(new DegradeAccumulator(), 0, 0,
                AnalysisModules.empty());

        String firstJson = objectMapper.writeValueAsString(result);
        AnalysisResult restored = objectMapper.readValue(firstJson, AnalysisResult.class);

        assertThat(objectMapper.writeValueAsString(restored)).isEqualTo(firstJson);
        assertThat(restored.getModules().getHealthIndicators()).isNull();
    }

}
