package com.example.healthreport.infra;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OCR 的连接与传输参数。
 * <p>与 {@link com.example.healthreport.parse.OcrProperties} 同属 {@code ocr.*} 前缀，
 * 但那一份是容量与接口契约的接入答复，这一份是部署地址与超时——两类值的变更理由不同，
 * 分开绑定可以让容量契约的启动自检保持与部署环境无关。</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ocr")
public class OcrConnectionProperties {

    /** OCR 网关根地址，必填，形如 http://host/public。 */
    private String baseUrl;

    /** OpenAI 兼容的对话补全路径；网关把 OCR 也放在这个协议下，因此与 LLM-A 同路径。 */
    private String chatCompletionsPath = "/v1/chat/completions";

    /** OCR 模型标识，必填，例如 PaddleOCR-VL-0.9B。 */
    private String model;

    /** 访问凭证，必填，只允许由环境变量注入，代码库不保存真实值。 */
    private String apiKey;

    /** 响应体有界读取上限；单页 OCR 文本远小于它，超出即视为服务端异常。 */
    private long maxResponseBodyBytes = 4L << 20;

    /** 建连超时毫秒。 */
    private int connectTimeoutMillis = 10000;

    /** 读超时毫秒，必须小于任务 deadline，否则任务先被判超时而请求还挂着。 */
    private int readTimeoutMillis = 120000;
}
