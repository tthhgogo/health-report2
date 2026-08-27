package com.example.healthreport.llm.extraction;

import com.example.healthreport.infra.ExtractionProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 启动时校验 LLM-A 凭证、模型标识、容量参数及打包后的提示词。 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ExtractionStartupValidator implements ApplicationRunner {

    private final ExtractionProperties properties;
    private final ExtractionPromptProvider promptProvider;

    public ExtractionStartupValidator(ExtractionProperties properties, ExtractionPromptProvider promptProvider) {
        this.properties = properties;
        this.promptProvider = promptProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(properties.getBaseUrl())
                || !StringUtils.hasText(properties.getModel())
                || !StringUtils.hasText(properties.getApiKey())) {
            throw new IllegalStateException("LLM-A baseUrl、model、apiKey 必须配置");
        }
        if (properties.getConnectTimeoutMillis() < 1 || properties.getReadTimeoutMillis() < 1
                || properties.getMaxRequestBodyBytes() < 1
                || properties.getMaxResponseBodyBytes() < 1) {
            throw new IllegalStateException("LLM-A 超时与容量配置必须大于零");
        }
        promptProvider.getPrompt();
        // 提示词长度进日志：提示词版本进不了 tagHash 这类校验，改坏了（比如打包漏了资源、
        // 被截断）只有长度这一个廉价信号能看出来。【不记 apiKey】。
        log.info("LLM-A 启动自检通过，baseUrl={}，model={}，提示词字符数={}",
                properties.getBaseUrl(), properties.getModel(), promptProvider.getPrompt().length());
    }
}
