package com.example.healthreport.cache;

import com.example.healthreport.assemble.indicator.IndicatorAssembler;
import com.example.healthreport.assemble.problem.ProblemAssembler;
import com.example.healthreport.assemble.dietadvice.DietAdviceAssembler;
import com.example.healthreport.assemble.dietadvice.DietAdviceCounters;
import com.example.healthreport.assemble.dietadvice.DietAdviceInputFactory;
import com.example.healthreport.assemble.dishrecommend.DishRecommendAssembler;
import com.example.healthreport.assemble.dishrecommend.DishRecommendInput;
import com.example.healthreport.assemble.sort.DisplayOrder;
import com.example.healthreport.dish.TagStateResolver;
import com.example.healthreport.llm.extraction.ValidatedExtractionOutput;
import com.example.healthreport.llm.extraction.ValidatedExtractionOutputTestFactory;
import com.example.healthreport.safety.HighRiskAdviceGate;
import com.example.healthreport.task.DegradeAccumulator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** R48：Redis 结果只含公开结果字段并使用两小时 TTL。 */
class TaskResultCacheTest {

    @Test
    void serializedAssembledModulesShouldExcludeIdentityAndCompleteSourcePayloadFields()
            throws Exception {
        String taskId = "123e4567-e89b-12d3-a456-426614174000";
        List<String> prohibitedMarkerList = Arrays.asList("R48_PERSON_NAME_TOKEN",
                "R48_GENDER_TOKEN", "R48_COMPLETE_OCR_TOKEN", "R48_REPORT_SOURCE_TOKEN");
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        ObjectMapper objectMapper = new ObjectMapper();
        TaskResultCache resultCache = new TaskResultCache(redisTemplate, objectMapper);
        ValidatedExtractionOutput output = ValidatedExtractionOutputTestFactory.withUnreferencedSourceText(
                String.join("|", prohibitedMarkerList));
        DisplayOrder displayOrder = new DisplayOrder();
        IndicatorAssembler.Result moduleOne = new IndicatorAssembler(displayOrder).assemble(output, 1);
        ProblemAssembler.Result moduleTwo = new ProblemAssembler(displayOrder).assemble(output, 1);
        DietAdviceAssembler.Result moduleThree = new DietAdviceAssembler(
                new HighRiskAdviceGate(), new DietAdviceCounters()).assemble(
                new DietAdviceInputFactory(displayOrder).create(output, 1));
        DishRecommendAssembler.Result moduleFour = new DishRecommendAssembler(
                new TagStateResolver(), displayOrder).assemble(new DishRecommendInput(false, false,
                Collections.<DishRecommendInput.Candidate>emptyList()));
        AnalysisModules modules = new AnalysisModules(moduleOne, moduleTwo, moduleThree, moduleFour);
        AnalysisResult result = AnalysisResult.create(new DegradeAccumulator(), 0, 0,
                modules);

        resultCache.write(taskId, result);

        org.mockito.ArgumentCaptor<String> jsonCaptor = forClass(String.class);
        verify(valueOperations).set(org.mockito.ArgumentMatchers.eq("result:" + taskId),
                jsonCaptor.capture(), org.mockito.ArgumentMatchers.eq(2L),
                org.mockito.ArgumentMatchers.eq(TimeUnit.HOURS));
        String resultJson = jsonCaptor.getValue();
        assertThat(resultJson).contains("\"partial\"", "\"processedPages\"", "\"totalPages\"");
        assertR48SafePayload(resultJson, prohibitedMarkerList);
        JsonNode modulesNode = objectMapper.readTree(resultJson).path("modules");
        // 每个模块是一个对象，不是长度恒为 1 的数组。
        assertThat(modulesNode.path("healthIndicators").isObject()).isTrue();
        assertThat(modulesNode.path("healthIndicators").has("overview")).isTrue();
        assertThat(modulesNode.path("healthProblems").has("itemList")).isTrue();
        assertThat(modulesNode.path("dietAdvice").has("allergenSection")).isTrue();
        assertThat(modulesNode.path("dishRecommendations").has("recommendList")).isTrue();

        when(valueOperations.get("result:" + taskId)).thenReturn(resultJson);
        AnalysisResult restored = resultCache.read(taskId);
        assertThat(restored.isPartial()).isFalse();
        assertThat(restored.getModules().getHealthIndicators()).isNotNull();
    }

    @Test
    void r48AssertionShouldRejectUnsafeRedisPayload() throws Exception {
        String prohibitedMarker = "R48_UNSAFE_PAYLOAD_TOKEN";
        String unsafeJson = new ObjectMapper().writeValueAsString(new UnsafeRedisPayload(
                prohibitedMarker, prohibitedMarker, prohibitedMarker));

        assertThatThrownBy(() -> assertR48SafePayload(unsafeJson,
                Collections.singletonList(prohibitedMarker))).isInstanceOf(AssertionError.class);
    }

    private void assertR48SafePayload(String resultJson, List<String> prohibitedMarkerList) {
        assertThat(resultJson).doesNotContain("\"patient\"", "\"patientName\"",
                "\"gender\"", "\"ocrText\"", "\"segments\"");
        for (String prohibitedMarker : prohibitedMarkerList) {
            assertThat(resultJson).doesNotContain(prohibitedMarker);
        }
    }

    /** 证明 R48 断言会拒绝身份字段和完整 OCR 字段的测试载荷。 */
    @Getter
    private static final class UnsafeRedisPayload {
        private final String patientName;
        private final String gender;
        private final String ocrText;

        private UnsafeRedisPayload(String patientName, String gender, String ocrText) {
            this.patientName = patientName;
            this.gender = gender;
            this.ocrText = ocrText;
        }
    }
}
