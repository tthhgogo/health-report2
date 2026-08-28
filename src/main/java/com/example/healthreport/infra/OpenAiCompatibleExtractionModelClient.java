package com.example.healthreport.infra;

import com.example.healthreport.llm.extraction.BatchPage;
import com.example.healthreport.llm.extraction.ExtractionBatchInput;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.SensitiveLog;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;

/**
 * OpenAI 兼容的 LLM-A 直连客户端。
 * <p>请求与响应都只存在于有界内存中；本类不记录正文、不加拦截器、不做重试。</p>
 */
@Slf4j
@Component
public class OpenAiCompatibleExtractionModelClient implements ExtractionModelClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ExtractionProperties properties;

    public OpenAiCompatibleExtractionModelClient(ObjectMapper objectMapper, ExtractionProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.restTemplate = buildRestTemplate(properties);
    }

    @Override
    public String call(ExtractionBatchInput input) {
        assertPageListValid(input);
        byte[] bodyBytes;
        try {
            bodyBytes = buildRequestBody(input);
        } catch (RequestTooLargeException exception) {
            // 超限异常只含字节数，可安全记录；对上层仍统一为 LLM-A 调用失败。
            log.error("LLM-A 请求体超限，fileIndex={}，batchIndex={}",
                    input.getFileIndex(), input.getBatchIndex(), exception);
            throw new LlmCallException(FailCode.SERVER_ERROR, 0, 0L);
        } catch (IOException exception) {
            // 请求序列化异常来自本地对象，不持有模型响应，可安全保留异常堆栈。
            log.error("LLM-A 请求体序列化失败，fileIndex={}，batchIndex={}",
                    input.getFileIndex(), input.getBatchIndex(), exception);
            throw new LlmCallException(FailCode.SERVER_ERROR, 0, 0L);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(properties.getApiKey());
        long startMillis = System.currentTimeMillis();
        try {
            String rawResponse = restTemplate.execute(
                    properties.getBaseUrl() + properties.getChatCompletionsPath(),
                    HttpMethod.POST,
                    bodyWriter(bodyBytes, headers),
                    new BoundedResponseExtractor(properties.getMaxResponseBodyBytes()));
            String content = extractContent(rawResponse, input.getBatchIndex());
            log.info("LLM-A 调用完成，fileIndex={}，batchIndex={}，耗时={}ms，状态码=200，"
                            + "请求体字节={}，响应正文字符数={}",
                    input.getFileIndex(), input.getBatchIndex(),
                    System.currentTimeMillis() - startMillis, bodyBytes.length, content.length());
            // 响应正文是结构化健康结论，只走 SensitiveLog。
            // 【请求体不打】它里面是整页 JPEG 的 Base64，一条日志几 MB，打出来既读不了又会压垮日志管道；
            // 需要看发出去的文本时，看上面 ParsePlan 那侧的 OCR 识别文本即可，那是同一份内容。
            SensitiveLog.debug("LLM-A 响应正文，fileIndex={}，batchIndex={}，正文=\n{}",
                    input.getFileIndex(), input.getBatchIndex(), content);
            return content;
        } catch (LlmCallException exception) {
            log.warn("LLM-A 调用失败，batchIndex={}，耗时={}ms，状态码={}",
                    input.getBatchIndex(), System.currentTimeMillis() - startMillis,
                    exception.getHttpStatus(), exception);
            throw exception;
        } catch (ResponseTooLargeException exception) {
            // 容量异常只含上限值，不含响应正文，可安全保留异常堆栈。
            log.error("LLM-A 响应体超限，fileIndex={}，batchIndex={}，耗时={}ms，状态码=200",
                    input.getFileIndex(), input.getBatchIndex(),
                    System.currentTimeMillis() - startMillis, exception);
            throw new LlmCallException(FailCode.SERVER_ERROR, 200,
                    System.currentTimeMillis() - startMillis);
        } catch (ResourceAccessException exception) {
            // 连接与读超时异常不含响应正文，可安全保留异常堆栈用于区分网络根因。
            log.warn("LLM-A 网络调用失败，fileIndex={}，batchIndex={}，耗时={}ms，状态码=0",
                    input.getFileIndex(), input.getBatchIndex(),
                    System.currentTimeMillis() - startMillis, exception);
            throw new LlmCallException(FailCode.SERVER_ERROR, 0,
                    System.currentTimeMillis() - startMillis);
        } catch (RestClientException exception) {
            // HTTP 客户端异常可能持有响应正文，只记录类型名，绝不把异常对象或消息交给日志框架。
            log.error("LLM-A HTTP 调用失败，fileIndex={}，batchIndex={}，耗时={}ms，状态码=0，异常类型={}",
                    input.getFileIndex(), input.getBatchIndex(),
                    System.currentTimeMillis() - startMillis, exception.getClass().getName());
            throw new LlmCallException(FailCode.SERVER_ERROR, 0,
                    System.currentTimeMillis() - startMillis);
        }
        // 全案零重试：任何错误从这里直接返回上层。
    }

    private RestTemplate buildRestTemplate(ExtractionProperties modelProperties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(modelProperties.getConnectTimeoutMillis());
        factory.setReadTimeout(modelProperties.getReadTimeoutMillis());
        factory.setBufferRequestBody(true);
        RestTemplate template = new RestTemplate(factory);
        template.setInterceptors(new ArrayList<ClientHttpRequestInterceptor>(0));
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

    /** 发送前断言非空、真实页码严格递增以及必需页面图存在。 */
    void assertPageListValid(ExtractionBatchInput input) {
        if (input == null || input.getPageList() == null || input.getPageList().isEmpty()) {
            throw new IllegalStateException("批次无页面");
        }
        int previousPage = 0;
        for (BatchPage page : input.getPageList()) {
            if (page == null || page.getPage() <= previousPage) {
                throw new IllegalStateException("页码必须严格递增");
            }
            if (page.isImageRequired() && page.getJpegBytes() == null) {
                throw new IllegalStateException("必需的页面图缺失");
            }
            previousPage = page.getPage();
        }
    }

    /**
     * 在任何 Base64 分配前估算未截断的请求体上界，超过配置便直接拒绝。
     */
    long estimateBodyBytes(ExtractionBatchInput input) {
        long total = 4096L;
        total = saturatedAdd(total, saturatedMultiply(input.getSystemPrompt().length(), 3L));
        for (BatchPage page : input.getPageList()) {
            total = saturatedAdd(total, saturatedMultiply(page.getRenderedText().length(), 3L));
            if (page.getJpegBytes() != null) {
                long base64Bytes = saturatedMultiply((page.getJpegBytes().length + 2L) / 3L, 4L);
                total = saturatedAdd(total, saturatedAdd(base64Bytes, 64L));
            }
        }
        return saturatedMultiply(total, 120L) / 100L;
    }

    /** 增量写入 OpenAI 兼容请求体，底层缓冲在任何时刻都不超过配置上限。 */
    byte[] buildRequestBody(ExtractionBatchInput input) throws IOException {
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

    private void writeSystemMessage(JsonGenerator generator, ExtractionBatchInput input) throws IOException {
        generator.writeStartObject();
        generator.writeStringField("role", "system");
        generator.writeStringField("content", input.getSystemPrompt());
        generator.writeEndObject();
    }

    private void writeUserMessage(JsonGenerator generator, ExtractionBatchInput input) throws IOException {
        generator.writeStartObject();
        generator.writeStringField("role", "user");
        generator.writeArrayFieldStart("content");
        generator.writeStartObject();
        generator.writeStringField("type", "text");
        generator.writeStringField("text", "【批次信息】fileIndex=" + input.getFileIndex()
                + "  batchIndex=" + input.getBatchIndex()
                + "  batchCount=" + input.getBatchCount()
                + "  promptVersion=" + input.getPromptVersion()
                + "\n（本批全部页面来自同一个文件；前三个值请原样回填进输出）");
        generator.writeEndObject();
        for (BatchPage page : input.getPageList()) {
            generator.writeStartObject();
            generator.writeStringField("type", "text");
            generator.writeStringField("text", page.getRenderedText());
            generator.writeEndObject();
            if (page.getJpegBytes() != null) {
                generator.writeStartObject();
                generator.writeStringField("type", "image_url");
                generator.writeObjectFieldStart("image_url");
                generator.writeStringField("url", "data:image/jpeg;base64,"
                        + Base64.getEncoder().encodeToString(page.getJpegBytes()));
                generator.writeEndObject();
                generator.writeEndObject();
            }
        }
        generator.writeEndArray();
        generator.writeEndObject();
    }

    /** 从有界响应中提取 content；任何解析异常都在此处脱敏。 */
    String extractContent(String responseBody, int batchIndex) {
        if (responseBody == null) {
            log.error("LLM-A 响应结构无效，batchIndex={}，响应长度=0，异常类型=NullResponse",
                    batchIndex);
            throw new LlmCallException(FailCode.SERVER_ERROR, 200, 0L);
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (!content.isTextual()) {
                log.error("LLM-A 响应结构无效，batchIndex={}，响应长度={}，异常类型=InvalidStructure",
                        batchIndex, responseBody.length());
                throw new LlmCallException(FailCode.SERVER_ERROR, 200, 0L);
            }
            return content.asText();
        } catch (IOException exception) {
            // Jackson 异常可能包含响应片段，因此只记录类型名，绝不传异常对象或消息。
            log.error("LLM-A 响应解析失败，batchIndex={}，响应长度={}，异常类型={}",
                    batchIndex, responseBody.length(), exception.getClass().getName());
            throw new LlmCallException(FailCode.SERVER_ERROR, 200, 0L);
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
