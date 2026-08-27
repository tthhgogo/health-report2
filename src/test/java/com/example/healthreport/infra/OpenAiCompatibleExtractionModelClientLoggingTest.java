package com.example.healthreport.infra;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.example.healthreport.llm.extraction.BatchPage;
import com.example.healthreport.llm.extraction.ExtractionBatchInput;
import com.example.healthreport.support.FailCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R63：持有响应正文的 HTTP 异常必须脱敏包装，且不得作为日志 throwable 记录。 */
class OpenAiCompatibleExtractionModelClientLoggingTest {

    @Test
    void responseBearingRestClientExceptionMustBeWrappedWithoutLoggingBody() throws Exception {
        String sensitiveMarker = "SENSITIVE_ERROR_BODY_MARKER";
        Logger applicationLogger = (Logger) LoggerFactory.getLogger("com.example.healthreport");
        Level previousLevel = applicationLogger.getLevel();
        ListAppender<ILoggingEvent> logAppender = new ListAppender<ILoggingEvent>();
        logAppender.start();
        applicationLogger.addAppender(logAppender);
        applicationLogger.setLevel(Level.ALL);
        try {
            OpenAiCompatibleExtractionModelClient client = client();
            RestTemplate restTemplate = extractRestTemplate(client);
            restTemplate.setRequestFactory(new ClientHttpRequestFactory() {
                @Override
                public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
                    throw HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR,
                            "synthetic-error", null,
                            sensitiveMarker.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
                }
            });

            assertThatThrownBy(() -> client.call(input()))
                    .isInstanceOfSatisfying(LlmCallException.class,
                            exception -> assertThat(exception.getFailCode()).isEqualTo(FailCode.SERVER_ERROR));

            assertThat(logAppender.list)
                    .filteredOn(event -> event.getFormattedMessage().contains("HTTP 调用失败"))
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.getFormattedMessage())
                                .contains(HttpServerErrorException.class.getName())
                                .doesNotContain(sensitiveMarker);
                        assertThat(event.getThrowableProxy()).isNull();
                    });
            assertThat(logAppender.list).allSatisfy(event -> {
                String throwableText = event.getThrowableProxy() == null
                        ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy());
                assertThat(event.getFormattedMessage()).doesNotContain(sensitiveMarker);
                assertThat(throwableText).doesNotContain(sensitiveMarker);
            });
        } finally {
            applicationLogger.detachAppender(logAppender);
            applicationLogger.setLevel(previousLevel);
            logAppender.stop();
        }
    }

    private OpenAiCompatibleExtractionModelClient client() {
        ExtractionProperties properties = new ExtractionProperties();
        properties.setBaseUrl("http://127.0.0.1");
        properties.setModel("test-model");
        properties.setApiKey("test-api-key");
        return new OpenAiCompatibleExtractionModelClient(new ObjectMapper(), properties);
    }

    private RestTemplate extractRestTemplate(OpenAiCompatibleExtractionModelClient client) throws Exception {
        Field restTemplateField = OpenAiCompatibleExtractionModelClient.class.getDeclaredField("restTemplate");
        restTemplateField.setAccessible(true);
        return (RestTemplate) restTemplateField.get(client);
    }

    private ExtractionBatchInput input() {
        BatchPage page = new BatchPage(1, "=== page 1 ===", null, false);
        return new ExtractionBatchInput("system", "extraction-2.3.0", 0, 0, 1,
                Collections.singletonList(page));
    }
}
