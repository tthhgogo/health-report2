package com.example.healthreport.llm.dishtag;

import com.example.healthreport.infra.DishTagConnectionProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 启动时校验 LLM-B 的连接三项与打包后的提示词。 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DishTagStartupValidator implements ApplicationRunner {

    private final DishTagConnectionProperties connectionProperties;
    private final DishTagProperties dishTagProperties;
    private final DishTagPromptProvider promptProvider;

    public DishTagStartupValidator(DishTagConnectionProperties connectionProperties,
                                   DishTagProperties dishTagProperties,
                                   DishTagPromptProvider promptProvider) {
        this.connectionProperties = connectionProperties;
        this.dishTagProperties = dishTagProperties;
        this.promptProvider = promptProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(connectionProperties.getBaseUrl())
                || !StringUtils.hasText(connectionProperties.getApiKey())
                || !StringUtils.hasText(dishTagProperties.getModelVersionDishtag())) {
            throw new IllegalStateException("LLM-B baseUrl、apiKey、模型版本必须配置");
        }
        if (connectionProperties.getConnectTimeoutMillis() < 1
                || connectionProperties.getReadTimeoutMillis() < 1
                || connectionProperties.getMaxTokens() < 1) {
            throw new IllegalStateException("LLM-B 超时与 maxTokens 必须大于零");
        }
        // 有界缓冲按 int 分配，超过 int 上限会被静默截断成 2GB。
        if (connectionProperties.getMaxRequestBodyBytes() < 1L
                || connectionProperties.getMaxRequestBodyBytes() > Integer.MAX_VALUE
                || connectionProperties.getMaxResponseBodyBytes() < 1L
                || connectionProperties.getMaxResponseBodyBytes() > Integer.MAX_VALUE) {
            throw new IllegalStateException("LLM-B 请求体与响应体上限必须为正且不超过 int 上限");
        }
        promptProvider.getPrompt();
        // modelVersionDishtag 是 tagHash 的输入，改它等于全量重打标（§9.5.1）。
        // 把它打在启动日志里，是为了让「今晚为什么跑了三千次模型调用」这个问题
        // 在同一个日志流里就能对上——不用去翻部署配置。【不记 apiKey】。
        log.info("LLM-B 启动自检通过，baseUrl={}，模型版本={}，maxTokens={}，提示词字符数={}",
                connectionProperties.getBaseUrl(), dishTagProperties.getModelVersionDishtag(),
                connectionProperties.getMaxTokens(), promptProvider.getPrompt().length());
    }
}
