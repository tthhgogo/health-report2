package com.example.healthreport.infra;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.example.healthreport.llm.extraction.BatchPage;
import com.example.healthreport.llm.extraction.ExtractionBatchInput;
import com.example.healthreport.support.FailCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R58/R60-R65c：真实 HTTP 层验证消息结构、有界内存、脱敏与零重试。 */
class OpenAiCompatibleExtractionModelClientTest {

    private static final String PATH = "/v1/chat/completions";
    private static final String PRIVATE_RENDERED_TEXT_MARKER = "PRIVATE_RENDERED_TEXT_MARKER";
    private static final String PRIVATE_RESPONSE_MARKER = "PRIVATE_RESPONSE_MARKER";

    private WireMockServer wireMockServer;
    private ObjectMapper objectMapper;
    private Logger applicationLogger;
    private Level previousApplicationLogLevel;
    private ListAppender<ILoggingEvent> applicationLogAppender;
    private Logger rootLogger;
    private ListAppender<ILoggingEvent> rootLogAppender;

    @BeforeEach
    void startServer() {
        wireMockServer = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
        try {
            wireMockServer.start();
        } catch (RuntimeException exception) {
            throw new AssertionError("WireMock 无法监听本机回环端口，真实 HTTP 红线测试不能执行", exception);
        }
        objectMapper = new ObjectMapper();
        applicationLogger = (Logger) LoggerFactory.getLogger("com.example.healthreport");
        previousApplicationLogLevel = applicationLogger.getLevel();
        applicationLogAppender = new ListAppender<ILoggingEvent>();
        applicationLogAppender.start();
        applicationLogger.addAppender(applicationLogAppender);
        applicationLogger.setLevel(Level.ALL);
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogAppender = new ListAppender<ILoggingEvent>();
        rootLogAppender.start();
        rootLogger.addAppender(rootLogAppender);
    }

    @AfterEach
    void stopServer() {
        if (applicationLogger != null && applicationLogAppender != null) {
            applicationLogger.detachAppender(applicationLogAppender);
            applicationLogger.setLevel(previousApplicationLogLevel);
            applicationLogAppender.stop();
        }
        if (rootLogger != null && rootLogAppender != null) {
            rootLogger.detachAppender(rootLogAppender);
            rootLogAppender.stop();
        }
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

    @Test
    void shouldSendBufferedAlternatingContentWithoutPrivateIdentifiers() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(successResponse("{\"batchStatus\":\"OK\"}"))));
        OpenAiCompatibleExtractionModelClient client = client(properties(1024 * 1024, 1024 * 1024, 1000));

        String content = client.call(twoPageInput());

        assertThat(content).isEqualTo("{\"batchStatus\":\"OK\"}");
        com.github.tomakehurst.wiremock.verification.LoggedRequest request =
                wireMockServer.findAll(postRequestedFor(urlEqualTo(PATH))).get(0);
        JsonNode root = objectMapper.readTree(request.getBodyAsString());
        assertThat(root.path("stream").asBoolean()).isFalse();
        JsonNode userContent = root.path("messages").path(1).path("content");
        assertThat(userContent).hasSize(5);
        assertThat(userContent.path(0).path("text").asText())
                .contains("fileIndex=0", "batchIndex=0", "batchCount=1", "promptVersion=extraction-2.3.0");
        assertThat(userContent.path(1).path("type").asText()).isEqualTo("text");
        assertThat(userContent.path(2).path("type").asText()).isEqualTo("image_url");
        assertThat(userContent.path(3).path("type").asText()).isEqualTo("text");
        assertThat(userContent.path(4).path("type").asText()).isEqualTo("image_url");
        assertThat(request.getBodyAsString())
                .doesNotContain("taskId", "userId", "origin_name", "segmentId");
        assertThat(request.getHeader("Content-Length")).isEqualTo(String.valueOf(request.getBody().length));
        assertThat(request.getHeader("Transfer-Encoding")).isNull();
        wireMockServer.verify(1, postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer test-api-key")));
        assertApplicationLogsDoNotContain(PRIVATE_RENDERED_TEXT_MARKER, "data:image/jpeg");
    }

    @Test
    void invalidOrderDuplicateAndMissingRequiredImageShouldFailBeforeSending() {
        OpenAiCompatibleExtractionModelClient client = client(properties(1024 * 1024, 1024 * 1024, 1000));
        BatchPage pageOne = page(1, new byte[]{1}, true);
        BatchPage pageTwo = page(2, new byte[]{2}, true);

        assertThatThrownBy(() -> client.call(input(Arrays.asList(pageTwo, pageOne))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.call(input(Arrays.asList(pageOne, pageOne))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.call(input(Collections.singletonList(page(1, null, true)))))
                .isInstanceOf(IllegalStateException.class);
        wireMockServer.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void oversizeRequestShouldFailBeforeBase64AndCappedBufferShouldNeverExceedLimit() {
        OpenAiCompatibleExtractionModelClient client = client(properties(5000, 1024 * 1024, 1000));
        BatchPage largeImagePage = page(1, new byte[4096], true);

        assertThatThrownBy(() -> client.call(input(Collections.singletonList(largeImagePage))))
                .isInstanceOfSatisfying(LlmCallException.class,
                        exception -> assertThat(exception.getFailCode())
                                .isEqualTo(FailCode.SERVER_ERROR));
        wireMockServer.verify(0, postRequestedFor(urlEqualTo(PATH)));
        assertThat(applicationLogAppender.list).anySatisfy(event -> {
            assertThat(event.getFormattedMessage()).contains("fileIndex=0", "batchIndex=0");
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getClassName())
                    .isEqualTo(RequestTooLargeException.class.getName());
        });
        assertApplicationLogsDoNotContain(PRIVATE_RENDERED_TEXT_MARKER, "data:image/jpeg");

        CappedByteArrayOutputStream output = new CappedByteArrayOutputStream(2, 8);
        output.write(new byte[8], 0, 8);
        assertThat(output.size()).isEqualTo(8);
        assertThat(output.capacity()).isEqualTo(8);
        assertThatThrownBy(() -> output.write(1)).isInstanceOf(RequestTooLargeException.class);
        assertThat(output.size()).isEqualTo(8);
        assertThat(output.capacity()).isEqualTo(8);
    }

    @Test
    void statusErrorsAndReadTimeoutShouldEachCallExactlyOnce() throws Exception {
        assertSingleFailure(429, 0);
        assertSingleFailure(500, 0);

        wireMockServer.resetAll();
        wireMockServer.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200).withFixedDelay(300)
                .withBody(successResponse(PRIVATE_RESPONSE_MARKER))));
        OpenAiCompatibleExtractionModelClient timeoutClient =
                client(properties(1024 * 1024, 1024 * 1024, 50));
        assertThatThrownBy(() -> timeoutClient.call(input(Collections.singletonList(page(1, null, false)))))
                .isInstanceOf(LlmCallException.class);
        wireMockServer.verify(1, postRequestedFor(urlEqualTo(PATH)));
        assertApplicationLogsDoNotContain(
                PRIVATE_RENDERED_TEXT_MARKER, PRIVATE_RESPONSE_MARKER, "data:image/jpeg");
        assertThat(applicationLogAppender.list)
                .filteredOn(event -> event.getThrowableProxy() != null)
                .hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void oversizedSuccessAndErrorResponsesShouldEachCallOnce() {
        String oversized = PRIVATE_RESPONSE_MARKER + repeat('x', 32768);
        wireMockServer.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200).withBody(oversized)));
        OpenAiCompatibleExtractionModelClient client = client(properties(1024 * 1024, 1024, 1000));

        assertThatThrownBy(() -> client.call(input(Collections.singletonList(page(1, null, false)))))
                .isInstanceOf(LlmCallException.class);
        wireMockServer.verify(1, postRequestedFor(urlEqualTo(PATH)));

        wireMockServer.resetAll();
        wireMockServer.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(500).withBody(oversized)));
        assertThatThrownBy(() -> client.call(input(Collections.singletonList(page(1, null, false)))))
                .isInstanceOfSatisfying(LlmCallException.class,
                        exception -> assertThat(exception.getHttpStatus()).isEqualTo(500));
        wireMockServer.verify(1, postRequestedFor(urlEqualTo(PATH)));
        assertApplicationLogsDoNotContain(
                PRIVATE_RENDERED_TEXT_MARKER, PRIVATE_RESPONSE_MARKER, "data:image/jpeg");
        assertThat(applicationLogAppender.list)
                .filteredOn(event -> event.getFormattedMessage().contains("响应体超限"))
                .anySatisfy(event -> {
                    assertThat(event.getThrowableProxy()).isNotNull();
                    assertThat(event.getThrowableProxy().getClassName())
                            .isEqualTo(ResponseTooLargeException.class.getName());
                });
        assertThat(applicationLogAppender.list)
                .filteredOn(event -> event.getFormattedMessage().contains("状态码=500"))
                .anySatisfy(event -> {
                    assertThat(event.getThrowableProxy()).isNotNull();
                    assertThat(event.getThrowableProxy().getClassName())
                            .isEqualTo(LlmCallException.class.getName());
                });
    }

    @Test
    void malformedSuccessResponseMustNotLeakResponseFragmentToLogs() {
        String sensitiveMarker = "SENSITIVE_RESPONSE_MARKER";
        wireMockServer.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200).withBody("{broken-" + sensitiveMarker)));
        assertThatThrownBy(() -> client(properties(1024 * 1024, 1024 * 1024, 1000))
                .call(input(Collections.singletonList(page(1, null, false)))))
                .isInstanceOf(LlmCallException.class);
        assertApplicationLogsDoNotContain(sensitiveMarker);
    }

    @Test
    void reasoningContentJsonShouldBeUsedWhenContentIsEmpty() throws Exception {
        String expectedJson = "{\"batchStatus\":\"OK\"}";
        OpenAiCompatibleExtractionModelClient client =
                client(properties(1024 * 1024, 1024 * 1024, 1000));

        String actualJson = client.extractContent(responseWithChannels("", expectedJson), 0);

        assertThat(actualJson).isEqualTo(expectedJson);
    }

    @Test
    void validReasoningContentShouldReplaceIncompleteContent() throws Exception {
        String incompleteContentMarker = "INCOMPLETE_CONTENT_MARKER";
        String expectedJson = "{\"batchStatus\":\"OK\"}";
        OpenAiCompatibleExtractionModelClient client =
                client(properties(1024 * 1024, 1024 * 1024, 1000));

        String actualJson = client.extractContent(responseWithChannels(
                "{\"partial\":\"" + incompleteContentMarker, expectedJson), 0);

        assertThat(actualJson).isEqualTo(expectedJson);
        assertApplicationLogsDoNotContain(incompleteContentMarker);
    }

    @Test
    void differentValidJsonChannelsMustFailSafeWithoutLeakingEitherChannel() throws Exception {
        String contentMarker = "CONTENT_CHANNEL_PRIVATE_MARKER";
        String reasoningMarker = "REASONING_CHANNEL_PRIVATE_MARKER";
        OpenAiCompatibleExtractionModelClient client =
                client(properties(1024 * 1024, 1024 * 1024, 1000));

        assertThatThrownBy(() -> client.extractContent(responseWithChannels(
                "{\"source\":\"" + contentMarker + "\"}",
                "{\"source\":\"" + reasoningMarker + "\"}"), 0))
                .isInstanceOf(LlmCallException.class);
        assertApplicationLogsDoNotContain(contentMarker, reasoningMarker);
    }

    private void assertSingleFailure(int status, int expectedDelay) {
        wireMockServer.resetAll();
        wireMockServer.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(status).withFixedDelay(expectedDelay)
                .withBody(PRIVATE_RESPONSE_MARKER + repeat('e', 32768))));
        OpenAiCompatibleExtractionModelClient client = client(properties(1024 * 1024, 1024, 1000));

        assertThatThrownBy(() -> client.call(input(Collections.singletonList(page(1, null, false)))))
                .isInstanceOfSatisfying(LlmCallException.class,
                        exception -> assertThat(exception.getHttpStatus()).isEqualTo(status));
        wireMockServer.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    private OpenAiCompatibleExtractionModelClient client(ExtractionProperties properties) {
        return new OpenAiCompatibleExtractionModelClient(objectMapper, properties);
    }

    private ExtractionProperties properties(int maxRequestBytes, int maxResponseBytes, int readTimeoutMillis) {
        ExtractionProperties properties = new ExtractionProperties();
        properties.setBaseUrl("http://127.0.0.1:" + wireMockServer.port());
        properties.setModel("test-model");
        properties.setApiKey("test-api-key");
        properties.setConnectTimeoutMillis(1000);
        properties.setReadTimeoutMillis(readTimeoutMillis);
        properties.setMaxRequestBodyBytes(maxRequestBytes);
        properties.setMaxResponseBodyBytes(maxResponseBytes);
        return properties;
    }

    private ExtractionBatchInput twoPageInput() {
        return input(Arrays.asList(page(2, new byte[]{1, 2}, true),
                page(5, new byte[]{3, 4}, true)));
    }

    private ExtractionBatchInput input(java.util.List<BatchPage> pageList) {
        return new ExtractionBatchInput("system", "extraction-2.3.0", 0, 0, 1, pageList);
    }

    private BatchPage page(int page, byte[] jpegBytes, boolean imageRequired) {
        return new BatchPage(page, "=== 第 " + page + " 页 ===\n[0] (NATIVE, bbox=null) "
                + PRIVATE_RENDERED_TEXT_MARKER + "\n",
                jpegBytes, imageRequired);
    }

    private void assertApplicationLogsDoNotContain(String... markerArray) {
        assertLogEventsDoNotContain(applicationLogAppender.list, markerArray);
        assertLogEventsDoNotContain(rootLogAppender.list, markerArray);
    }

    private void assertLogEventsDoNotContain(java.util.List<ILoggingEvent> eventList,
                                             String... markerArray) {
        assertThat(eventList).allSatisfy(event -> {
            String throwableText = event.getThrowableProxy() == null
                    ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy());
            for (String marker : markerArray) {
                assertThat(event.getFormattedMessage()).doesNotContain(marker);
                assertThat(throwableText).doesNotContain(marker);
            }
        });
    }

    /**
     * {@code finish_reason} 不是 {@code stop} 就是被截断，半截 JSON 后面全不可信。
     * <p>缺失同样按截断处理：网关不给这个字段时，无法证明输出完整。</p>
     */
    /**
     * 请求体里每个顶层字段只能出现一次。
     *
     * <p><b>必须查原始字节，不能查 {@code readTree} 的结果</b>——Jackson 会用后者覆盖
     * 重复键，测试因此完全看不见问题，而部分网关会直接拒绝含重复字段的 JSON。
     * 2026-09-02 曾因两处独立改动各写了一次 {@code stream} 而出现重复。</p>
     */
    @Test
    void requestBodyMustNotContainDuplicateTopLevelFields() throws Exception {
        OpenAiCompatibleExtractionModelClient client =
                client(properties(1024 * 1024, 1024 * 1024, 1000));

        String rawBody = new String(client.buildRequestBody(twoPageInput()),
                java.nio.charset.StandardCharsets.UTF_8);

        for (String field : new String[] {"\"stream\"", "\"model\"", "\"temperature\"",
                "\"messages\"", "\"response_format\""}) {
            assertThat(countOccurrences(rawBody, field))
                    .as("顶层字段 " + field + " 重复出现").isEqualTo(1);
        }
    }

    private int countOccurrences(String text, String token) {
        int count = 0;
        int index = text.indexOf(token);
        while (index >= 0) {
            count++;
            index = text.indexOf(token, index + token.length());
        }
        return count;
    }

    @Test
    void truncatedOrMissingFinishReasonMustFail() throws Exception {
        OpenAiCompatibleExtractionModelClient client =
                client(properties(1024 * 1024, 1024 * 1024, 1000));
        String privateMarker = "TRUNCATED_RESPONSE_MARKER";

        for (String finishReason : new String[] {"length", "content_filter", "", null}) {
            final String response = responseWithFinishReason(
                    "{\"batchStatus\":\"OK\",\"marker\":\"" + privateMarker + "\"}", finishReason);

            assertThatThrownBy(() -> client.extractContent(response, 0))
                    .as("finishReason=" + finishReason)
                    .isInstanceOf(LlmCallException.class);
        }
        // 截断日志只记 finishReason 与长度，绝不带响应正文。
        assertApplicationLogsDoNotContain(privateMarker);
    }

    /** 隐私标记放在 {@code finish_reason} 里也不得进入普通日志——只记白名单分类。 */
    @Test
    void privateMarkerInFinishReasonMustNotReachTheLog() throws Exception {
        OpenAiCompatibleExtractionModelClient client =
                client(properties(1024 * 1024, 1024 * 1024, 1000));
        String privateMarker = "FINISH_REASON_PRIVATE_MARKER";
        final String response = responseWithFinishReason("{\"batchStatus\":\"OK\"}", privateMarker);

        assertThatThrownBy(() -> client.extractContent(response, 0))
                .isInstanceOf(LlmCallException.class);

        assertApplicationLogsDoNotContain(privateMarker);
        assertThat(OpenAiCompatibleExtractionModelClient.classifyFinishReason(privateMarker))
                .isEqualTo("other");
    }

    /** 外层信封后面还跟着第二段 JSON 或解释文字，都必须拒绝。 */
    @Test
    void trailingContentAfterResponseEnvelopeMustFail() throws Exception {
        OpenAiCompatibleExtractionModelClient client =
                client(properties(1024 * 1024, 1024 * 1024, 1000));
        String envelope = successResponse("{\"batchStatus\":\"OK\"}");

        for (String trailing : new String[] {" {\"second\":1}", "\n网关附言"}) {
            final String polluted = envelope + trailing;
            assertThatThrownBy(() -> client.extractContent(polluted, 0))
                    .as("尾随=" + trailing.trim())
                    .isInstanceOf(LlmCallException.class);
        }
    }

    private String successResponse(String content) throws Exception {
        return responseWithFinishReason(content, "stop");
    }

    /** 正常响应必须带 {@code finish_reason=stop}；缺失或非 stop 都表示被截断。 */
    private String responseWithFinishReason(String content, String finishReason) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode root = objectMapper.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode choiceNode =
                root.putArray("choices").addObject();
        if (finishReason != null) {
            choiceNode.put("finish_reason", finishReason);
        }
        choiceNode.putObject("message").put("content", content);
        return objectMapper.writeValueAsString(root);
    }

    private String responseWithChannels(String content, String reasoningContent) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode root = objectMapper.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode choiceNode =
                root.putArray("choices").addObject();
        choiceNode.put("finish_reason", "stop");
        com.fasterxml.jackson.databind.node.ObjectNode messageNode = choiceNode.putObject("message");
        messageNode.put("content", content);
        messageNode.put("reasoning_content", reasoningContent);
        return objectMapper.writeValueAsString(root);
    }

    private String repeat(char value, int count) {
        char[] values = new char[count];
        Arrays.fill(values, value);
        return new String(values);
    }

}
