package com.example.healthreport.infra;

import com.example.healthreport.llm.extraction.ExtractionCallInput;
import com.example.healthreport.render.PageImage;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.SensitiveLog;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;

/**
 * OpenAI 兼容的体检报告分析模型直连客户端。
 * <p>请求与响应都只存在于有界内存中；本类不记录正文、不加拦截器、不做重试。
 * 流式与非流式共用同一套 content / reasoning_content 双通道裁决。</p>
 */
@Slf4j
@Component
public class OpenAiCompatibleHealthReportAnalysisModelClient implements HealthReportAnalysisModelClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final HealthReportAnalysisModelProperties properties;

    public OpenAiCompatibleHealthReportAnalysisModelClient(ObjectMapper objectMapper, HealthReportAnalysisModelProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.restTemplate = buildRestTemplate(properties);
    }

    @Override
    public String call(ExtractionCallInput input) {
        assertPageListValid(input);
        byte[] bodyBytes;
        try {
            bodyBytes = buildRequestBody(input);
        } catch (RequestTooLargeException exception) {
            // 超限异常只含字节数，可安全记录；对上层仍统一为调用失败。
            log.error("模型请求体超限，call={}", input.getCall(), exception);
            throw new HealthReportAnalysisCallException(FailCode.SERVER_ERROR, 0);
        } catch (IOException exception) {
            // 请求序列化异常来自本地对象，不持有模型响应，可安全保留异常堆栈。
            log.error("模型请求体序列化失败，call={}", input.getCall(), exception);
            throw new HealthReportAnalysisCallException(FailCode.SERVER_ERROR, 0);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(properties.isStreamEnabled()
                ? MediaType.TEXT_EVENT_STREAM : MediaType.APPLICATION_JSON));
        headers.setBearerAuth(properties.getApiKey());
        long startMillis = System.currentTimeMillis();
        try {
            String content;
            if (properties.isStreamEnabled()) {
                content = restTemplate.execute(
                        properties.getBaseUrl() + properties.getChatCompletionsPath(),
                        HttpMethod.POST,
                        bodyWriter(bodyBytes, headers),
                        streamingExtractor(input));
            } else {
                String rawResponse = restTemplate.execute(
                        properties.getBaseUrl() + properties.getChatCompletionsPath(),
                        HttpMethod.POST,
                        bodyWriter(bodyBytes, headers),
                        new BoundedResponseExtractor(properties.getMaxResponseBodyBytes()));
                content = extractContent(rawResponse, input.getCall().name());
            }
            log.info("模型调用完成，call={}，耗时={}ms，状态码=200，请求体字节={}，响应正文字符数={}",
                    input.getCall(), System.currentTimeMillis() - startMillis,
                    bodyBytes.length, content.length());
            // 响应正文是结构化健康结论，只走 SensitiveLog；请求体含整页 JPEG Base64，任何 logger 都不打。
            SensitiveLog.debug("模型响应正文，call={}，正文=\n{}", input.getCall(), content);
            return content;
        } catch (HealthReportAnalysisCallException exception) {
            log.warn("模型调用失败，call={}，耗时={}ms，状态码={}",
                    input.getCall(), System.currentTimeMillis() - startMillis,
                    exception.getHttpStatus(), exception);
            throw exception;
        } catch (ResponseTooLargeException exception) {
            // 容量异常只含上限值，不含响应正文，可安全保留异常堆栈。
            log.error("模型响应体超限，call={}，耗时={}ms，状态码=200",
                    input.getCall(), System.currentTimeMillis() - startMillis, exception);
            throw new HealthReportAnalysisCallException(FailCode.SERVER_ERROR, 200);
        } catch (ResourceAccessException exception) {
            // 连接与读超时异常不含响应正文，可安全保留异常堆栈用于区分网络根因。
            log.warn("模型网络调用失败，call={}，耗时={}ms，状态码=0",
                    input.getCall(), System.currentTimeMillis() - startMillis, exception);
            throw new HealthReportAnalysisCallException(FailCode.SERVER_ERROR, 0);
        } catch (RestClientException exception) {
            // HTTP 客户端异常可能持有响应正文，只记录类型名，绝不把异常对象或消息交给日志框架。
            log.error("模型 HTTP 调用失败，call={}，耗时={}ms，状态码=0，异常类型={}",
                    input.getCall(), System.currentTimeMillis() - startMillis,
                    exception.getClass().getName());
            throw new HealthReportAnalysisCallException(FailCode.SERVER_ERROR, 0);
        }
        // 全案零重试：任何错误从这里直接返回上层。
    }

    private RestTemplate buildRestTemplate(HealthReportAnalysisModelProperties modelProperties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(modelProperties.getConnectTimeoutMillis());
        factory.setReadTimeout(modelProperties.getReadTimeoutMillis());
        factory.setBufferRequestBody(true);
        RestTemplate template = new RestTemplate(factory);
        template.setErrorHandler(new StatusOnlyErrorHandler());
        return template;
    }

    private RequestCallback bodyWriter(final byte[] bodyBytes, final HttpHeaders headers) {
        return new RequestCallback() {
            @Override
            public void doWithRequest(ClientHttpRequest request) throws IOException {
                request.getHeaders().putAll(headers);
                request.getHeaders().setContentLength(bodyBytes.length);
                StreamUtils.copy(bodyBytes, request.getBody());
            }
        };
    }

    /** 发送前断言输入完整、全局页码从 1 起严格连续递增。 */
    void assertPageListValid(ExtractionCallInput input) {
        if (input == null || input.getPageList() == null || input.getPageList().isEmpty()) {
            throw new IllegalStateException("调用无页面");
        }
        int expectedPage = 1;
        for (PageImage page : input.getPageList()) {
            if (page == null || page.getPage() != expectedPage) {
                throw new IllegalStateException("页码必须从 1 起严格连续递增");
            }
            if (page.getJpegBytes() == null || page.getJpegBytes().length == 0) {
                throw new IllegalStateException("页面图缺失");
            }
            expectedPage++;
        }
    }

    /**
     * 在任何 Base64 分配前估算未截断的请求体上界，超过配置便直接拒绝。
     */
    long estimateBodyBytes(ExtractionCallInput input) {
        long total = 4096L;
        total = saturatedAdd(total, saturatedMultiply(input.getSystemPrompt().length(), 3L));
        total = saturatedAdd(total, saturatedMultiply(input.getUserText().length(), 3L));
        for (PageImage page : input.getPageList()) {
            long base64Bytes = saturatedMultiply((page.getJpegBytes().length + 2L) / 3L, 4L);
            total = saturatedAdd(total, saturatedAdd(base64Bytes, 96L));
        }
        return saturatedMultiply(total, 120L) / 100L;
    }

    /** 增量写入 OpenAI 兼容请求体，底层缓冲在任何时刻都不超过配置上限。 */
    byte[] buildRequestBody(ExtractionCallInput input) throws IOException {
        long estimatedBytes = estimateBodyBytes(input);
        if (estimatedBytes > properties.getMaxRequestBodyBytes()) {
            throw new RequestTooLargeException(estimatedBytes, properties.getMaxRequestBodyBytes());
        }
        int initialCapacity = (int) Math.min(estimatedBytes, properties.getMaxRequestBodyBytes());
        CappedByteArrayOutputStream output = new CappedByteArrayOutputStream(
                initialCapacity, properties.getMaxRequestBodyBytes());
        JsonGenerator generator = objectMapper.getFactory().createGenerator(output);
        try {
            generator.writeStartObject();
            generator.writeStringField("model", properties.getModel());
            generator.writeNumberField("temperature", 0);
            generator.writeBooleanField("stream", properties.isStreamEnabled());
            // Qwen3 模板参数：网关透传后关闭深度思考，省下思考段的 token 与耗时。
            generator.writeObjectFieldStart("chat_template_kwargs");
            generator.writeBooleanField("enable_thinking", properties.isEnableThinking());
            generator.writeEndObject();
            generator.writeArrayFieldStart("messages");
            writeSystemMessage(generator, input);
            writeUserMessage(generator, input);
            generator.writeEndArray();
            generator.writeObjectFieldStart("response_format");
            generator.writeStringField("type", "json_object");
            generator.writeEndObject();
            generator.writeEndObject();
            generator.flush();
        } finally {
            generator.close();
        }
        return output.toByteArray();
    }

    private void writeSystemMessage(JsonGenerator generator, ExtractionCallInput input) throws IOException {
        generator.writeStartObject();
        generator.writeStringField("role", "system");
        generator.writeStringField("content", input.getSystemPrompt());
        generator.writeEndObject();
    }

    /** User 消息：任务说明 + 逐页「页码文本 → 图」成对（R60），不含任何标识或前序结果。 */
    private void writeUserMessage(JsonGenerator generator, ExtractionCallInput input) throws IOException {
        generator.writeStartObject();
        generator.writeStringField("role", "user");
        generator.writeArrayFieldStart("content");
        generator.writeStartObject();
        generator.writeStringField("type", "text");
        generator.writeStringField("text", input.getUserText());
        generator.writeEndObject();
        for (PageImage page : input.getPageList()) {
            generator.writeStartObject();
            generator.writeStringField("type", "text");
            generator.writeStringField("text", "第 " + page.getPage() + " 页：");
            generator.writeEndObject();
            generator.writeStartObject();
            generator.writeStringField("type", "image_url");
            generator.writeObjectFieldStart("image_url");
            generator.writeStringField("url", "data:image/jpeg;base64,"
                    + Base64.getEncoder().encodeToString(page.getJpegBytes()));
            generator.writeEndObject();
            generator.writeEndObject();
        }
        generator.writeEndArray();
        generator.writeEndObject();
    }

    /**
     * SSE 流式读取：逐行消费 {@code data:} 块，累计 content / reasoning_content 增量，
     * 累计量超上限立即中断；结束后与非流式共用同一套通道裁决。
     */
    private ResponseExtractor<String> streamingExtractor(final ExtractionCallInput input) {
        return new ResponseExtractor<String>() {
            @Override
            public String extractData(ClientHttpResponse response) throws IOException {
                StringBuilder contentBuilder = new StringBuilder();
                StringBuilder reasoningBuilder = new StringBuilder();
                String finishReason = "";
                long consumedBytes = 0L;
                // 【按 UTF-8 字节而不是字符计数，且先计数后缓冲】：BufferedReader.readLine 会
                // 先把整行完整分配再检查，中文一个字符占多个字节也会被漏算成 1——
                // 超长响应可以是配置上限的数倍仍被接受。逐字节推进时缓冲永远不超过上限。
                java.io.BufferedInputStream bodyStream =
                        new java.io.BufferedInputStream(response.getBody());
                java.io.ByteArrayOutputStream lineBuffer = new java.io.ByteArrayOutputStream(256);
                boolean[] sawDataLine = new boolean[1];
                int nextByte;
                while ((nextByte = bodyStream.read()) >= 0) {
                    consumedBytes++;
                    if (consumedBytes > properties.getMaxResponseBodyBytes()) {
                        throw new ResponseTooLargeException(properties.getMaxResponseBodyBytes());
                    }
                    if (nextByte != '\n') {
                        lineBuffer.write(nextByte);
                        continue;
                    }
                    finishReason = consumeSseLine(lineBuffer, contentBuilder, reasoningBuilder,
                            finishReason, input, sawDataLine);
                }
                if (lineBuffer.size() > 0) {
                    finishReason = consumeSseLine(lineBuffer, contentBuilder, reasoningBuilder,
                            finishReason, input, sawDataLine);
                }
                if (!sawDataLine[0] && consumedBytes > 0L) {
                    // 网关忽略 stream:true 回普通 JSON 时，这里一条 data 行都不会有——
                    // 后续裁决必然以 finishReason=missing 失败，先把根因指出来，别让排障去猜。
                    log.error("SSE 响应中没有任何 data 行，疑似网关未启用流式；"
                            + "请核对 llm.extraction.stream-enabled 配置，call={}，响应字节={}",
                            input.getCall(), consumedBytes);
                }
                return adjudicate(contentBuilder.toString(), reasoningBuilder.toString(),
                        finishReason, input.getCall().name(), (int) consumedBytes);
            }
        };
    }

    /** 处理一行 SSE 数据；返回累计到的 finish_reason，并清空行缓冲。 */
    private String consumeSseLine(java.io.ByteArrayOutputStream lineBuffer,
                                  StringBuilder contentBuilder, StringBuilder reasoningBuilder,
                                  String finishReason, ExtractionCallInput input,
                                  boolean[] sawDataLine) {
        String line = new String(lineBuffer.toByteArray(), StandardCharsets.UTF_8);
        lineBuffer.reset();
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        if (!line.startsWith("data:")) {
            return finishReason;
        }
        sawDataLine[0] = true;
        String payload = line.substring(5).trim();
        if (payload.isEmpty() || "[DONE]".equals(payload)) {
            return finishReason;
        }
        JsonNode chunkNode;
        try {
            chunkNode = objectMapper.readTree(payload);
        } catch (IOException exception) {
            // 单个 chunk 畸形即整次失败；异常消息可能含正文片段，只记类型。
            log.error("SSE 数据块解析失败，call={}，异常类型={}",
                    input.getCall(), exception.getClass().getName());
            throw new HealthReportAnalysisCallException(FailCode.SERVER_ERROR, 200);
        }
        JsonNode choiceNode = chunkNode.path("choices").path(0);
        JsonNode deltaNode = choiceNode.path("delta");
        if (deltaNode.path("content").isTextual()) {
            contentBuilder.append(deltaNode.path("content").asText());
        }
        if (deltaNode.path("reasoning_content").isTextual()) {
            reasoningBuilder.append(deltaNode.path("reasoning_content").asText());
        }
        if (choiceNode.path("finish_reason").isTextual()) {
            return choiceNode.path("finish_reason").asText();
        }
        return finishReason;
    }

    /**
     * 从有界响应的 content / reasoning_content 中选择唯一合法 JSON 对象；任何解析异常都脱敏。
     */
    String extractContent(String responseBody, String callName) {
        if (responseBody == null) {
            log.error("模型响应结构无效，call={}，响应长度=0，异常类型=NullResponse", callName);
            throw new HealthReportAnalysisCallException(FailCode.SERVER_ERROR, 200);
        }
        try {
            // 外层信封同样拒绝尾随内容：「合法响应 + 第二段 JSON」不能被静默接受。
            JsonNode root = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(responseBody);
            JsonNode choiceNode = root.path("choices").path(0);
            String finishReason = choiceNode.path("finish_reason").asText("");
            JsonNode messageNode = choiceNode.path("message");
            String content = messageNode.path("content").isTextual()
                    ? messageNode.path("content").asText() : null;
            String reasoning = messageNode.path("reasoning_content").isTextual()
                    ? messageNode.path("reasoning_content").asText() : null;
            return adjudicate(content, reasoning, finishReason, callName, responseBody.length());
        } catch (IOException exception) {
            // Jackson 异常可能包含响应片段，因此只记录类型名，绝不传异常对象或消息。
            log.error("模型响应解析失败，call={}，响应长度={}，异常类型={}",
                    callName, responseBody.length(), exception.getClass().getName());
            throw new HealthReportAnalysisCallException(FailCode.SERVER_ERROR, 200);
        }
    }

    /**
     * 流式与非流式共用的最终裁决：先判 finish_reason，再做双通道唯一合法 JSON 选择。
     * <p>网关会把结构化结果放进 reasoning_content，标准实现放 content。不按字段名盲选：
     * 只有一个通道是合法 JSON 对象时采用它；两者都合法但内容不同时 fail-safe 拒绝。</p>
     */
    String adjudicate(String content, String reasoning, String finishReason,
                      String callName, int responseLength) {
        // 【先判 finish_reason】不是 stop 就是被截断，半截 JSON 后面全不可信。
        if (!"stop".equals(finishReason)) {
            // 【只记白名单分类，不记原值】finish_reason 是外部响应字段。
            log.error("模型响应未正常结束，call={}，响应长度={}，finishReasonKind={}",
                    callName, responseLength, classifyFinishReason(finishReason));
            throw new HealthReportAnalysisCallException(FailCode.SERVER_ERROR, 200);
        }
        JsonNode contentObjectNode = parseJsonObjectOrNull(content);
        JsonNode reasoningObjectNode = parseJsonObjectOrNull(reasoning);
        if (contentObjectNode != null && reasoningObjectNode != null) {
            if (!contentObjectNode.equals(reasoningObjectNode)) {
                log.error("模型响应结构无效，call={}，响应长度={}，异常类型=AmbiguousJsonChannels",
                        callName, responseLength);
                throw new HealthReportAnalysisCallException(FailCode.SERVER_ERROR, 200);
            }
            return content;
        }
        if (contentObjectNode != null) {
            return content;
        }
        if (reasoningObjectNode != null) {
            return reasoning;
        }
        log.error("模型响应结构无效，call={}，响应长度={}，异常类型=NoJsonObjectChannel",
                callName, responseLength);
        throw new HealthReportAnalysisCallException(FailCode.SERVER_ERROR, 200);
    }

    /**
     * 把 {@code finish_reason} 收窄成可枚举的分类。
     * <p>它是外部字段，原值不可信也不可记；日志只需要知道「哪一类截断」。</p>
     */
    static String classifyFinishReason(String finishReason) {
        if (finishReason == null || finishReason.isEmpty()) {
            return "missing";
        }
        if ("length".equals(finishReason) || "content_filter".equals(finishReason)
                || "tool_calls".equals(finishReason) || "stop".equals(finishReason)) {
            return finishReason;
        }
        return "other";
    }

    /** 文本完整解析为 JSON 对象时返回对象，否则返回 null；不记录可能含健康数据的解析异常。 */
    private JsonNode parseJsonObjectOrNull(String candidate) {
        if (candidate == null || candidate.trim().isEmpty()) {
            return null;
        }
        try {
            // 【必须拒绝尾随内容】readTree("{...} 一些解释文字") 会返回对象并静默丢掉后面的字，
            // 那正是「模型在 JSON 之后又说了几句」的形态，不能当成合法输出。
            JsonNode parsedNode = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(candidate);
            return parsedNode != null && parsedNode.isObject() ? parsedNode : null;
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private long saturatedAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private long saturatedMultiply(long value, long multiplier) {
        if (value > Long.MAX_VALUE / multiplier) {
            return Long.MAX_VALUE;
        }
        return value * multiplier;
    }
}
