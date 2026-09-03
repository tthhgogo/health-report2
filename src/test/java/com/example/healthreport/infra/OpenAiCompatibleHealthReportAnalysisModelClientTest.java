package com.example.healthreport.infra;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.example.healthreport.llm.extraction.ExtractionCall;
import com.example.healthreport.llm.extraction.ExtractionCallInput;
import com.example.healthreport.render.PageImage;
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
class OpenAiCompatibleHealthReportAnalysisModelClientTest {

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
        OpenAiCompatibleHealthReportAnalysisModelClient client = client(properties(1024 * 1024, 1024 * 1024, 1000));

        String content = client.call(twoPageInput());

        assertThat(content).isEqualTo("{\"batchStatus\":\"OK\"}");
        com.github.tomakehurst.wiremock.verification.LoggedRequest request =
                wireMockServer.findAll(postRequestedFor(urlEqualTo(PATH))).get(0);
        JsonNode root = objectMapper.readTree(request.getBodyAsString());
        assertThat(root.path("stream").asBoolean()).isFalse();
        JsonNode userContent = root.path("messages").path(1).path("content");
        assertThat(userContent).hasSize(5);
        assertThat(userContent.path(0).path("text").asText()).contains("共 2 张");
        assertThat(userContent.path(1).path("text").asText()).contains("第 1 页");
        assertThat(userContent.path(3).path("text").asText()).contains("第 2 页");
        assertThat(root.path("chat_template_kwargs").path("enable_thinking").asBoolean()).isFalse();
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
        OpenAiCompatibleHealthReportAnalysisModelClient client = client(properties(1024 * 1024, 1024 * 1024, 1000));
        PageImage pageOne = page(1, new byte[]{1});
        PageImage pageTwo = page(2, new byte[]{2});

        assertThatThrownBy(() -> client.call(input(Arrays.asList(pageTwo, pageOne))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.call(input(Arrays.asList(pageOne, pageOne))))
                .isInstanceOf(IllegalStateException.class);
        // 缺图在 PageImage 构造时就被拒，根本组不出一个缺图的输入。
        assertThatThrownBy(() -> page(1, null)).isInstanceOf(IllegalArgumentException.class);
        // 页码必须从 1 起连续：跳页输入同样在发送前失败。
        assertThatThrownBy(() -> client.call(input(Collections.singletonList(page(2, new byte[]{2})))))
                .isInstanceOf(IllegalStateException.class);
        wireMockServer.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void oversizeRequestShouldFailBeforeBase64AndCappedBufferShouldNeverExceedLimit() {
        OpenAiCompatibleHealthReportAnalysisModelClient client = client(properties(5000, 1024 * 1024, 1000));
        PageImage largeImagePage = page(1, new byte[4096]);

        assertThatThrownBy(() -> client.call(input(Collections.singletonList(largeImagePage))))
                .isInstanceOfSatisfying(HealthReportAnalysisCallException.class,
                        exception -> assertThat(exception.getFailCode())
                                .isEqualTo(FailCode.SERVER_ERROR));
        wireMockServer.verify(0, postRequestedFor(urlEqualTo(PATH)));
        assertThat(applicationLogAppender.list).anySatisfy(event -> {
            assertThat(event.getFormattedMessage()).contains("call=INDICATORS");
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
        OpenAiCompatibleHealthReportAnalysisModelClient timeoutClient =
                client(properties(1024 * 1024, 1024 * 1024, 50));
        assertThatThrownBy(() -> timeoutClient.call(input(Collections.singletonList(page(1, new byte[]{9})))))
                .isInstanceOf(HealthReportAnalysisCallException.class);
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
        OpenAiCompatibleHealthReportAnalysisModelClient client = client(properties(1024 * 1024, 1024, 1000));

        assertThatThrownBy(() -> client.call(input(Collections.singletonList(page(1, new byte[]{9})))))
                .isInstanceOf(HealthReportAnalysisCallException.class);
        wireMockServer.verify(1, postRequestedFor(urlEqualTo(PATH)));

        wireMockServer.resetAll();
        wireMockServer.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(500).withBody(oversized)));
        assertThatThrownBy(() -> client.call(input(Collections.singletonList(page(1, new byte[]{9})))))
                .isInstanceOfSatisfying(HealthReportAnalysisCallException.class,
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
                            .isEqualTo(HealthReportAnalysisCallException.class.getName());
                });
    }

    @Test
    void malformedSuccessResponseMustNotLeakResponseFragmentToLogs() {
        String sensitiveMarker = "SENSITIVE_RESPONSE_MARKER";
        wireMockServer.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200).withBody("{broken-" + sensitiveMarker)));
        assertThatThrownBy(() -> client(properties(1024 * 1024, 1024 * 1024, 1000))
                .call(input(Collections.singletonList(page(1, new byte[]{9})))))
                .isInstanceOf(HealthReportAnalysisCallException.class);
        assertApplicationLogsDoNotContain(sensitiveMarker);
    }

    @Test
    void reasoningContentJsonShouldBeUsedWhenContentIsEmpty() throws Exception {
        String expectedJson = "{\"batchStatus\":\"OK\"}";
        OpenAiCompatibleHealthReportAnalysisModelClient client =
                client(properties(1024 * 1024, 1024 * 1024, 1000));

        String actualJson = client.extractContent(responseWithChannels("", expectedJson), "INDICATORS");

        assertThat(actualJson).isEqualTo(expectedJson);
    }

    @Test
    void validReasoningContentShouldReplaceIncompleteContent() throws Exception {
        String incompleteContentMarker = "INCOMPLETE_CONTENT_MARKER";
        String expectedJson = "{\"batchStatus\":\"OK\"}";
        OpenAiCompatibleHealthReportAnalysisModelClient client =
                client(properties(1024 * 1024, 1024 * 1024, 1000));

        String actualJson = client.extractContent(responseWithChannels(
                "{\"partial\":\"" + incompleteContentMarker, expectedJson), "INDICATORS");

        assertThat(actualJson).isEqualTo(expectedJson);
        assertApplicationLogsDoNotContain(incompleteContentMarker);
    }

    @Test
    void differentValidJsonChannelsMustFailSafeWithoutLeakingEitherChannel() throws Exception {
        String contentMarker = "CONTENT_CHANNEL_PRIVATE_MARKER";
        String reasoningMarker = "REASONING_CHANNEL_PRIVATE_MARKER";
        OpenAiCompatibleHealthReportAnalysisModelClient client =
                client(properties(1024 * 1024, 1024 * 1024, 1000));

        assertThatThrownBy(() -> client.extractContent(responseWithChannels(
                "{\"source\":\"" + contentMarker + "\"}",
                "{\"source\":\"" + reasoningMarker + "\"}"), "INDICATORS"))
                .isInstanceOf(HealthReportAnalysisCallException.class);
        assertApplicationLogsDoNotContain(contentMarker, reasoningMarker);
    }

    private void assertSingleFailure(int status, int expectedDelay) {
        wireMockServer.resetAll();
        wireMockServer.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(status).withFixedDelay(expectedDelay)
                .withBody(PRIVATE_RESPONSE_MARKER + repeat('e', 32768))));
        OpenAiCompatibleHealthReportAnalysisModelClient client = client(properties(1024 * 1024, 1024, 1000));

        assertThatThrownBy(() -> client.call(input(Collections.singletonList(page(1, new byte[]{9})))))
                .isInstanceOfSatisfying(HealthReportAnalysisCallException.class,
                        exception -> assertThat(exception.getHttpStatus()).isEqualTo(status));
        wireMockServer.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    private OpenAiCompatibleHealthReportAnalysisModelClient client(HealthReportAnalysisModelProperties properties) {
        return new OpenAiCompatibleHealthReportAnalysisModelClient(objectMapper, properties);
    }

    private HealthReportAnalysisModelProperties properties(int maxRequestBytes, int maxResponseBytes, int readTimeoutMillis) {
        HealthReportAnalysisModelProperties properties = new HealthReportAnalysisModelProperties();
        properties.setBaseUrl("http://127.0.0.1:" + wireMockServer.port());
        properties.setModel("test-model");
        properties.setApiKey("test-api-key");
        properties.setConnectTimeoutMillis(1000);
        properties.setReadTimeoutMillis(readTimeoutMillis);
        properties.setMaxRequestBodyBytes(maxRequestBytes);
        properties.setMaxResponseBodyBytes(maxResponseBytes);
        // 本测试类主要覆盖非流式 JSON 信封路径；SSE 路径由专门用例覆盖。
        properties.setStreamEnabled(false);
        return properties;
    }

    private ExtractionCallInput twoPageInput() {
        return input(Arrays.asList(page(1, new byte[]{1, 2}), page(2, new byte[]{3, 4})));
    }

    private ExtractionCallInput input(java.util.List<PageImage> pageList) {
        return new ExtractionCallInput(ExtractionCall.INDICATORS, "system",
                "共 " + pageList.size() + " 张 " + PRIVATE_RENDERED_TEXT_MARKER, pageList);
    }

    private PageImage page(int page, byte[] jpegBytes) {
        return new PageImage(page, jpegBytes);
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
        OpenAiCompatibleHealthReportAnalysisModelClient client =
                client(properties(1024 * 1024, 1024 * 1024, 1000));

        String rawBody = new String(client.buildRequestBody(twoPageInput()),
                java.nio.charset.StandardCharsets.UTF_8);

        for (String field : new String[] {"\"stream\"", "\"model\"", "\"temperature\"",
                "\"messages\"", "\"response_format\"", "\"chat_template_kwargs\""}) {
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
        OpenAiCompatibleHealthReportAnalysisModelClient client =
                client(properties(1024 * 1024, 1024 * 1024, 1000));
        String privateMarker = "TRUNCATED_RESPONSE_MARKER";

        for (String finishReason : new String[] {"length", "content_filter", "", null}) {
            final String response = responseWithFinishReason(
                    "{\"batchStatus\":\"OK\",\"marker\":\"" + privateMarker + "\"}", finishReason);

            assertThatThrownBy(() -> client.extractContent(response, "INDICATORS"))
                    .as("finishReason=" + finishReason)
                    .isInstanceOf(HealthReportAnalysisCallException.class);
        }
        // 截断日志只记 finishReason 与长度，绝不带响应正文。
        assertApplicationLogsDoNotContain(privateMarker);
    }

    /** 隐私标记放在 {@code finish_reason} 里也不得进入普通日志——只记白名单分类。 */
    @Test
    void privateMarkerInFinishReasonMustNotReachTheLog() throws Exception {
        OpenAiCompatibleHealthReportAnalysisModelClient client =
                client(properties(1024 * 1024, 1024 * 1024, 1000));
        String privateMarker = "FINISH_REASON_PRIVATE_MARKER";
        final String response = responseWithFinishReason("{\"batchStatus\":\"OK\"}", privateMarker);

        assertThatThrownBy(() -> client.extractContent(response, "INDICATORS"))
                .isInstanceOf(HealthReportAnalysisCallException.class);

        assertApplicationLogsDoNotContain(privateMarker);
        assertThat(OpenAiCompatibleHealthReportAnalysisModelClient.classifyFinishReason(privateMarker))
                .isEqualTo("other");
    }

    /** 外层信封后面还跟着第二段 JSON 或解释文字，都必须拒绝。 */
    @Test
    void trailingContentAfterResponseEnvelopeMustFail() throws Exception {
        OpenAiCompatibleHealthReportAnalysisModelClient client =
                client(properties(1024 * 1024, 1024 * 1024, 1000));
        String envelope = successResponse("{\"batchStatus\":\"OK\"}");

        for (String trailing : new String[] {" {\"second\":1}", "\n网关附言"}) {
            final String polluted = envelope + trailing;
            assertThatThrownBy(() -> client.extractContent(polluted, "INDICATORS"))
                    .as("尾随=" + trailing.trim())
                    .isInstanceOf(HealthReportAnalysisCallException.class);
        }
    }

    /** SSE 流式：分块累计 content，与非流式共用 finish_reason 与双通道裁决。 */
    @Test
    void streamedResponseShouldAccumulateChunksAndAdjudicate() {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"a\\\":\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"1}\"},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n";
        wireMockServer.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "text/event-stream").withBody(sse)));
        HealthReportAnalysisModelProperties streamProperties = properties(1024 * 1024, 1024 * 1024, 1000);
        streamProperties.setStreamEnabled(true);
        OpenAiCompatibleHealthReportAnalysisModelClient client = client(streamProperties);

        String content = client.call(input(Collections.singletonList(page(1, new byte[]{9}))));

        assertThat(content).isEqualTo("{\"a\":1}");
        wireMockServer.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    /** SSE 响应上限按 UTF-8 字节统计：中文一个字符占多个字节，按字符数计会放行数倍超限。 */
    @Test
    void streamedResponseLimitMustCountUtf8BytesNotChars() {
        StringBuilder chinese = new StringBuilder();
        for (int index = 0; index < 300; index++) {
            chinese.append("甘油三酯偏高");
        }
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"" + chinese + "\"}}]}\n\n"
                + "data: [DONE]\n\n";
        wireMockServer.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "text/event-stream").withBody(sse)));
        // 上限 4000 字节：1800 个汉字按字符数只有 ~1900，按 UTF-8 字节是 ~5400，必须被拒。
        HealthReportAnalysisModelProperties streamProperties = properties(1024 * 1024, 4000, 1000);
        streamProperties.setStreamEnabled(true);

        assertThatThrownBy(() -> client(streamProperties)
                .call(input(Collections.singletonList(page(1, new byte[]{9})))))
                .isInstanceOf(HealthReportAnalysisCallException.class);
        assertApplicationLogsDoNotContain("甘油三酯");
    }

    /** 网关忽略 stream:true 回普通 JSON 信封：显式失败，且日志指出疑似流式未启用。 */
    @Test
    void plainJsonEnvelopeUnderStreamModeMustFailWithConfigHint() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(successResponse("{\"a\":1}"))));
        HealthReportAnalysisModelProperties streamProperties = properties(1024 * 1024, 1024 * 1024, 1000);
        streamProperties.setStreamEnabled(true);

        assertThatThrownBy(() -> client(streamProperties)
                .call(input(Collections.singletonList(page(1, new byte[]{9})))))
                .isInstanceOf(HealthReportAnalysisCallException.class);
        assertThat(applicationLogAppender.list)
                .anySatisfy(event -> assertThat(event.getFormattedMessage())
                        .contains("没有任何 data 行"));
    }

    /** SSE 流式下 finish_reason 缺失或非 stop 同样整次失败。 */
    @Test
    void streamedResponseWithoutStopMustFail() {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"{}\"}}]}\n\n"
                + "data: [DONE]\n\n";
        wireMockServer.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "text/event-stream").withBody(sse)));
        HealthReportAnalysisModelProperties streamProperties = properties(1024 * 1024, 1024 * 1024, 1000);
        streamProperties.setStreamEnabled(true);

        assertThatThrownBy(() -> client(streamProperties)
                .call(input(Collections.singletonList(page(1, new byte[]{9})))))
                .isInstanceOf(HealthReportAnalysisCallException.class);
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
