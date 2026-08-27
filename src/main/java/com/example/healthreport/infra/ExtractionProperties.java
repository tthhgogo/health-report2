package com.example.healthreport.infra;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** LLM-A OpenAI 兼容直连接入参数。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "llm.extraction")
public class ExtractionProperties {

    private String baseUrl;
    private String chatCompletionsPath = "/v1/chat/completions";
    private String model;
    private String apiKey;
    private int connectTimeoutMillis = 10000;
    private int readTimeoutMillis = 180000;
    private int maxRequestBodyBytes = 32 << 20;
    private int maxResponseBodyBytes = 4 << 20;
}
