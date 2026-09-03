package com.example.healthreport.llm.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.util.Set;

/**
 * 一份模型输出契约，只做校验。
 *
 * <p><b>2026-09-02 删掉了「传输版投影」那一半。</b> 它原本用于把 Schema 随
 * {@code response_format=json_schema} 发给模型，实测下来这条路没有价值：
 * LLM-B 用不了 {@code json_schema}；LLM-A 侧只有一个候选模型支持，
 * 而它剩下的失败是条件约束（{@code const}），投影必须剥掉、约束解码看不到。
 * 加上条目剔除机制已经把可用性救回来（§4.4-①），
 * 那部分只剩「少剔几条」的边际收益，却要每批多付约 6k token。</p>
 *
 * <p>一整块零生产调用的代码留着比删了危险——后来的人会当它在用而去维护、去同步。
 * 若要恢复，依据与设计记在设计方案 §4.4-①。</p>
 */
public final class ModelOutputSchema {

    private final JsonSchema jsonSchema;

    ModelOutputSchema(JsonNode canonicalSchemaNode) {
        this.jsonSchema = JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V202012).getSchema(canonicalSchemaNode);
    }

    /** 校验模型输出；条件约束（{@code if/then}、{@code allOf}）在这里生效。 */
    public Set<ValidationMessage> validate(JsonNode outputNode) {
        return jsonSchema.validate(outputNode);
    }
}
