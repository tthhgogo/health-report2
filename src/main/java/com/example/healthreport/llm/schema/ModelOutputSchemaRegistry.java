package com.example.healthreport.llm.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * LLM-A 与 LLM-B 输出契约的唯一加载点，应用启动时一次性完成。
 *
 * <p>存在的理由是<b>消掉两处重复加载</b>：{@code ExtractionSchemaValidator} 与
 * {@code DishTagContractValidator} 原先各有一个 {@code loadSchema}，
 * 两份 Schema 的编译入口因此有两个。</p>
 *
 * <p>任一资源缺失或无法编译都让<b>启动失败</b>：这些是部署期就能发现的问题，
 * 拖到第一次调用模型才暴露，代价是一个已经跑了一半的用户任务。</p>
 */
@Component
public class ModelOutputSchemaRegistry {

    /** LLM-A 完整版 Schema 的类路径位置。 */
    private static final String EXTRACTION_SCHEMA_PATH = "schema/extraction_output.schema.json";

    /** LLM-B 完整版 Schema 的类路径位置。 */
    private static final String DISH_TAG_SCHEMA_PATH = "schema/dish_tag_output.schema.json";

    private final ModelOutputSchema extractionSchema;

    private final ModelOutputSchema dishTagSchema;

    public ModelOutputSchemaRegistry(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("Schema 注册表依赖不能为空");
        }
        this.extractionSchema = load(EXTRACTION_SCHEMA_PATH, objectMapper);
        this.dishTagSchema = load(DISH_TAG_SCHEMA_PATH, objectMapper);
    }

    /** LLM-A 抽取输出契约。 */
    public ModelOutputSchema extraction() {
        return extractionSchema;
    }

    /** LLM-B 打标输出契约。 */
    public ModelOutputSchema dishTag() {
        return dishTagSchema;
    }

    private ModelOutputSchema load(String resourcePath, ObjectMapper objectMapper) {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        JsonNode canonicalSchemaNode;
        try (InputStream inputStream = resource.getInputStream()) {
            canonicalSchemaNode = objectMapper.readTree(inputStream);
        } catch (IOException exception) {
            throw new IllegalStateException("模型输出契约不可读：" + resourcePath, exception);
        }
        if (canonicalSchemaNode == null || !canonicalSchemaNode.isObject()) {
            throw new IllegalStateException("模型输出契约不是 JSON 对象：" + resourcePath);
        }
        return new ModelOutputSchema(canonicalSchemaNode);
    }
}
