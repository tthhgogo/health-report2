package com.example.healthreport.llm.extraction;

import com.example.healthreport.infra.HealthReportAnalysisModelProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 启动时校验体检报告分析模型凭证、容量参数及三份打包提示词。 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ExtractionStartupValidator implements ApplicationRunner {

    /**
     * 30 页满载的请求体下限，必须与客户端 {@code estimateBodyBytes} 的口径一致：
     * 单页压缩档 1MiB × 30 页 × Base64 膨胀率 4/3，加提示词与信封余量 512KiB，
     * <b>再整体乘发送前预估用的 120% 安全系数</b>——预估比下限宽时，
     * 40~50MiB 区间的配置能过启动检查却会在首个满载任务被 RequestTooLarge 拒绝。
     */
    private static final long MIN_REQUEST_BODY_BYTES =
            (30L * 1024L * 1024L * 4L / 3L + 512L * 1024L) * 120L / 100L;

    private final HealthReportAnalysisModelProperties properties;
    private final ExtractionPromptProvider promptProvider;

    public ExtractionStartupValidator(HealthReportAnalysisModelProperties properties, ExtractionPromptProvider promptProvider) {
        this.properties = properties;
        this.promptProvider = promptProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(properties.getBaseUrl())
                || !StringUtils.hasText(properties.getModel())
                || !StringUtils.hasText(properties.getApiKey())) {
            throw new IllegalStateException("体检报告分析模型 baseUrl、model、apiKey 必须配置");
        }
        if (properties.getConnectTimeoutMillis() < 1 || properties.getReadTimeoutMillis() < 1
                || properties.getMaxResponseBodyBytes() < 1) {
            throw new IllegalStateException("体检报告分析模型超时与容量配置必须大于零");
        }
        // R66k：请求体上限必须容得下 30 页满载任务，配小了不能拖到第一次调用才报错。
        if (properties.getMaxRequestBodyBytes() < MIN_REQUEST_BODY_BYTES) {
            throw new IllegalStateException("llm.extraction.max-request-body-bytes 小于 30 页满载所需下限："
                    + MIN_REQUEST_BODY_BYTES);
        }
        for (ExtractionCall call : ExtractionCall.values()) {
            String prompt = promptProvider.getPrompt(call);
            // 版本号必须写在提示词头部；对不上说明资源与常量漂移（R55a 的运行时兜底）。
            if (!prompt.contains(call.getPromptVersion())) {
                throw new IllegalStateException("提示词头部版本与常量不一致：" + call.name());
            }
            log.info("提示词自检通过，call={}，字符数={}", call, prompt.length());
        }
        log.info("体检报告分析模型启动自检通过，baseUrl={}，model={}，流式={}",
                properties.getBaseUrl(), properties.getModel(), properties.isStreamEnabled());
    }
}
