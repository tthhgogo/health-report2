package com.example.healthreport.llm.schema;

import com.example.healthreport.llm.extraction.ExtractionCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;

/**
 * 三份在线体检报告分析 Schema 与菜品打标 Schema 的唯一加载点，应用启动时一次性完成。
 *
 * <p>任一资源缺失或无法编译都让<b>启动失败</b>：这些是部署期就能发现的问题，
 * 拖到第一次调用模型才暴露，代价是一个已经跑了一半的用户任务。
 * 生产加载器显式拒绝文件名含 {@code probe} 的资源（开发方案 §6.6）。</p>
 */
@Component
public class ModelOutputSchemaRegistry {

    /** LLM-B 完整版 Schema 的类路径位置。 */
    private static final String DISH_TAG_SCHEMA_PATH = "schema/dish_tag_output.schema.json";

    private final Map<ExtractionCall, ModelOutputSchema> extractionSchemaMap;

    private final ModelOutputSchema dishTagSchema;

    public ModelOutputSchemaRegistry(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("Schema 注册表依赖不能为空");
        }
        this.extractionSchemaMap = new EnumMap<ExtractionCall, ModelOutputSchema>(ExtractionCall.class);
        for (ExtractionCall call : ExtractionCall.values()) {
            this.extractionSchemaMap.put(call, load(call.getSchemaResource(), objectMapper));
        }
        this.dishTagSchema = load(DISH_TAG_SCHEMA_PATH, objectMapper);
    }

    /** 指定调用的输出契约。 */
    public ModelOutputSchema extraction(ExtractionCall call) {
        ModelOutputSchema schema = extractionSchemaMap.get(call);
        if (schema == null) {
            throw new IllegalStateException("未注册的调用契约：" + call);
        }
        return schema;
    }

    /** LLM-B 打标输出契约。 */
    public ModelOutputSchema dishTag() {
        return dishTagSchema;
    }

    private ModelOutputSchema load(String resourcePath, ObjectMapper objectMapper) {
        if (resourcePath == null || resourcePath.toLowerCase().contains("probe")) {
            throw new IllegalStateException("生产加载器拒绝 probe 资源：" + resourcePath);
        }
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
