package com.example.healthreport.llm.dishtag;

import com.example.healthreport.dish.Dish;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** LLM-B Schema、批次覆盖和互斥的唯一校验入口。 */
@Slf4j
@Component
public class DishTagContractValidator {

    /** 随应用打包的 LLM-B 输出 Schema。 */
    private static final String SCHEMA_PATH = "schema/dish_tag_output.schema.json";

    private final ObjectMapper objectMapper;
    private final JsonSchema jsonSchema;

    public DishTagContractValidator(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("JSON处理器不能为空");
        }
        this.objectMapper = objectMapper;
        this.jsonSchema = loadSchema(objectMapper);
    }

    /**
     * 校验并反序列化模型响应；少、多、重复或跨列表相交均整批拒绝。
     */
    public DishTagOutput validate(String responseJson, String expectedEnumKey,
                                  List<Dish> inputDishList) {
        // 【输入侧检查必须在 try 之外】它查的是调用方传进来的批次，不是模型返回值。
        // 放在 try 里会被下面的 RuntimeException 兜底转成「模型批次被拒」，
        // 结果一样但把编排层的 bug 伪装成模型问题，排障时找错方向。
        Set<Long> expectedDishIdSet = collectInputDishIds(inputDishList);
        ValidationStage validationStage = ValidationStage.JSON_PARSE;
        Set<ValidationMessage> schemaViolationSet = Collections.emptySet();
        try {
            String normalizedResponseJson = unwrapMarkdownJsonFence(responseJson);
            JsonNode rootNode = objectMapper.readTree(normalizedResponseJson);
            if (rootNode == null) {
                throw rejected();
            }
            validationStage = ValidationStage.SCHEMA;
            schemaViolationSet = jsonSchema.validate(rootNode);
            if (!schemaViolationSet.isEmpty()) {
                throw rejected();
            }
            validationStage = ValidationStage.DESERIALIZATION;
            DishTagOutput output = objectMapper.treeToValue(rootNode, DishTagOutput.class);
            validationStage = ValidationStage.COVERAGE;
            validateCoverage(output, expectedEnumKey, expectedDishIdSet);
            return output;
        } catch (DishTagBatchRejectedException exception) {
            logRejected(validationStage, expectedEnumKey, expectedDishIdSet.size(), schemaViolationSet,
                    responseJson, exception);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            log.error("LLM-B批次契约处理异常，阶段={}，enumKey={}，批次菜品数={}，异常类型={}",
                    validationStage.getDescription(), expectedEnumKey, expectedDishIdSet.size(),
                    exception.getClass().getName(), exception);
            logResponseDebug(validationStage, schemaViolationSet, responseJson);
            throw rejected();
        }
    }

    /**
     * 兼容模型把完整 JSON 包在 Markdown 代码围栏中的常见输出。
     * 只接受整个响应由 {@code ```json}（或无语言标签的 {@code ```}）完整包裹，
     * 不从解释性文本中搜索 JSON，避免把混合输出静默纠正为合法响应。
     */
    private String unwrapMarkdownJsonFence(String responseJson) {
        if (responseJson == null) {
            return null;
        }
        String trimmedResponse = responseJson.trim();
        if (!trimmedResponse.startsWith("```")) {
            return trimmedResponse;
        }
        int openingLineEnd = trimmedResponse.indexOf('\n');
        if (openingLineEnd < 0) {
            return trimmedResponse;
        }
        String openingFence = trimmedResponse.substring(0, openingLineEnd).trim();
        if (!("```json".equalsIgnoreCase(openingFence) || "```".equals(openingFence))) {
            return trimmedResponse;
        }
        int closingLineStart = trimmedResponse.lastIndexOf('\n');
        if (closingLineStart <= openingLineEnd
                || !"```".equals(trimmedResponse.substring(closingLineStart + 1).trim())) {
            return trimmedResponse;
        }
        return trimmedResponse.substring(openingLineEnd + 1, closingLineStart).trim();
    }

    /** 记录模型契约拒绝；普通日志只写安全坐标，完整违规详情与响应仅进 DEBUG。 */
    private void logRejected(ValidationStage validationStage, String expectedEnumKey, int dishCount,
                             Set<ValidationMessage> schemaViolationSet, String responseJson,
                             DishTagBatchRejectedException exception) {
        log.warn("LLM-B批次契约校验拒绝，阶段={}，enumKey={}，批次菜品数={}，Schema违规数={}",
                validationStage.getDescription(), expectedEnumKey, dishCount, schemaViolationSet.size(), exception);
        logResponseDebug(validationStage, schemaViolationSet, responseJson);
    }

    /** LLM-B 只处理公开菜品数据，允许在本类 DEBUG 日志中记录完整校验详情与响应。 */
    private void logResponseDebug(ValidationStage validationStage,
                                  Set<ValidationMessage> schemaViolationSet, String responseJson) {
        if (!log.isDebugEnabled()) {
            return;
        }
        if (!schemaViolationSet.isEmpty()) {
            log.debug("LLM-B Schema违规详情：{}", schemaViolationSet);
        }
        log.debug("LLM-B契约校验失败响应正文，阶段={}：{}", validationStage.getDescription(), responseJson);
    }

    /**
     * 汇总本批输入的菜品 ID，重复即编排层 bug。
     * 这里抛 IllegalArgumentException 而不是「批次被拒」——两者结果都是整批作废，
     * 但前者指向调用方，后者指向模型，混同会掩盖真正的缺陷。
     */
    private Set<Long> collectInputDishIds(List<Dish> inputDishList) {
        Set<Long> expectedDishIdSet = new HashSet<Long>(inputDishList.size());
        for (Dish dish : inputDishList) {
            if (!expectedDishIdSet.add(dish.getDishId())) {
                throw new IllegalArgumentException("批次输入菜品ID重复");
            }
        }
        return expectedDishIdSet;
    }

    private void validateCoverage(DishTagOutput output, String expectedEnumKey,
                                  Set<Long> expectedDishIdSet) {
        if (output == null || !expectedEnumKey.equals(output.getEnumKey())) {
            throw rejected();
        }
        Set<Long> actualDishIdSet = new HashSet<Long>(expectedDishIdSet.size());
        addUnique(actualDishIdSet, output.getNeutralDishIds());
        addUnique(actualDishIdSet, output.getUnknownDishIds());
        for (DishTagOutput.Hit hit : output.getHitList()) {
            if (hit == null || hit.getDishId() == null || !actualDishIdSet.add(hit.getDishId())) {
                throw rejected();
            }
        }
        if (!actualDishIdSet.equals(expectedDishIdSet)) {
            throw rejected();
        }
    }

    private void addUnique(Set<Long> targetDishIdSet, List<Long> sourceDishIdList) {
        for (Long dishId : sourceDishIdList) {
            if (dishId == null || !targetDishIdSet.add(dishId)) {
                throw rejected();
            }
        }
    }

    private DishTagBatchRejectedException rejected() {
        // 不把 Schema 消息或模型正文放进异常，避免被上层日志意外输出。
        return new DishTagBatchRejectedException("LLM-B批次契约校验失败");
    }

    private JsonSchema loadSchema(ObjectMapper mapper) {
        ClassPathResource resource = new ClassPathResource(SCHEMA_PATH);
        try (InputStream inputStream = resource.getInputStream()) {
            JsonNode schemaNode = mapper.readTree(inputStream);
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(schemaNode);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("LLM-B Schema加载失败", exception);
        }
    }

    /** LLM-B 响应在契约处理链路中的失败阶段。 */
    private enum ValidationStage {

        /** Jackson 尚未把响应正文解析为 JSON 树。 */
        JSON_PARSE("JSON解析"),

        /** JSON 树未通过正式输出 Schema。 */
        SCHEMA("Schema校验"),

        /** 合法 JSON 树无法转换为紧凑响应 DTO。 */
        DESERIALIZATION("DTO反序列化"),

        /** DTO 未通过批次覆盖、互斥或枚举一致性校验。 */
        COVERAGE("覆盖互斥校验");

        private final String description;

        ValidationStage(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
}
