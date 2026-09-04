package com.example.healthreport.infra;

import com.example.healthreport.llm.dishtag.DishTagProperties;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * OpenAI 兼容的 LLM-B 直连客户端。
 *
 * <p><b>日志策略与体检报告分析模型相反，这是全案唯一的例外。</b>
 * LLM-B 的请求里只有菜名、食材名、重量与枚举展示名，响应里只有标签，
 * <b>不含健康数据、不含用户标识</b>（§6.2.1 分界表），因此允许在 DEBUG 级记录完整正文。
 * 照抄体检报告分析链路那套「正文一个字都不进普通日志」会把本链路的排障能力一起抄掉，
 * 网关忽略关闭思考参数时，思考段仍可能与 JSON 混在一起，出问题必须能看到原文。</p>
 *
 * <p>不重试、不加拦截器、不复用全局 RestTemplate；不剥离思考段，原样返回 content。</p>
 */
@Slf4j
@Component
public class OpenAiCompatibleDishTagClient implements DishTagModelClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final DishTagConnectionProperties connectionProperties;
    private final DishTagProperties dishTagProperties;

    public OpenAiCompatibleDishTagClient(ObjectMapper objectMapper,
                                         DishTagConnectionProperties connectionProperties,
                                         DishTagProperties dishTagProperties) {
        this.objectMapper = objectMapper;
        this.connectionProperties = connectionProperties;
        this.dishTagProperties = dishTagProperties;
        this.restTemplate = buildRestTemplate(connectionProperties);
    }

    @Override
    public String call(String systemPrompt, String userMessage) {
        if (systemPrompt == null || systemPrompt.isEmpty()
                || userMessage == null || userMessage.isEmpty()) {
            throw new IllegalArgumentException("LLM-B 请求的提示词与批次正文不能为空");
        }
        byte[] bodyBytes;
        try {
            bodyBytes = buildRequestBody(systemPrompt, userMessage);
        } catch (RequestTooLargeException exception) {
            // 超限异常只含字节数，可安全记录；请求不发出。
            // 【原样上抛】：由 DishTagClient 翻译成整批作废，不能在这里变成"调用失败"
            // ——它根本没出过进程，而且重试同一批数据也不会变小。
            log.error("LLM-B 请求体超限", exception);
            throw exception;
        } catch (IOException exception) {
            log.error("LLM-B 请求体序列化失败", exception);
            throw new DishTagModelCallException(0);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(connectionProperties.getApiKey());
        String requestUrl = connectionProperties.getBaseUrl() + connectionProperties.getChatCompletionsPath();
        long startMillis = System.currentTimeMillis();
        log.info("LLM-B 调用开始，url={}，请求体字节={}", requestUrl, bodyBytes.length);
        if (log.isDebugEnabled()) {
            // LLM-B 只处理公开菜品数据，允许记录完整请求正文；Authorization Header 绝不进入日志。
            log.debug("LLM-B 请求正文：{}", new String(bodyBytes, StandardCharsets.UTF_8));
        }
        try {
            String rawResponse = restTemplate.execute(
                    requestUrl,
                    HttpMethod.POST,
                    bodyWriter(bodyBytes, headers),
                    new BoundedResponseExtractor((int) connectionProperties.getMaxResponseBodyBytes()));
            return extractContent(rawResponse, System.currentTimeMillis() - startMillis);
        } catch (HealthReportAnalysisCallException exception) {
            // StatusOnlyErrorHandler 只看状态码、绝不读错误 body，这一点与哪个模型无关，
            // 因此复用它而不是复制一份；在边界上换成 LLM-B 自己的异常类型。
            log.warn("LLM-B 调用失败，耗时={}ms，状态码={}",
                    System.currentTimeMillis() - startMillis, exception.getHttpStatus());
            throw new DishTagModelCallException(exception.getHttpStatus());
        } catch (ResponseTooLargeException exception) {
            log.error("LLM-B 响应体超限，耗时={}ms", System.currentTimeMillis() - startMillis, exception);
            throw new DishTagModelCallException(200);
        } catch (ResourceAccessException exception) {
            log.warn("LLM-B 网络调用失败，耗时={}ms", System.currentTimeMillis() - startMillis, exception);
            throw new DishTagModelCallException(0);
        } catch (RestClientException exception) {
            log.error("LLM-B HTTP 调用失败，耗时={}ms，异常类型={}",
                    System.currentTimeMillis() - startMillis, exception.getClass().getName());
            throw new DishTagModelCallException(0);
        }
        // 全案零重试：任何错误从这里直接返回上层，由离线编排整批作废。
    }

    private RestTemplate buildRestTemplate(DishTagConnectionProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMillis());
        factory.setReadTimeout(properties.getReadTimeoutMillis());
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

    /**
     * 组装两条消息的请求体。
     * <p><b>不发送 {@code response_format}</b>：网关是否支持 {@code json_object} 未确认，
     * 而 {@code DishTagContractValidator} 本来就是最终保证。确认支持后再加属于收紧，不是补漏。</p>
     * <p><b>不发送 {@code user} 字段</b>：本链路没有用户，打标按菜品维度离线跑、结果跨用户复用。</p>
     */
    byte[] buildRequestBody(String systemPrompt, String userMessage) throws IOException {
        int maxRequestBodyBytes = (int) connectionProperties.getMaxRequestBodyBytes();
        CappedByteArrayOutputStream output = new CappedByteArrayOutputStream(
                Math.min(8192, maxRequestBodyBytes), maxRequestBodyBytes);
        JsonGenerator generator = objectMapper.getFactory().createGenerator(output);
        try {
            generator.writeStartObject();
            generator.writeStringField("model", dishTagProperties.getModelVersionDishtag());
            // 同一批菜必须得到同一批标签，否则 tagHash 的复用语义自相矛盾。
            generator.writeNumberField("temperature", 0);
            // 本客户端按单个 JSON 信封有界读取；显式禁用 SSE，避免等待长连接关闭直至读超时。
            generator.writeBooleanField("stream", false);
            generator.writeNumberField("max_tokens", connectionProperties.getMaxTokens());
            // 菜品标签是有限枚举分类，不使用模型思考过程；网关忽略参数时仍由
            // ThinkSegmentStripper 兼容剥离，不能让部署能力成为解析正确性的前提。
            generator.writeObjectFieldStart("chat_template_kwargs");
            generator.writeBooleanField("enable_thinking", false);
            generator.writeEndObject();
            generator.writeArrayFieldStart("messages");
            generator.writeStartObject();
            generator.writeStringField("role", "system");
            generator.writeStringField("content", systemPrompt);
            generator.writeEndObject();
            generator.writeStartObject();
            generator.writeStringField("role", "user");
            generator.writeStringField("content", userMessage);
            generator.writeEndObject();
            generator.writeEndArray();
            generator.writeEndObject();
            generator.flush();
        } finally {
            generator.close();
        }
        return output.toByteArray();
    }

    /**
     * 取出 content 原文。
     * <p><b>先判 {@code finish_reason}</b>：不是 {@code stop} 就说明被 {@code max_tokens}
     * 截断了，即使侥幸剥得出 JSON 也不可信——截断点可能正好落在思考段里的示例 JSON 之后。</p>
     */
    String extractContent(String responseBody, long elapsedMillis) {
        if (responseBody == null) {
            log.error("LLM-B 响应为空，耗时={}ms", elapsedMillis);
            throw new DishTagModelCallException(200);
        }
        try {
            // 外层信封同样拒绝尾随内容，理由与 LLM-A 一致。
            JsonNode root = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(responseBody);
            JsonNode choice = root.path("choices").path(0);
            String finishReason = choice.path("finish_reason").asText("");
            if (!"stop".equals(finishReason)) {
                log.error("LLM-B 响应未正常结束，finishReason={}，耗时={}ms", finishReason, elapsedMillis);
                throw new DishTagModelCallException(200);
            }
            JsonNode content = choice.path("message").path("content");
            if (!content.isTextual()) {
                log.error("LLM-B 响应结构无效，耗时={}ms", elapsedMillis);
                throw new DishTagModelCallException(200);
            }
            log.info("LLM-B 调用完成，耗时={}ms，状态码=200，响应正文字符数={}",
                    elapsedMillis, content.asText().length());
            // LLM-B 正文不含健康数据，允许记录；这是全案唯一可以这样做的模型调用。
            // 注意它走的是本类自己的 logger，【不是 SensitiveLog】——本链路没有隐私可分级。
            log.debug("LLM-B 响应 content：{}", content.asText());
            return content.asText();
        } catch (IOException exception) {
            log.error("LLM-B 响应解析失败，耗时={}ms，异常类型={}",
                    elapsedMillis, exception.getClass().getName());
            throw new DishTagModelCallException(200);
        }
    }
}
