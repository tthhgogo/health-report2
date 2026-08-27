package com.example.healthreport.infra;

import com.example.healthreport.parse.ImageTooLargeException;
import com.example.healthreport.parse.OcrProperties;
import com.example.healthreport.parse.ocr.OcrContentSplitter;
import com.example.healthreport.parse.ocr.OcrResult;
import com.example.healthreport.support.FailCode;
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
 * PaddleOCR-VL 直连客户端，走网关的 OpenAI 兼容对话补全协议。
 *
 * <p>协议只有一个 {@code content} 字符串可以承载结果，<b>没有任何坐标字段</b>，
 * 因此本客户端产出的每个识别块 bbox 恒为 {@code null}，图像宽高也不回传。</p>
 *
 * <p><b>安全边界</b>：请求体是报告页面图，响应体是报告全文。本类绝不把请求体或响应体
 * 写进日志，向上只抛不含正文的 {@link OcrCallException} 与 {@link ImageTooLargeException}。
 * 不重试、不加拦截器、不复用全局 RestTemplate。</p>
 */
@Slf4j
@Component
public class PaddleOcrVlClient implements PaddleOcrClient {

    /** 固定指令：只要求转录图片文字，不做任何理解或改写。 */
    static final String TRANSCRIBE_INSTRUCTION = "输出图片的文字";

    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final OcrConnectionProperties connectionProperties;
    private final OcrProperties ocrProperties;

    public PaddleOcrVlClient(ObjectMapper objectMapper, OcrConnectionProperties connectionProperties,
                             OcrProperties ocrProperties) {
        this.objectMapper = objectMapper;
        this.connectionProperties = connectionProperties;
        this.ocrProperties = ocrProperties;
        this.restTemplate = buildRestTemplate(connectionProperties);
    }

    @Override
    public OcrResult recognize(byte[] encodedImageBytes) {
        if (encodedImageBytes == null || encodedImageBytes.length == 0) {
            throw new IllegalArgumentException("OCR 输入图片字节不能为空");
        }
        // 入口已按 effectiveOcrImageBytes 提前拒过一次，这里是客户端兜底：
        // 估算的协议开销可能偏小，两层都要有。
        long effectiveOcrImageBytes = ocrProperties.getEffectiveOcrImageBytes();
        if (encodedImageBytes.length > effectiveOcrImageBytes) {
            throw new ImageTooLargeException(encodedImageBytes.length, effectiveOcrImageBytes);
        }

        byte[] bodyBytes;
        try {
            bodyBytes = buildRequestBody(encodedImageBytes);
        } catch (RequestTooLargeException exception) {
            // 超限异常只含字节数，可安全记录；请求不发出。
            log.error("OCR 请求体超限，图片字节={}", encodedImageBytes.length, exception);
            throw new ImageTooLargeException(exception.getAttemptedBytes(),
                    ocrProperties.getMaxRequestBodyBytes().longValue());
        } catch (IOException exception) {
            // 序列化异常来自本地对象，不持有模型响应，可安全保留异常堆栈。
            log.error("OCR 请求体序列化失败，图片字节={}", encodedImageBytes.length, exception);
            throw new OcrCallException(FailCode.SERVER_ERROR, 0, 0L);
        }
        if (bodyBytes.length > ocrProperties.getMaxRequestBodyBytes().longValue()) {
            throw new ImageTooLargeException(bodyBytes.length,
                    ocrProperties.getMaxRequestBodyBytes().longValue());
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(connectionProperties.getApiKey());
        long startMillis = System.currentTimeMillis();
        log.info("OCR 调用开始，请求图片字节={}，请求体字节={}",
                encodedImageBytes.length, bodyBytes.length);
        try {
            String rawResponse = restTemplate.execute(
                    connectionProperties.getBaseUrl() + connectionProperties.getChatCompletionsPath(),
                    HttpMethod.POST,
                    bodyWriter(bodyBytes, headers),
                    new BoundedResponseExtractor(boundedResponseBytes()));
            String recognizedText = extractContent(rawResponse);
            log.info("OCR 调用完成，耗时={}ms，状态码=200，请求图片字节={}，响应正文字符数={}",
                    System.currentTimeMillis() - startMillis, encodedImageBytes.length,
                    recognizedText.length());
            return new OcrResult(OcrContentSplitter.split(recognizedText), null, null);
        } catch (LlmCallException exception) {
            // StatusOnlyErrorHandler 只看状态码、绝不读错误 body，这一点与哪个模型无关，
            // 因此复用它而不是复制一份；在边界上换成 OCR 自己的异常类型。
            log.warn("OCR 调用失败，耗时={}ms，状态码={}",
                    System.currentTimeMillis() - startMillis, exception.getHttpStatus());
            throw new OcrCallException(exception.getFailCode(), exception.getHttpStatus(),
                    System.currentTimeMillis() - startMillis);
        } catch (ResponseTooLargeException exception) {
            // 容量异常只含上限值，不含响应正文，可安全保留异常堆栈。
            log.error("OCR 响应体超限，耗时={}ms，状态码=200",
                    System.currentTimeMillis() - startMillis, exception);
            throw new OcrCallException(FailCode.SERVER_ERROR, 200,
                    System.currentTimeMillis() - startMillis);
        } catch (ResourceAccessException exception) {
            // 连接与读超时异常不含响应正文，可安全保留异常堆栈用于区分网络根因。
            log.warn("OCR 网络调用失败，耗时={}ms，状态码=0",
                    System.currentTimeMillis() - startMillis, exception);
            throw new OcrCallException(FailCode.SERVER_ERROR, 0,
                    System.currentTimeMillis() - startMillis);
        } catch (RestClientException exception) {
            // HTTP 客户端异常可能持有响应正文，只记录类型名，绝不把异常对象或消息交给日志框架。
            log.error("OCR HTTP 调用失败，耗时={}ms，状态码=0，异常类型={}",
                    System.currentTimeMillis() - startMillis, exception.getClass().getName());
            throw new OcrCallException(FailCode.SERVER_ERROR, 0,
                    System.currentTimeMillis() - startMillis);
        }
        // 全案零重试：任何错误从这里直接返回上层。
    }

    private RestTemplate buildRestTemplate(OcrConnectionProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMillis());
        factory.setReadTimeout(properties.getReadTimeoutMillis());
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

    /**
     * 增量序列化到有界缓冲；底层数组在任何时刻都不超过配置的请求体上限。
     * <p>图片以 data URI 内联，<b>不上传对象存储、不生成可被 URL 取回的副本</b>——
     * 这与 LLM-A 不走 Dify 是同一条理由：报告图一旦被外部系统按 URL 留存就删不掉。</p>
     */
    byte[] buildRequestBody(byte[] encodedImageBytes) throws IOException {
        // OcrStartupValidator 已保证请求体上限落在 int 范围内，这里可以直接窄化。
        int maxRequestBodyBytes = ocrProperties.getMaxRequestBodyBytes().intValue();
        CappedByteArrayOutputStream output = new CappedByteArrayOutputStream(
                Math.min(1 << 16, maxRequestBodyBytes), maxRequestBodyBytes);
        JsonGenerator generator = objectMapper.getFactory().createGenerator(output);
        try {
            generator.writeStartObject();
            generator.writeStringField("model", connectionProperties.getModel());
            // 同一张图必须得到同一份文本，识别结果不接受采样波动。
            generator.writeNumberField("temperature", 0);
            generator.writeArrayFieldStart("messages");
            generator.writeStartObject();
            generator.writeStringField("role", "user");
            generator.writeArrayFieldStart("content");
            generator.writeStartObject();
            generator.writeStringField("type", "image_url");
            generator.writeObjectFieldStart("image_url");
            generator.writeStringField("url", "data:" + sniffMediaType(encodedImageBytes)
                    + ";base64," + Base64.getEncoder().encodeToString(encodedImageBytes));
            generator.writeEndObject();
            generator.writeEndObject();
            generator.writeStartObject();
            generator.writeStringField("type", "text");
            generator.writeStringField("text", TRANSCRIBE_INSTRUCTION);
            generator.writeEndObject();
            generator.writeEndArray();
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
     * 按文件头判定 data URI 的媒体类型。
     * <p>只认 §5.1 允许的两种编码格式，认不出就抛——猜一个类型会让服务端拿到声明与内容不符的图。</p>
     */
    static String sniffMediaType(byte[] encodedImageBytes) {
        if (startsWith(encodedImageBytes, PNG_SIGNATURE)) {
            return "image/png";
        }
        if (startsWith(encodedImageBytes, JPEG_SIGNATURE)) {
            return "image/jpeg";
        }
        throw new IllegalArgumentException("OCR 输入不是 PNG 或 JPEG 编码字节");
    }

    private static boolean startsWith(byte[] source, byte[] signature) {
        if (source.length < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if (source[index] != signature[index]) {
                return false;
            }
        }
        return true;
    }

    /** 从有界响应中提取 content；任何解析异常都在此处脱敏。 */
    String extractContent(String responseBody) {
        if (responseBody == null) {
            log.error("OCR 响应结构无效，响应长度=0，异常类型=NullResponse");
            throw new OcrCallException(FailCode.SERVER_ERROR, 200, 0L);
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (!content.isTextual()) {
                log.error("OCR 响应结构无效，响应长度={}，异常类型=InvalidStructure",
                        responseBody.length());
                throw new OcrCallException(FailCode.SERVER_ERROR, 200, 0L);
            }
            return content.asText();
        } catch (IOException exception) {
            // Jackson 异常可能包含响应片段，因此只记录类型名，绝不传异常对象或消息。
            log.error("OCR 响应解析失败，响应长度={}，异常类型={}",
                    responseBody.length(), exception.getClass().getName());
            throw new OcrCallException(FailCode.SERVER_ERROR, 200, 0L);
        }
    }

    /** 响应上限已由启动自检约束在 int 范围内。 */
    private int boundedResponseBytes() {
        return (int) connectionProperties.getMaxResponseBodyBytes();
    }
}
