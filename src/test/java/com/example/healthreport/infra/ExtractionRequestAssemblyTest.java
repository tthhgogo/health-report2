package com.example.healthreport.infra;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.healthreport.llm.extraction.BatchPage;
import com.example.healthreport.llm.extraction.ExtractionBatchInput;
import com.example.healthreport.parse.CompressedPageImage;
import com.example.healthreport.parse.ExtractionImageCompressor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 不依赖监听端口的请求组装、前置校验和响应日志脱敏测试。 */
class ExtractionRequestAssemblyTest {

    private static final String PRIVATE_RENDERED_TEXT_MARKER = "PRIVATE_RENDERED_TEXT_MARKER";

    static {
        // JDK 8 在无图形会话的 macOS 测试进程中必须显式走 headless ImageIO。
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void shouldAssembleAlternatingPagesAndExcludePrivateIdentifiers() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiCompatibleExtractionModelClient client = client(objectMapper, 1024 * 1024);
        ExtractionBatchInput input = input(Arrays.asList(
                page(2, new byte[]{1}, true), page(5, new byte[]{2}, true)));

        client.assertPageListValid(input);
        byte[] requestBytes = client.buildRequestBody(input);
        JsonNode root = objectMapper.readTree(requestBytes);
        JsonNode contentList = root.path("messages").path(1).path("content");

        assertThat(contentList.size()).isEqualTo(5);
        assertThat(contentList.path(0).path("text").asText())
                .contains("fileIndex=0", "batchIndex=0", "batchCount=1", "promptVersion=extraction-2.3.0");
        assertThat(contentList.path(1).path("type").asText()).isEqualTo("text");
        assertThat(contentList.path(2).path("type").asText()).isEqualTo("image_url");
        assertThat(contentList.path(3).path("type").asText()).isEqualTo("text");
        assertThat(contentList.path(4).path("type").asText()).isEqualTo("image_url");
        assertThat(new String(requestBytes, java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("taskId", "userId", "origin_name", "segmentId");
    }

    @Test
    @Tag("release-gate")
    void largeSourceImageShouldOnlyEnterRequestAfterBoundedCompression() throws Exception {
        BufferedImage source = new BufferedImage(4000, 3000, BufferedImage.TYPE_INT_RGB);
        try {
            CompressedPageImage compressed = new ExtractionImageCompressor().compressForExtraction(source);
            byte[] requestBytes = client(new ObjectMapper(), 32 * 1024 * 1024)
                    .buildRequestBody(input(Collections.singletonList(
                            page(1, compressed.getJpegBytes(), true))));
            String request = new String(requestBytes, java.nio.charset.StandardCharsets.UTF_8);

            assertThat(Math.max(compressed.getWidth(), compressed.getHeight())).isEqualTo(2000);
            assertThat(compressed.sizeBytes()).isLessThanOrEqualTo(1024 * 1024);
            assertThat(request).contains(Base64.getEncoder()
                    .encodeToString(compressed.getJpegBytes()));
        } finally {
            source.flush();
        }
    }

    @Test
    void shouldRejectInvalidPagesAndOversizeBeforeEncoding() {
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiCompatibleExtractionModelClient client = client(objectMapper, 5000);
        BatchPage first = page(1, new byte[]{1}, true);
        BatchPage second = page(2, new byte[]{2}, true);

        assertThatThrownBy(() -> client.assertPageListValid(input(Arrays.asList(second, first))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.assertPageListValid(input(Arrays.asList(first, first))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.assertPageListValid(
                input(Collections.singletonList(page(1, null, true)))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.buildRequestBody(
                input(Collections.singletonList(page(1, new byte[4096], true)))))
                .isInstanceOf(RequestTooLargeException.class);

        CappedByteArrayOutputStream output = new CappedByteArrayOutputStream(2, 8);
        output.write(new byte[8], 0, 8);
        assertThat(output.size()).isEqualTo(8);
        assertThat(output.capacity()).isEqualTo(8);
        assertThatThrownBy(() -> output.write(1)).isInstanceOf(RequestTooLargeException.class);
        assertThat(output.size()).isEqualTo(8);
        assertThat(output.capacity()).isEqualTo(8);
    }

    @Test
    void oversizeCallShouldWrapExceptionLogSafeCoordinatesAndNeverReachNetwork() {
        Logger applicationLogger = (Logger) LoggerFactory.getLogger("com.example.healthreport");
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
        appender.start();
        applicationLogger.addAppender(appender);
        try {
            OpenAiCompatibleExtractionModelClient client = client(new ObjectMapper(), 5000);
            assertThatThrownBy(() -> client.call(input(Collections.singletonList(
                    page(1, new byte[4096], true)))))
                    .isInstanceOfSatisfying(LlmCallException.class,
                            exception -> assertThat(exception.getFailCode())
                                    .isEqualTo(com.example.healthreport.support.FailCode.SERVER_ERROR));
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getFormattedMessage())
                        .contains("fileIndex=0", "batchIndex=0")
                        .doesNotContain(PRIVATE_RENDERED_TEXT_MARKER, "data:image/jpeg");
                assertThat(event.getThrowableProxy()).isNotNull();
                assertThat(event.getThrowableProxy().getClassName())
                        .isEqualTo(RequestTooLargeException.class.getName());
            });
        } finally {
            applicationLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void requestSerializationFailureShouldLogSafeExceptionAndNeverReachNetwork() throws Exception {
        com.fasterxml.jackson.core.JsonFactory jsonFactory =
                mock(com.fasterxml.jackson.core.JsonFactory.class);
        when(jsonFactory.createGenerator(any(java.io.OutputStream.class)))
                .thenThrow(new IOException("safe-serialization-failure"));
        ObjectMapper failingObjectMapper = mock(ObjectMapper.class);
        when(failingObjectMapper.getFactory()).thenReturn(jsonFactory);
        Logger applicationLogger = (Logger) LoggerFactory.getLogger("com.example.healthreport");
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
        appender.start();
        applicationLogger.addAppender(appender);
        try {
            OpenAiCompatibleExtractionModelClient client = client(failingObjectMapper, 1024 * 1024);
            assertThatThrownBy(() -> client.call(input(Collections.singletonList(
                    page(1, null, false)))))
                    .isInstanceOfSatisfying(LlmCallException.class,
                            exception -> assertThat(exception.getFailCode())
                                    .isEqualTo(com.example.healthreport.support.FailCode.SERVER_ERROR));
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getFormattedMessage())
                        .contains("fileIndex=0", "batchIndex=0", "请求体序列化失败")
                        .doesNotContain(PRIVATE_RENDERED_TEXT_MARKER);
                assertThat(event.getThrowableProxy()).isNotNull();
                assertThat(event.getThrowableProxy().getClassName())
                        .isEqualTo(IOException.class.getName());
            });
        } finally {
            applicationLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void malformedResponseMustNotAppearInApplicationLogs() {
        String sensitiveMarker = "SENSITIVE_RESPONSE_MARKER";
        Logger applicationLogger = (Logger) LoggerFactory.getLogger("com.example.healthreport");
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
        appender.start();
        applicationLogger.addAppender(appender);
        try {
            assertThatThrownBy(() -> client(new ObjectMapper(), 1024 * 1024)
                    .extractContent("{broken-" + sensitiveMarker, 0))
                    .isInstanceOf(LlmCallException.class);
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .allSatisfy(message -> assertThat(message).doesNotContain(sensitiveMarker));
        } finally {
            applicationLogger.detachAppender(appender);
            appender.stop();
        }
    }

    private OpenAiCompatibleExtractionModelClient client(ObjectMapper objectMapper, int maxRequestBytes) {
        ExtractionProperties properties = new ExtractionProperties();
        properties.setBaseUrl("http://127.0.0.1");
        properties.setModel("test-model");
        properties.setApiKey("test-api-key");
        properties.setMaxRequestBodyBytes(maxRequestBytes);
        return new OpenAiCompatibleExtractionModelClient(objectMapper, properties);
    }

    private ExtractionBatchInput input(List<BatchPage> pageList) {
        return new ExtractionBatchInput("system", "extraction-2.3.0", 0, 0, 1, pageList);
    }

    private BatchPage page(int page, byte[] jpegBytes, boolean imageRequired) {
        return new BatchPage(page, "=== 第 " + page + " 页 ===\n[0] (NATIVE, bbox=null) "
                + PRIVATE_RENDERED_TEXT_MARKER + "\n",
                jpegBytes, imageRequired);
    }
}
