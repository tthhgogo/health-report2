package com.example.healthreport.llm.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Schema 校验结果：通过校验的（可能已剔除坏条目的）输出，以及剔除了什么。 */
@Getter
public final class SchemaValidationOutcome {

    /** 已通过完整版 Schema 的输出；坏条目已被移除。 */
    private final JsonNode rootNode;

    /** 每个数组各剔除了几条，用于降级留痕与观测。 */
    private final Map<String, Integer> droppedCountByArray;

    SchemaValidationOutcome(JsonNode rootNode, Map<String, Integer> droppedCountByArray) {
        this.rootNode = rootNode;
        this.droppedCountByArray = Collections.unmodifiableMap(
                new LinkedHashMap<String, Integer>(droppedCountByArray));
    }

    /** 是否剔除过条目；剔除即为部分结果。 */
    public boolean hasDropped() {
        return !droppedCountByArray.isEmpty();
    }

}
