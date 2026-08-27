package com.example.healthreport.infra;

import com.example.healthreport.parse.OcrProperties;
import com.example.healthreport.parse.OcrRequestEncoding;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 启动时校验 OCR 的连接三项与当前客户端实现得了的编码方式。
 * <p>容量与接口契约由 {@link OcrProperties#afterPropertiesSet()} 在更早的容器刷新阶段校验，
 * 本类不重复那几条判断——重复写出来的分支永远执行不到。</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OcrStartupValidator implements ApplicationRunner {

    private final OcrConnectionProperties connectionProperties;
    private final OcrProperties ocrProperties;

    public OcrStartupValidator(OcrConnectionProperties connectionProperties,
                               OcrProperties ocrProperties) {
        this.connectionProperties = connectionProperties;
        this.ocrProperties = ocrProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(connectionProperties.getBaseUrl())
                || !StringUtils.hasText(connectionProperties.getModel())
                || !StringUtils.hasText(connectionProperties.getApiKey())) {
            throw new IllegalStateException("OCR baseUrl、model、apiKey 必须配置");
        }
        if (connectionProperties.getConnectTimeoutMillis() < 1
                || connectionProperties.getReadTimeoutMillis() < 1) {
            throw new IllegalStateException("OCR 超时配置必须大于零");
        }
        if (ocrProperties.getRequestEncoding() != OcrRequestEncoding.JSON_BASE64) {
            throw new IllegalStateException(
                    "当前只实现了 JSON_BASE64 编码的 OCR 客户端，配成 MULTIPART 时不得静默按 JSON 发送");
        }
        // 有界缓冲按 int 分配，超过 int 上限的配置会被静默截断成 2GB，必须在这里拦下。
        if (ocrProperties.getMaxRequestBodyBytes().longValue() > Integer.MAX_VALUE
                || connectionProperties.getMaxResponseBodyBytes() > Integer.MAX_VALUE
                || connectionProperties.getMaxResponseBodyBytes() < 1L) {
            throw new IllegalStateException("OCR 请求体与响应体上限必须为正且不超过 int 上限");
        }
        // 启动自检是 AGENTS.md §6 要求记录的「集成检查点」。不打这条的话，
        // 「校验通过」与「这个 Runner 压根没跑」在日志里长得一模一样。
        // 【只记 baseUrl 与 model，不记 apiKey】凭证在任何级别、任何 logger 上都不记录。
        log.info("OCR 启动自检通过，baseUrl={}，model={}，编码方式={}，单图有效上限={}字节",
                connectionProperties.getBaseUrl(), connectionProperties.getModel(),
                ocrProperties.getRequestEncoding(), ocrProperties.getEffectiveOcrImageBytes());
    }
}
