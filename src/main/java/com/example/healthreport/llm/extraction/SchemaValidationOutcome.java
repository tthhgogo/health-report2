package com.example.healthreport.llm.extraction;

import com.fasterxml.jackson.databind.JsonNode;

/** Schema 校验结果：已通过契约的输出节点与被剔除条目数。 */
public final class SchemaValidationOutcome {

    private final JsonNode validatedNode;
    private final int droppedItemCount;

    public SchemaValidationOutcome(JsonNode validatedNode, int droppedItemCount) {
        if (validatedNode == null || droppedItemCount < 0) {
            throw new IllegalArgumentException("Schema 校验结果参数无效");
        }
        this.validatedNode = validatedNode;
        this.droppedItemCount = droppedItemCount;
    }

    public JsonNode getValidatedNode() {
        return validatedNode;
    }

    public int getDroppedItemCount() {
        return droppedItemCount;
    }
}
