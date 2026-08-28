package com.example.healthreport.llm.extraction;

import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/** 在任何业务处理前执行 LLM-A JSON Schema 校验。 */
@Component
public class ExtractionSchemaValidator {

    /** 随应用打包的 LLM-A 输出契约路径。 */
    private static final String SCHEMA_PATH = "schema/extraction_output.schema.json";

    private final ObjectMapper objectMapper;
    private final JsonSchema jsonSchema;

    public ExtractionSchemaValidator(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("Schema 校验依赖不能为空");
        }
        this.objectMapper = objectMapper;
        this.jsonSchema = loadSchema(objectMapper);
    }

    /**
     * 解析并校验模型响应；失败固定映射 SERVER_ERROR，调用方不得重试。
     * <p>校验消息可能带模型值，因此本方法不记录消息正文。</p>
     */
    public JsonNode validate(String rawContent) {
        JsonNode rootNode;
        try {
            rootNode = objectMapper.readTree(rawContent);
        } catch (IOException | RuntimeException exception) {
            return failSchema();
        }
        if (rootNode == null) {
            return failSchema();
        }
        Set<ValidationMessage> validationMessageSet;
        try {
            validationMessageSet = jsonSchema.validate(rootNode);
        } catch (RuntimeException exception) {
            return failSchema();
        }
        if (!validationMessageSet.isEmpty()) {
            return failSchema();
        }
        return rootNode;
    }

    private JsonNode failSchema() {
        throw new HealthReportException(FailCode.SERVER_ERROR, 500);
    }

    private JsonSchema loadSchema(ObjectMapper mapper) {
        ClassPathResource resource = new ClassPathResource(SCHEMA_PATH);
        try (InputStream inputStream = resource.getInputStream()) {
            JsonNode schemaNode = mapper.readTree(inputStream);
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaNode);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("LLM-A Schema 加载失败", exception);
        }
    }
}
