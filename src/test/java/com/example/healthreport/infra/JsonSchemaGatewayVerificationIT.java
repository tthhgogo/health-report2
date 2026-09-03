package com.example.healthreport.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 使用两份真实业务 Schema 验证 OpenAI 兼容网关的 json_schema 支持能力。
 *
 * <p>本测试以 IT 结尾，默认不会进入 Maven Surefire 全量测试；仅在人工显式指定本类时访问网关。
 * 请求与响应正文均不写日志，避免 LLM-A 排障过程中意外记录健康数据。</p>
 */
@Slf4j(topic = "JSON_SCHEMA_GATEWAY_VERIFICATION")
class JsonSchemaGatewayVerificationIT {

    // ==================== 全部走环境变量，源码里不出现任何凭证 ====================
    //
    // 变量名与 application.properties 一致，跑应用本来就要配这几个：
    //     export EXTRACTION_API_KEY=... EXTRACTION_MODEL=...
    //     export DISHTAG_API_KEY=...    DISHTAG_MODEL=...
    // 不验证某一侧时留空即可，configuredTargets() 会跳过它。
    //
    // 【不要改成源码常量】填进去跑完忘了清就永久进 git 历史，删不干净。

    /** LLM-A 的 API Key，与 llm.extraction.api-key 同源。 */
    private static final String LLM_A_API_KEY = env("EXTRACTION_API_KEY");

    /** LLM-A 模型名称；只与 extraction Schema 配对。 */
    private static final String LLM_A_MODEL_NAME = env("EXTRACTION_MODEL");

    /** LLM-B 的 API Key，与 llm.dishtag.api-key 同源。 */
    private static final String LLM_B_API_KEY = env("DISHTAG_API_KEY");

    /** LLM-B 模型名称；只与 dish-tag Schema 配对。 */
    private static final String LLM_B_MODEL_NAME = env("DISHTAG_MODEL");

    /** 未设置的环境变量按空串处理，等价于「本次不验证这一侧」。 */
    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }

    // ==================== 以下通常不需要修改 ====================

    /**
     * OpenAI 兼容 Chat Completions Endpoint。
     * <p>默认值是测试环境的明文 HTTP；<b>网关一旦提供 HTTPS 就应改用 HTTPS</b>——
     * 请求头里带着 Bearer token。设置 {@code EXTRACTION_BASE_URL} 可整体覆盖。</p>
     * <p><b>声明顺序有意排在回退值之后</b>：静态字段按声明顺序初始化，
     * 放在前面会让 {@link #chatCompletionsUrl()} 读到还没赋值的回退值。</p>
     */
    private static final String CHAT_COMPLETIONS_URL = chatCompletionsUrl();

    /**
     * 端点必须显式配置，且非本机地址只接受 HTTPS。
     *
     * <p><b>不留默认远程地址</b>：内置一个明文 HTTP 端点，再配上请求头里的 Bearer token，
     * 等于给「凭证走明文」留了一条零阻力的默认路径。宁可未配置时失败。</p>
     */
    private static String chatCompletionsUrl() {
        String baseUrl = env("EXTRACTION_BASE_URL");
        if (baseUrl.isEmpty()) {
            return "";
        }
        String normalizedBaseUrl = baseUrl.replaceAll("/+$", "");
        if (normalizedBaseUrl.startsWith("http://") && !isLoopback(normalizedBaseUrl)) {
            throw new AssertionError("EXTRACTION_BASE_URL 是非本机的明文 HTTP，而请求会携带 "
                    + "Bearer token；请改用 HTTPS，或确认网关只在本机可达");
        }
        return normalizedBaseUrl + "/v1/chat/completions";
    }

    /** 本机地址允许明文，便于对着本地 mock 调试。 */
    private static boolean isLoopback(String url) {
        String host = url.substring("http://".length());
        int end = host.indexOf('/');
        if (end >= 0) {
            host = host.substring(0, end);
        }
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
    }

    /** LLM-A 验证请求输出上限；仅用于网关能力验证，待真实报告联调后校准。 */
    private static final int LLM_A_MAX_TOKENS = 16384;

    /** LLM-B 验证请求输出上限；仅用于网关能力验证，待真实菜品联调后校准。 */
    private static final int LLM_B_MAX_TOKENS = 8192;

    /**
     * 截断行为探针的输出上限；故意配到装不下任何合法 JSON，用于观测网关截断时的 finish_reason。
     * <p>生产代码的 {@code finish_reason == "stop"} 校验只有在本探针确认网关返回标准值后才成立。</p>
     */
    private static final int TRUNCATION_PROBE_MAX_TOKENS = 16;

    /** 单次网关连接超时，单位毫秒。 */
    private static final int CONNECT_TIMEOUT_MILLIS = 30000;

    /** 单次网关读取超时，单位毫秒；复杂 Schema 首次编译可能较慢。 */
    private static final int READ_TIMEOUT_MILLIS = 300000;

    /** 响应体最大读取字节数，防止异常网关响应耗尽测试 JVM 内存。 */
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    /** LLM-A 完整 Schema 的仓库相对路径。 */
    private static final String EXTRACTION_SCHEMA_PATH = "schema/extraction_output.schema.json";

    /** LLM-B 完整 Schema 的仓库相对路径。 */
    private static final String DISH_TAG_SCHEMA_PATH = "schema/dish_tag_output.schema.json";

    /** LLM-A 当前提示词的仓库相对路径。 */
    private static final String EXTRACTION_PROMPT_PATH = "prompt/extraction.md";

    /** LLM-B 当前提示词的仓库相对路径。 */
    private static final String DISH_TAG_PROMPT_PATH = "prompt/dish_tag.md";

    /** OpenAI 结构化输出使用的请求类型。 */
    private static final String JSON_SCHEMA_RESPONSE_TYPE = "json_schema";

    /** 传输版 Schema 明确允许保留并递归处理的关键字。 */
    private static final Set<String> SUPPORTED_SCHEMA_KEYWORD_SET = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("$ref", "$defs", "description", "type", "properties",
                    "required", "additionalProperties", "items", "enum", "const", "minimum",
                    "maximum", "minLength", "maxLength", "minItems", "maxItems", "uniqueItems",
                    "pattern", "oneOf")));

    /** 传输版固定删除的纯元数据关键字。 */
    private static final Set<String> METADATA_SCHEMA_KEYWORD_SET = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("$schema", "$id", "title", "$comment")));

    /** 网关约束解码通常不支持、且由提示词或 Java 后置校验承担的条件关键字。 */
    private static final Set<String> CONDITIONAL_SCHEMA_KEYWORD_SET = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("if", "then", "else", "allOf", "not")));

    /** HTTP 成功状态码的下界。 */
    private static final int HTTP_SUCCESS_MINIMUM = 200;

    /** HTTP 成功状态码的上界。 */
    private static final int HTTP_SUCCESS_MAXIMUM = 299;

    /** Markdown JSON 围栏开头，用于兼容未完全遵守输出要求的模型。 */
    private static final String JSON_FENCE_PREFIX = "```json";

    /** 普通 Markdown 围栏开头，用于兼容未标注语言的模型输出。 */
    private static final String FENCE_PREFIX = "```";

    /** Markdown 围栏结尾。 */
    private static final String FENCE_SUFFIX = "```";

    /** 推理模型思考段开头。 */
    private static final String THINK_PREFIX = "<think>";

    /** 推理模型思考段结尾。 */
    private static final String THINK_SUFFIX = "</think>";

    /** 测试内统一使用的 JSON 读写器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Draft 2020-12 Schema 编译器，与生产校验器保持一致。 */
    private final JsonSchemaFactory jsonSchemaFactory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    /**
     * 本地验证两份 Schema 均可完成白名单投影和编译，不访问任何网关。
     */
    @Test
    void shouldProjectAndCompileBothRealSchemasLocally() throws Exception {
        enableVerificationInfoLogs();
        for (Target target : Target.values()) {
            SchemaBundle schemaWithDescription = loadSchemaBundle(target, true);
            SchemaBundle schemaWithoutDescription = loadSchemaBundle(target, false);
            if (schemaWithoutDescription.transportSchemaBytes
                    >= schemaWithDescription.transportSchemaBytes) {
                throw new AssertionError(target.displayName + " 去除 description 后 Schema 未缩小");
            }
        }
    }

    /**
     * 对每个已配置目标依次验证保留 description、去除 description、Schema 可生成性与截断行为。
     */
    @Test
    void shouldVerifyRealJsonSchemasAgainstConfiguredGateways() throws Exception {
        enableVerificationInfoLogs();
        List<TargetConfig> targetConfigList = configuredTargets();
        List<Executable> verificationExecutableList =
                new ArrayList<Executable>(targetConfigList.size() * 4);
        for (TargetConfig targetConfig : targetConfigList) {
            verificationExecutableList.add(() -> verifyNormalRequest(targetConfig, true));
            verificationExecutableList.add(() -> verifyNormalRequest(targetConfig, false));
            verificationExecutableList.add(() -> verifyConstraintProbe(targetConfig));
            verificationExecutableList.add(() -> verifyTruncationBehaviour(targetConfig));
        }
        Assertions.assertAll("JSON Schema 网关验证存在失败场景", verificationExecutableList);
    }

    /** 使用真实提示词与安全合成输入验证正常结构化输出，并记录输入 token。 */
    private void verifyNormalRequest(TargetConfig targetConfig, boolean keepDescription) throws Exception {
        SchemaBundle schemaBundle = loadSchemaBundle(targetConfig.target, keepDescription);
        ObjectNode requestNode = newRequest(targetConfig, schemaBundle,
                normalMessages(targetConfig.target), targetConfig.maxTokens);
        verifyGatewayResponse(targetConfig, schemaBundle, Scenario.NORMAL, requestNode);
    }

    /** 使用短提示词验证真实投影 Schema 能否约束出一个完整且可终止的合法对象。 */
    private void verifyConstraintProbe(TargetConfig targetConfig) throws Exception {
        SchemaBundle schemaBundle = loadSchemaBundle(targetConfig.target, false);
        ObjectNode requestNode = newRequest(targetConfig, schemaBundle,
                constraintProbeMessages(targetConfig.target), targetConfig.maxTokens);
        verifyGatewayResponse(targetConfig, schemaBundle, Scenario.CONSTRAINT_PROBE, requestNode);
    }

    /**
     * 把 max_tokens 压到装不下任何合法输出，观测网关截断时的真实 finish_reason。
     *
     * <p><b>这一项决定生产代码怎么写。</b> 开发方案 §13.2.4 要求 LLM-A、LLM-B 都校验
     * {@code finish_reason == "stop"}，而 OpenAI 兼容层的实现质量参差：有的返回标准
     * {@code length}，有的返回 {@code null}、空串或自定义值。若网关截断时不给标准值，
     * 那条校验上线即批批失败——必须在写代码之前知道，不能等联调。</p>
     *
     * <p>本场景【不】校验响应 JSON：截断的输出本来就不完整，能剥出内容也不可信。</p>
     */
    private void verifyTruncationBehaviour(TargetConfig targetConfig) throws Exception {
        SchemaBundle schemaBundle = loadSchemaBundle(targetConfig.target, false);
        ObjectNode requestNode = newRequest(targetConfig, schemaBundle,
                normalMessages(targetConfig.target), TRUNCATION_PROBE_MAX_TOKENS);
        byte[] requestBytes = objectMapper.writeValueAsBytes(requestNode);
        log.info("JSON Schema 网关调用开始：目标={}，场景={}，保留描述=false，请求字节数={}，"
                        + "读取超时毫秒={}",
                targetConfig.target.displayName, Scenario.TRUNCATION.displayName,
                requestBytes.length, READ_TIMEOUT_MILLIS);
        HttpResult httpResult = executeHttp(targetConfig, requestBytes,
                Scenario.TRUNCATION.displayName);

        if (httpResult.statusCode < HTTP_SUCCESS_MINIMUM
                || httpResult.statusCode > HTTP_SUCCESS_MAXIMUM) {
            // 网关对极小 max_tokens 直接报错也是必须知道的行为，同样属于本探针的产出。
            throw new AssertionError("网关拒绝极小 max_tokens 请求：目标="
                    + targetConfig.target.displayName + "，状态码=" + httpResult.statusCode
                    + "，max_tokens=" + TRUNCATION_PROBE_MAX_TOKENS
                    + "，完整错误响应=" + new String(httpResult.responseBytes, StandardCharsets.UTF_8));
        }

        JsonNode envelopeNode = parseJsonWithoutContentLeak(httpResult.responseBytes, "截断探针响应信封");
        JsonNode choiceNode = envelopeNode.path("choices").path(0);
        if (choiceNode.isMissingNode()) {
            throw new AssertionError("截断探针响应缺少 choices[0]：目标="
                    + targetConfig.target.displayName);
        }

        JsonNode finishReasonNode = choiceNode.path("finish_reason");
        String finishReasonShape = finishReasonNode.isMissingNode() ? "字段缺失"
                : finishReasonNode.isNull() ? "JSON null" : "字符串";
        String finishReason = finishReasonNode.asText("");

        if (finishReasonNode.isMissingNode() || finishReasonNode.isNull() || finishReason.isEmpty()) {
            throw new AssertionError("网关截断时不返回可用的 finish_reason（" + finishReasonShape
                    + "）：目标=" + targetConfig.target.displayName
                    + "。生产代码不能依赖 finish_reason 判断截断，需改用其他信号（如"
                    + " usage.completion_tokens 是否触顶 max_tokens），开发方案 §13.2.4 要一并修订");
        }
        if ("stop".equals(finishReason)) {
            throw new AssertionError("网关在 max_tokens=" + TRUNCATION_PROBE_MAX_TOKENS
                    + " 时仍返回 stop，说明它忽略了 max_tokens：目标="
                    + targetConfig.target.displayName
                    + "。llm.extraction.max-tokens 将无法防止超长输出，需要另找上限手段");
        }

        JsonNode usageNode = envelopeNode.path("usage");
        JsonNode messageNode = choiceNode.path("message");
        log.info("截断行为探针完成：目标={}，场景={}，finish_reason={}，形态={}，"
                        + "请求max_tokens={}，输出token={}，content字符数={}，reasoning_content字符数={}",
                targetConfig.target.displayName, Scenario.TRUNCATION.displayName, finishReason,
                finishReasonShape, TRUNCATION_PROBE_MAX_TOKENS,
                usageNode.path("completion_tokens").asInt(-1),
                messageNode.path("content").asText("").length(),
                messageNode.path("reasoning_content").asText("").length());
    }

    /** 组装 OpenAI Chat Completions 的 json_schema 请求。 */
    private ObjectNode newRequest(TargetConfig targetConfig, SchemaBundle schemaBundle,
                                  ArrayNode messagesNode, int maxTokens) {
        ObjectNode requestNode = objectMapper.createObjectNode();
        requestNode.put("model", targetConfig.model);
        requestNode.put("temperature", 0);
        requestNode.put("stream", false);
        requestNode.put("max_tokens", maxTokens);
        requestNode.set("messages", messagesNode);

        ObjectNode jsonSchemaNode = objectMapper.createObjectNode();
        jsonSchemaNode.put("name", targetConfig.target.responseFormatName);
        jsonSchemaNode.put("strict", true);
        jsonSchemaNode.set("schema", schemaBundle.transportSchemaNode);

        ObjectNode responseFormatNode = objectMapper.createObjectNode();
        responseFormatNode.put("type", JSON_SCHEMA_RESPONSE_TYPE);
        responseFormatNode.set("json_schema", jsonSchemaNode);
        requestNode.set("response_format", responseFormatNode);
        return requestNode;
    }

    /** 调用网关并执行协议、截断、拒答，以及当前场景要求的 Schema 校验。 */
    private void verifyGatewayResponse(TargetConfig targetConfig, SchemaBundle schemaBundle,
                                       Scenario scenario, ObjectNode requestNode) throws Exception {
        byte[] requestBytes = objectMapper.writeValueAsBytes(requestNode);
        log.info("JSON Schema 网关调用开始：目标={}，场景={}，保留描述={}，请求字节数={}，"
                        + "读取超时毫秒={}",
                targetConfig.target.displayName, scenario.displayName,
                schemaBundle.keepDescription, requestBytes.length, READ_TIMEOUT_MILLIS);
        long startedNanos = System.nanoTime();
        HttpResult httpResult = executeHttp(targetConfig, requestBytes, scenario.displayName);
        long elapsedMillis = (System.nanoTime() - startedNanos) / 1000000L;

        if (httpResult.statusCode < HTTP_SUCCESS_MINIMUM
                || httpResult.statusCode > HTTP_SUCCESS_MAXIMUM) {
            String errorResponse = new String(httpResult.responseBytes, StandardCharsets.UTF_8);
            throw new AssertionError("网关拒绝真实 Schema：目标=" + targetConfig.target.displayName
                    + "，状态码=" + httpResult.statusCode
                    + "，Authorization头=" + (targetConfig.apiKey.isEmpty() ? "未发送" : "已发送")
                    + "，响应字节数=" + httpResult.responseBytes.length
                    + "，完整错误响应=" + errorResponse);
        }

        JsonNode envelopeNode = parseJsonWithoutContentLeak(httpResult.responseBytes, "网关响应信封");
        JsonNode choiceNode = envelopeNode.path("choices").path(0);
        if (choiceNode.isMissingNode()) {
            throw new AssertionError("网关响应缺少 choices[0]：目标=" + targetConfig.target.displayName);
        }
        String finishReason = choiceNode.path("finish_reason").asText("");
        JsonNode usageNode = envelopeNode.path("usage");
        int promptTokens = usageNode.path("prompt_tokens").asInt(-1);
        int completionTokens = usageNode.path("completion_tokens").asInt(-1);
        int totalTokens = usageNode.path("total_tokens").asInt(-1);
        JsonNode messageNode = choiceNode.path("message");
        String contentText = messageNode.path("content").asText("");
        String reasoningContentText = messageNode.path("reasoning_content").asText("");
        if (!"stop".equals(finishReason)) {
            throw new AssertionError("网关响应疑似截断：目标=" + targetConfig.target.displayName
                    + "，finish_reason=" + finishReason
                    + "，请求max_tokens=" + targetConfig.maxTokens
                    + "，prompt_tokens=" + promptTokens
                    + "，completion_tokens=" + completionTokens
                    + "，total_tokens=" + totalTokens
                    + "，content字符数=" + contentText.length()
                    + "，reasoning_content字符数=" + reasoningContentText.length()
                    + "，完整usage=" + usageNode.toString());
        }

        JsonNode refusalNode = choiceNode.path("message").path("refusal");
        if (!refusalNode.isMissingNode() && !refusalNode.isNull()
                && !refusalNode.asText("").trim().isEmpty()) {
            throw new AssertionError("网关返回 refusal：目标=" + targetConfig.target.displayName);
        }

        String rawContent = selectModelJsonText(targetConfig.target, contentText,
                reasoningContentText);
        JsonNode outputNode = parseJsonContentWithoutLeak(rawContent, targetConfig.target);
        assertSchemaValid(schemaBundle.transportJsonSchema, outputNode,
                targetConfig.target.displayName + " 传输版 Schema");
        // 约束探针故意攻击传输版保留的结构关键字；被投影删除的条件规则不属于网关验收范围。
        if (scenario == Scenario.NORMAL) {
            assertSchemaValid(schemaBundle.canonicalJsonSchema, outputNode,
                    targetConfig.target.displayName + " 完整版 Schema");
        }

        if (promptTokens < 0) {
            throw new AssertionError("网关未返回 usage.prompt_tokens，无法完成真实 token 实测：目标="
                    + targetConfig.target.displayName);
        }
        log.info("JSON Schema 网关验证通过：目标={}，场景={}，保留描述={}，Schema字节数={}，"
                        + "请求字节数={}，响应字节数={}，输入token={}，输出token={}，耗时毫秒={}",
                targetConfig.target.displayName, scenario.displayName, schemaBundle.keepDescription,
                schemaBundle.transportSchemaBytes, requestBytes.length, httpResult.responseBytes.length,
                promptTokens, completionTokens, elapsedMillis);
    }

    /**
     * LLM-A 在 content / reasoning_content 中选择唯一合法 JSON；LLM-B 仍遵循标准 content 契约。
     */
    private String selectModelJsonText(Target target, String contentText,
                                       String reasoningContentText) {
        if (target == Target.DISH_TAG) {
            if (contentText.trim().isEmpty()) {
                throw new AssertionError("LLM-B 网关响应 content 为空");
            }
            return contentText;
        }
        JsonNode contentObjectNode = parseJsonObjectOrNull(contentText);
        JsonNode reasoningContentObjectNode = parseJsonObjectOrNull(reasoningContentText);
        if (contentObjectNode != null && reasoningContentObjectNode != null) {
            if (!contentObjectNode.equals(reasoningContentObjectNode)) {
                throw new AssertionError("LLM-A 的 content 与 reasoning_content 均为合法JSON但内容不同");
            }
            return contentText;
        }
        if (contentObjectNode != null) {
            return contentText;
        }
        if (reasoningContentObjectNode != null) {
            return reasoningContentText;
        }
        throw new AssertionError("LLM-A 的 content 与 reasoning_content 均不含合法JSON对象："
                + "content字符数=" + contentText.length()
                + "，reasoning_content字符数=" + reasoningContentText.length());
    }

    /** 尝试把候选文本完整解析为 JSON 对象；失败仅用于选择另一响应通道。 */
    private JsonNode parseJsonObjectOrNull(String candidateText) {
        if (candidateText == null || candidateText.trim().isEmpty()) {
            return null;
        }
        try {
            JsonNode parsedNode = objectMapper.readTree(candidateText);
            return parsedNode != null && parsedNode.isObject() ? parsedNode : null;
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    /** 发起一次无重试、有界读取的 HTTP 请求，不记录请求或响应正文。 */
    private HttpResult executeHttp(TargetConfig targetConfig, byte[] requestBytes,
                                   String scenarioName) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(targetConfig.url).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Accept", "application/json");
            if (!targetConfig.apiKey.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + targetConfig.apiKey);
            }
            connection.setFixedLengthStreamingMode(requestBytes.length);
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(requestBytes);
            }

            int statusCode = connection.getResponseCode();
            InputStream responseStream = statusCode >= HTTP_SUCCESS_MINIMUM
                    && statusCode <= HTTP_SUCCESS_MAXIMUM
                    ? connection.getInputStream() : connection.getErrorStream();
            byte[] responseBytes = readBounded(responseStream);
            return new HttpResult(statusCode, responseBytes);
        } catch (SocketTimeoutException exception) {
            throw new AssertionError("网关连接或读取超时：目标=" + targetConfig.target.displayName
                    + "，场景=" + scenarioName
                    + "，超时毫秒=" + READ_TIMEOUT_MILLIS
                    + "，请求字节数=" + requestBytes.length, exception);
        } finally {
            connection.disconnect();
        }
    }

    /** 有界读取网关响应，超过限制立即失败。 */
    private byte[] readBounded(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return new byte[0];
        }
        try (InputStream stream = inputStream;
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream(8192)) {
            byte[] buffer = new byte[8192];
            int totalBytes = 0;
            int readBytes;
            while ((readBytes = stream.read(buffer)) >= 0) {
                totalBytes += readBytes;
                if (totalBytes > MAX_RESPONSE_BYTES) {
                    throw new AssertionError("网关响应超过测试读取上限，已停止读取");
                }
                outputStream.write(buffer, 0, readBytes);
            }
            return outputStream.toByteArray();
        }
    }

    /** 加载完整版 Schema，生成传输版并分别编译校验器。 */
    private SchemaBundle loadSchemaBundle(Target target, boolean keepDescription) throws IOException {
        byte[] canonicalBytes = Files.readAllBytes(repositoryPath(target.schemaPath));
        JsonNode canonicalSchemaNode = objectMapper.readTree(canonicalBytes);
        ProjectionStatistics projectionStatistics = new ProjectionStatistics();
        JsonNode transportSchemaNode = projectSchema(canonicalSchemaNode, canonicalSchemaNode,
                keepDescription, projectionStatistics);
        int transportSchemaBytes = objectMapper.writeValueAsBytes(transportSchemaNode).length;
        log.info("传输版 Schema 生成完成：目标={}，保留描述={}，字节数={}，删除条件关键字数={}，"
                        + "nullable引用展开数={}",
                target.displayName, keepDescription, transportSchemaBytes,
                projectionStatistics.removedConditionalKeywordCount,
                projectionStatistics.expandedNullableReferenceCount);
        return new SchemaBundle(transportSchemaNode, jsonSchemaFactory.getSchema(canonicalSchemaNode),
                jsonSchemaFactory.getSchema(transportSchemaNode), keepDescription,
                transportSchemaBytes);
    }

    /**
     * 白名单式投影 Schema；遇到未登记关键字即失败，避免网关约束被静默削弱。
     */
    private JsonNode projectSchema(JsonNode schemaNode, JsonNode rootSchemaNode,
                                   boolean keepDescription,
                                   ProjectionStatistics projectionStatistics) {
        if (!schemaNode.isObject()) {
            return schemaNode.deepCopy();
        }
        ObjectNode sourceObjectNode = (ObjectNode) schemaNode;
        if (sourceObjectNode.has("oneOf")) {
            sourceObjectNode = expandNullableReference(sourceObjectNode, rootSchemaNode,
                    projectionStatistics);
        }

        ObjectNode projectedObjectNode = objectMapper.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fieldIterator = sourceObjectNode.fields();
        while (fieldIterator.hasNext()) {
            Map.Entry<String, JsonNode> fieldEntry = fieldIterator.next();
            String fieldName = fieldEntry.getKey();
            JsonNode fieldValueNode = fieldEntry.getValue();
            if (METADATA_SCHEMA_KEYWORD_SET.contains(fieldName)) {
                continue;
            }
            if (CONDITIONAL_SCHEMA_KEYWORD_SET.contains(fieldName)) {
                projectionStatistics.removedConditionalKeywordCount++;
                continue;
            }
            if ("description".equals(fieldName) && !keepDescription) {
                continue;
            }
            if (!SUPPORTED_SCHEMA_KEYWORD_SET.contains(fieldName)) {
                throw new AssertionError("Schema 投影遇到未登记关键字：" + fieldName);
            }
            if ("properties".equals(fieldName) || "$defs".equals(fieldName)) {
                projectedObjectNode.set(fieldName, projectSchemaMap(fieldValueNode, rootSchemaNode,
                        keepDescription, projectionStatistics));
            } else if ("items".equals(fieldName)) {
                projectedObjectNode.set(fieldName, projectSchema(fieldValueNode, rootSchemaNode,
                        keepDescription, projectionStatistics));
            } else {
                projectedObjectNode.set(fieldName, fieldValueNode.deepCopy());
            }
        }
        return projectedObjectNode;
    }

    /** 递归投影 properties 与 $defs 中以字段名为键的 Schema 映射。 */
    private ObjectNode projectSchemaMap(JsonNode schemaMapNode, JsonNode rootSchemaNode,
                                        boolean keepDescription,
                                        ProjectionStatistics projectionStatistics) {
        if (!schemaMapNode.isObject()) {
            throw new AssertionError("Schema 的 properties/$defs 必须是对象");
        }
        ObjectNode projectedMapNode = objectMapper.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fieldIterator = schemaMapNode.fields();
        while (fieldIterator.hasNext()) {
            Map.Entry<String, JsonNode> fieldEntry = fieldIterator.next();
            projectedMapNode.set(fieldEntry.getKey(), projectSchema(fieldEntry.getValue(), rootSchemaNode,
                    keepDescription, projectionStatistics));
        }
        return projectedMapNode;
    }

    /**
     * 把当前唯一支持的 oneOf 形状“本地 $ref 或 null”展开成带完整对象约束的 type union。
     */
    private ObjectNode expandNullableReference(ObjectNode sourceObjectNode, JsonNode rootSchemaNode,
                                               ProjectionStatistics projectionStatistics) {
        JsonNode oneOfNode = sourceObjectNode.path("oneOf");
        if (!oneOfNode.isArray() || oneOfNode.size() != 2) {
            throw new AssertionError("只允许投影 [$ref, null] 两分支 oneOf");
        }
        JsonNode referenceBranchNode = null;
        boolean hasNullBranch = false;
        for (JsonNode branchNode : oneOfNode) {
            if (branchNode.isObject() && branchNode.size() == 1 && branchNode.has("$ref")) {
                referenceBranchNode = branchNode;
            } else if (branchNode.isObject() && branchNode.size() == 1
                    && "null".equals(branchNode.path("type").asText())) {
                hasNullBranch = true;
            } else {
                throw new AssertionError("oneOf 出现未登记分支形状");
            }
        }
        if (referenceBranchNode == null || !hasNullBranch) {
            throw new AssertionError("oneOf 必须同时包含本地 $ref 与 null 分支");
        }

        String reference = referenceBranchNode.path("$ref").asText();
        if (!reference.startsWith("#/")) {
            throw new AssertionError("只允许展开当前 Schema 内的本地 $ref");
        }
        JsonNode referencedSchemaNode = rootSchemaNode.at(reference.substring(1));
        if (!referencedSchemaNode.isObject() || referencedSchemaNode.isMissingNode()) {
            throw new AssertionError("oneOf 的本地 $ref 无法解析");
        }

        ObjectNode expandedObjectNode = ((ObjectNode) referencedSchemaNode).deepCopy();
        Iterator<Map.Entry<String, JsonNode>> outerFieldIterator = sourceObjectNode.fields();
        while (outerFieldIterator.hasNext()) {
            Map.Entry<String, JsonNode> fieldEntry = outerFieldIterator.next();
            if (!"oneOf".equals(fieldEntry.getKey())) {
                expandedObjectNode.set(fieldEntry.getKey(), fieldEntry.getValue().deepCopy());
            }
        }

        JsonNode originalTypeNode = expandedObjectNode.get("type");
        if (originalTypeNode == null || !originalTypeNode.isTextual()) {
            throw new AssertionError("nullable $ref 目标必须声明单一字符串 type");
        }
        ArrayNode nullableTypeNode = objectMapper.createArrayNode();
        nullableTypeNode.add(originalTypeNode.asText());
        nullableTypeNode.add("null");
        expandedObjectNode.set("type", nullableTypeNode);
        assertNullableStructurePreserved(referencedSchemaNode, expandedObjectNode);
        projectionStatistics.expandedNullableReferenceCount++;
        return expandedObjectNode;
    }

    /** 防止 nullable 转换时误丢 properties 或 required 约束。 */
    private void assertNullableStructurePreserved(JsonNode referencedSchemaNode,
                                                  JsonNode expandedSchemaNode) {
        Set<String> originalPropertySet = objectFieldNameSet(referencedSchemaNode.path("properties"));
        Set<String> expandedPropertySet = objectFieldNameSet(expandedSchemaNode.path("properties"));
        if (!originalPropertySet.equals(expandedPropertySet)) {
            throw new AssertionError("nullable $ref 展开前后 properties 集合不一致");
        }
        Set<String> originalRequiredSet = textArrayValueSet(referencedSchemaNode.path("required"));
        Set<String> expandedRequiredSet = textArrayValueSet(expandedSchemaNode.path("required"));
        if (!originalRequiredSet.equals(expandedRequiredSet)) {
            throw new AssertionError("nullable $ref 展开前后 required 集合不一致");
        }
    }

    /** 提取对象字段名集合；缺失 properties 按空集合处理。 */
    private Set<String> objectFieldNameSet(JsonNode objectNode) {
        if (objectNode.isMissingNode()) {
            return Collections.emptySet();
        }
        if (!objectNode.isObject()) {
            throw new AssertionError("properties 必须是对象");
        }
        Set<String> fieldNameSet = new LinkedHashSet<String>();
        Iterator<String> fieldNameIterator = objectNode.fieldNames();
        while (fieldNameIterator.hasNext()) {
            fieldNameSet.add(fieldNameIterator.next());
        }
        return fieldNameSet;
    }

    /** 提取字符串数组值集合；缺失 required 按空集合处理。 */
    private Set<String> textArrayValueSet(JsonNode arrayNode) {
        if (arrayNode.isMissingNode()) {
            return Collections.emptySet();
        }
        if (!arrayNode.isArray()) {
            throw new AssertionError("required 必须是数组");
        }
        Set<String> valueSet = new LinkedHashSet<String>();
        for (JsonNode valueNode : arrayNode) {
            if (!valueNode.isTextual()) {
                throw new AssertionError("required 元素必须是字符串");
            }
            valueSet.add(valueNode.asText());
        }
        return valueSet;
    }

    /** 生成使用仓库真实提示词的安全合成消息。 */
    private ArrayNode normalMessages(Target target) throws IOException {
        String systemPrompt = readUtf8(target.promptPath);
        String extractionPromptVersion = extractPromptVersion(systemPrompt);
        String userPrompt = target == Target.EXTRACTION
                ? "fileIndex=0\nbatchIndex=0\nbatchCount=1\npromptVersion="
                + extractionPromptVersion + "\n"
                + "=== 第 1 页 ===\n[0] 一般检查\n[1] 身高\n[2] 170\n[3] cm\n"
                : "【本批维度】\nenumKey: LOW_FAT\n展示名: 低脂\n"
                + "需避免食材: [肥肉, 动物油]\n避免的菜式: [油炸]\n烹饪方式建议: [少油]\n"
                + "【本批菜品】共 2 道\n- dishId=10001 清蒸西兰花\n"
                + "    西兰花 200g\n- dishId=10002 炸五花肉\n    五花肉 180g\n";
        return textMessages(systemPrompt, userPrompt);
    }

    /** 从真实提示词头部读取 promptVersion，避免验证样本随版本升级失效。 */
    private String extractPromptVersion(String prompt) {
        String marker = "promptVersion = ";
        int valueStartIndex = prompt.indexOf(marker);
        if (valueStartIndex < 0) {
            throw new AssertionError("真实提示词缺少 promptVersion");
        }
        valueStartIndex += marker.length();
        int valueEndIndex = prompt.indexOf('`', valueStartIndex);
        if (valueEndIndex < 0) {
            valueEndIndex = prompt.indexOf('\n', valueStartIndex);
        }
        if (valueEndIndex < 0) {
            valueEndIndex = prompt.length();
        }
        String promptVersion = prompt.substring(valueStartIndex, valueEndIndex).trim();
        if (promptVersion.isEmpty()) {
            throw new AssertionError("真实提示词的 promptVersion 为空");
        }
        return promptVersion;
    }

    /**
     * 生成不与 response_format 对抗的可生成性探针。
     * 原恶意探针要求模型故意违反 Schema，实测会让约束解码反复拒绝采样直到耗尽 16384 token，
     * 因而测到的是冲突死循环而不是 Schema 支持能力。
     */
    private ArrayNode constraintProbeMessages(Target target) {
        String systemPrompt = "这是 JSON Schema 约束解码可生成性测试。response_format 是最高约束；"
                + "只输出一个满足它的最小 JSON 对象，不要解释。";
        String userPrompt = target == Target.EXTRACTION
                ? "生成最小合法的体检抽取批次对象。没有报告内容，所有业务数组优先为空，"
                + "不得编造患者或指标。"
                : "生成最小合法的菜品打标对象。enumKey 使用 LOW_FAT，三个结果集合都为空。";
        return textMessages(systemPrompt, userPrompt);
    }

    /** 组装两个纯文本 Chat 消息。 */
    private ArrayNode textMessages(String systemPrompt, String userPrompt) {
        ArrayNode messagesNode = objectMapper.createArrayNode();
        messagesNode.add(message("system", systemPrompt));
        messagesNode.add(message("user", userPrompt));
        return messagesNode;
    }

    /** 组装单条纯文本 Chat 消息。 */
    private ObjectNode message(String role, String content) {
        ObjectNode messageNode = objectMapper.createObjectNode();
        messageNode.put("role", role);
        messageNode.put("content", content);
        return messageNode;
    }

    /** Schema 校验失败时完整输出全部 NetworkNT 校验消息，供人工联调定位。 */
    private void assertSchemaValid(JsonSchema jsonSchema, JsonNode outputNode, String schemaName) {
        Set<ValidationMessage> violationSet = jsonSchema.validate(outputNode);
        if (!violationSet.isEmpty()) {
            List<String> violationMessageList = new ArrayList<String>(violationSet.size());
            for (ValidationMessage validationMessage : violationSet) {
                violationMessageList.add(validationMessage.toString());
            }
            Collections.sort(violationMessageList);
            throw new AssertionError(schemaName + " 校验失败，违规数量=" + violationSet.size()
                    + "，全部违规消息=" + violationMessageList);
        }
    }

    /** 解析网关外层信封；异常信息不附带响应正文或 Jackson 原始异常。 */
    private JsonNode parseJsonWithoutContentLeak(byte[] jsonBytes, String sourceName) {
        try {
            JsonNode jsonNode = objectMapper.readTree(jsonBytes);
            if (jsonNode == null) {
                throw new AssertionError(sourceName + " 为空");
            }
            return jsonNode;
        } catch (IOException | RuntimeException exception) {
            throw new AssertionError(sourceName + " 不是合法 JSON", exception);
        }
    }

    /** 清理常见 think/Markdown 包装后解析模型正文，失败时不回显内容。 */
    private JsonNode parseJsonContentWithoutLeak(String rawContent, Target target) {
        String normalizedContent = rawContent.trim();
        if (normalizedContent.startsWith(THINK_PREFIX)) {
            int thinkEndIndex = normalizedContent.indexOf(THINK_SUFFIX);
            if (thinkEndIndex < 0) {
                throw new AssertionError("模型响应包含未闭合的 think 段：目标=" + target.displayName);
            }
            normalizedContent = normalizedContent.substring(thinkEndIndex + THINK_SUFFIX.length()).trim();
        }
        if (normalizedContent.startsWith(JSON_FENCE_PREFIX)
                && normalizedContent.endsWith(FENCE_SUFFIX)) {
            normalizedContent = normalizedContent.substring(JSON_FENCE_PREFIX.length(),
                    normalizedContent.length() - FENCE_SUFFIX.length()).trim();
        } else if (normalizedContent.startsWith(FENCE_PREFIX)
                && normalizedContent.endsWith(FENCE_SUFFIX)) {
            normalizedContent = normalizedContent.substring(FENCE_PREFIX.length(),
                    normalizedContent.length() - FENCE_SUFFIX.length()).trim();
        }
        try {
            JsonNode outputNode = objectMapper.readTree(normalizedContent);
            if (outputNode == null) {
                throw new AssertionError("模型响应 JSON 为空：目标=" + target.displayName);
            }
            return outputNode;
        } catch (IOException | RuntimeException exception) {
            throw new AssertionError("模型响应正文不是合法 JSON：目标=" + target.displayName,
                    exception);
        }
    }

    /** 按模型类型严格配对凭证、模型名称与对应的唯一业务 Schema。 */
    private List<TargetConfig> configuredTargets() {
        List<TargetConfig> targetConfigList = new ArrayList<TargetConfig>(2);
        addConfiguredTarget(targetConfigList, Target.EXTRACTION, LLM_A_API_KEY,
                LLM_A_MODEL_NAME, LLM_A_MAX_TOKENS);
        addConfiguredTarget(targetConfigList, Target.DISH_TAG, LLM_B_API_KEY,
                LLM_B_MODEL_NAME, LLM_B_MAX_TOKENS);
        if (targetConfigList.isEmpty()) {
            throw new AssertionError("请先在测试类顶部填写至少一组 API Key 和模型名称");
        }
        return targetConfigList;
    }

    /** 只把配置完整的模型加入验证列表，禁止 API Key 与模型名称错配。 */
    /** 端点未配置时不发起任何请求；与 key 留空一样表示「本次不验证」。 */
    private boolean endpointConfigured() {
        return !CHAT_COMPLETIONS_URL.isEmpty();
    }

    private void addConfiguredTarget(List<TargetConfig> targetConfigList, Target target,
                                     String apiKeyValue, String modelNameValue, int maxTokens) {
        String apiKey = apiKeyValue.trim();
        String modelName = modelNameValue.trim();
        if (apiKey.isEmpty() != modelName.isEmpty()) {
            throw new AssertionError(target.displayName + " 的 API Key 与模型名称必须同时填写或同时留空");
        }
        if (apiKey.isEmpty()) {
            return;
        }
        if (!endpointConfigured()) {
            throw new AssertionError("配置了 " + target.displayName
                    + " 的凭证但没有 EXTRACTION_BASE_URL；端点必须显式给出，不留默认远程地址");
        }
        targetConfigList.add(new TargetConfig(target, CHAT_COMPLETIONS_URL,
                modelName, apiKey, maxTokens));
    }

    /** 从仓库根目录读取 UTF-8 文本。 */
    private String readUtf8(String relativePath) throws IOException {
        return new String(Files.readAllBytes(repositoryPath(relativePath)), StandardCharsets.UTF_8);
    }

    /** 解析 Maven 执行目录下的仓库相对路径。 */
    private Path repositoryPath(String relativePath) {
        return Paths.get(System.getProperty("user.dir")).resolve(relativePath).normalize();
    }

    /** 仅打开本验证器自己的 INFO 日志，确保 Windows 控制台能看到 token 与耗时结果。 */
    private void enableVerificationInfoLogs() {
        if (log instanceof ch.qos.logback.classic.Logger) {
            ch.qos.logback.classic.Logger verificationLogger =
                    (ch.qos.logback.classic.Logger) log;
            verificationLogger.setLevel(ch.qos.logback.classic.Level.INFO);
        }
    }

    /** 被验证的业务模型与其真实契约资源。 */
    private enum Target {

        /** 体检报告结构化抽取模型 LLM-A。 */
        EXTRACTION("LLM-A", EXTRACTION_SCHEMA_PATH, EXTRACTION_PROMPT_PATH,
                "health_report_extraction"),

        /** 菜品安全打标模型 LLM-B。 */
        DISH_TAG("LLM-B", DISH_TAG_SCHEMA_PATH, DISH_TAG_PROMPT_PATH,
                "dish_tag_output");

        private final String displayName;
        private final String schemaPath;
        private final String promptPath;
        private final String responseFormatName;

        Target(String displayName, String schemaPath, String promptPath,
               String responseFormatName) {
            this.displayName = displayName;
            this.schemaPath = schemaPath;
            this.promptPath = promptPath;
            this.responseFormatName = responseFormatName;
        }
    }

    /** 网关验证场景。 */
    private enum Scenario {

        /** 使用真实提示词与安全合成输入的正常请求。 */
        NORMAL("正常请求"),

        /** 使用短提示词确认真实传输 Schema 可生成、可终止并产生合法对象。 */
        CONSTRAINT_PROBE("Schema可生成性"),

        /** 故意把 max_tokens 配到装不下输出，用于观测网关截断时的 finish_reason 取值。 */
        TRUNCATION("截断行为");

        private final String displayName;

        Scenario(String displayName) {
            this.displayName = displayName;
        }
    }

    /** 单个网关目标的运行时配置。 */
    private static final class TargetConfig {

        private final Target target;
        private final String url;
        private final String model;
        private final String apiKey;
        private final int maxTokens;

        private TargetConfig(Target target, String url, String model, String apiKey,
                             int maxTokens) {
            this.target = target;
            this.url = url;
            this.model = model;
            this.apiKey = apiKey;
            this.maxTokens = maxTokens;
        }
    }

    /** 完整版、传输版及其编译结果。 */
    private static final class SchemaBundle {

        private final JsonNode transportSchemaNode;
        private final JsonSchema canonicalJsonSchema;
        private final JsonSchema transportJsonSchema;
        private final boolean keepDescription;
        private final int transportSchemaBytes;

        private SchemaBundle(JsonNode transportSchemaNode, JsonSchema canonicalJsonSchema,
                             JsonSchema transportJsonSchema,
                             boolean keepDescription, int transportSchemaBytes) {
            this.transportSchemaNode = transportSchemaNode;
            this.canonicalJsonSchema = canonicalJsonSchema;
            this.transportJsonSchema = transportJsonSchema;
            this.keepDescription = keepDescription;
            this.transportSchemaBytes = transportSchemaBytes;
        }
    }

    /** 一次 HTTP 调用中后续校验需要的非敏感结果。 */
    private static final class HttpResult {

        private final int statusCode;
        private final byte[] responseBytes;

        private HttpResult(int statusCode, byte[] responseBytes) {
            this.statusCode = statusCode;
            this.responseBytes = responseBytes;
        }
    }

    /** Schema 投影过程的可审计计数。 */
    private static final class ProjectionStatistics {

        private int removedConditionalKeywordCount;
        private int expandedNullableReferenceCount;
    }
}
