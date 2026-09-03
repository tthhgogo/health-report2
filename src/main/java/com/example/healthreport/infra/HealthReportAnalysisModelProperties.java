package com.example.healthreport.infra;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 体检报告分析模型 OpenAI 兼容直连接入参数。
 * <p>请求体上限默认 64MiB：30 页 × 单页压缩档 1MiB × Base64 膨胀率 4/3 ≈ 40MiB，
 * 再留提示词与信封余量；配小会让满页任务在发送前被拒（R66j 是显式失败，但不该是常态）。</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "llm.extraction")
public class HealthReportAnalysisModelProperties {

    private String baseUrl;
    private String chatCompletionsPath = "/v1/chat/completions";
    private String model;
    private String apiKey;
    private int connectTimeoutMillis = 10000;
    private int readTimeoutMillis = 180000;
    private int maxRequestBodyBytes = 64 << 20;
    private int maxResponseBodyBytes = 4 << 20;

    /** SSE 流式默认开启：非流式长响应会撞网关空闲超时（设计方案 §11-7）。 */
    private boolean streamEnabled = true;

    /** 通过 chat_template_kwargs 关闭模型深度思考；网关透传 Qwen3 模板参数。 */
    private boolean enableThinking = false;
}
