package com.example.healthreport.llm.dishtag;

import com.example.healthreport.dish.Dish;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** LLM-B Schema、批次覆盖和互斥的唯一校验入口。 */
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
        try {
            JsonNode rootNode = objectMapper.readTree(responseJson);
            if (rootNode == null || !jsonSchema.validate(rootNode).isEmpty()) {
                throw rejected();
            }
            DishTagOutput output = objectMapper.treeToValue(rootNode, DishTagOutput.class);
            validateCoverage(output, expectedEnumKey, expectedDishIdSet);
            return output;
        } catch (DishTagBatchRejectedException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw rejected();
        }
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
}
