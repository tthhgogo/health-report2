package com.example.healthreport.infra;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.healthreport.llm.dishtag.DishTagProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** LLM-B 直连的真实 HTTP 层验证：消息结构、思考段透传、截断拒绝与零重试。 */
class OpenAiCompatibleDishTagClientTest {

    private static final String PATH = "/v1/chat/completions";

    private WireMockServer wireMockServer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void startServer() {
        wireMockServer = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
        wireMockServer.start();
        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void stopServer() {
        if (wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

    @Test
    void shouldSendTwoMessagesWithZeroTemperatureAndNoUserField() throws Exception {
        stubContent("stop", "{\"enumKey\":\"LOW_FAT\"}");

        newClient().call("提示词正文", "【本批维度】\nenumKey: LOW_FAT");

        JsonNode root = objectMapper.readTree(
                wireMockServer.findAll(postRequestedFor(urlEqualTo(PATH))).get(0).getBodyAsString());
        assertThat(root.path("model").asText()).isEqualTo("test-dishtag-model");
        assertThat(root.path("temperature").asInt()).isZero();
        assertThat(root.path("max_tokens").asInt()).isEqualTo(4096);
        assertThat(root.path("messages").size()).isEqualTo(2);
        assertThat(root.path("messages").path(0).path("role").asText()).isEqualTo("system");
        assertThat(root.path("messages").path(0).path("content").asText()).isEqualTo("提示词正文");
        assertThat(root.path("messages").path(1).path("role").asText()).isEqualTo("user");
        // 本链路没有用户，打标按菜品维度离线跑、结果跨用户复用。
        assertThat(root.has("user")).isFalse();
        // 网关是否支持 json_object 未确认，最终保证在 Java 校验层。
        assertThat(root.has("response_format")).isFalse();
    }

    @Test
    void shouldReturnContentWithThinkSegmentUntouched() {
        String content = "<think>\n想想\n</think>\n\n{\"enumKey\":\"LOW_FAT\"}";
        stubContent("stop", content);

        // 客户端不剥离，原样返回；剥离是 ThinkSegmentStripper 的职责，两者不重叠。
        assertThat(newClient().call("提示词", "批次")).isEqualTo(content);
    }

    @Test
    void shouldLogUrlAtInfoAndRequestBodyOnlyAtDebugWithoutApiKey() {
        stubContent("stop", "{\"enumKey\":\"LOW_FAT\"}");
        Logger logger = (Logger) LoggerFactory.getLogger(OpenAiCompatibleDishTagClient.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            logger.setLevel(Level.INFO);
            newClient().call("INFO_SYSTEM_MARKER", "INFO_USER_MARKER");

            assertThat(appender.list)
                    .filteredOn(event -> event.getFormattedMessage().startsWith("LLM-B 调用开始"))
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.INFO);
                        assertThat(event.getFormattedMessage()).contains(
                                "url=http://127.0.0.1:" + wireMockServer.port() + PATH);
                    });
            assertThat(renderedLog(appender))
                    .doesNotContain("INFO_SYSTEM_MARKER", "INFO_USER_MARKER", "test-dishtag-api-key");

            appender.list.clear();
            logger.setLevel(Level.DEBUG);
            newClient().call("DEBUG_SYSTEM_MARKER", "DEBUG_USER_MARKER");

            assertThat(appender.list)
                    .filteredOn(event -> event.getFormattedMessage().startsWith("LLM-B 请求正文"))
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                        assertThat(event.getFormattedMessage())
                                .contains("DEBUG_SYSTEM_MARKER", "DEBUG_USER_MARKER")
                                .doesNotContain("test-dishtag-api-key");
                    });
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    @Test
    void nonStopFinishReasonShouldFailBecauseTruncatedOutputIsNotTrustworthy() {
        stubContent("length", "<think>\n还在想");

        assertThatThrownBy(() -> newClient().call("提示词", "批次"))
                .isInstanceOf(DishTagModelCallException.class);
    }

    @Test
    void oversizedRequestShouldFailBeforeSendingAnything() {
        StringBuilder huge = new StringBuilder();
        for (int index = 0; index < 3000; index++) {
            huge.append("超长食材名超长食材名超长食材名");
        }

        assertThatThrownBy(() -> newClient(4096).call("提示词", huge.toString()))
                .isInstanceOf(RequestTooLargeException.class);
        // 超限在【写入过程中】就抛，不会先把整个请求体生成出来再判。
        assertThat(wireMockServer.findAll(postRequestedFor(urlEqualTo(PATH)))).isEmpty();
    }

    @Test
    void serverErrorShouldFailOnceWithoutRetry() {
        wireMockServer.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(500).withHeader("Content-Type", "application/json").withBody("{}")));

        assertThatThrownBy(() -> newClient().call("提示词", "批次"))
                .isInstanceOf(DishTagModelCallException.class)
                .satisfies(thrown -> assertThat(
                        ((DishTagModelCallException) thrown).getHttpStatus()).isEqualTo(500));
        assertThat(wireMockServer.findAll(postRequestedFor(urlEqualTo(PATH)))).hasSize(1);
    }

    private void stubContent(String finishReason, String content) {
        try {
            wireMockServer.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                            + "\"content\":" + objectMapper.writeValueAsString(content)
                            + "},\"finish_reason\":\"" + finishReason + "\"}]}")));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private OpenAiCompatibleDishTagClient newClient() {
        return newClient(1 << 20);
    }

    private OpenAiCompatibleDishTagClient newClient(int maxRequestBodyBytes) {
        DishTagConnectionProperties connectionProperties = new DishTagConnectionProperties();
        connectionProperties.setMaxRequestBodyBytes(maxRequestBodyBytes);
        connectionProperties.setBaseUrl("http://127.0.0.1:" + wireMockServer.port());
        connectionProperties.setApiKey("test-dishtag-api-key");
        connectionProperties.setMaxTokens(4096);
        connectionProperties.setConnectTimeoutMillis(2000);
        connectionProperties.setReadTimeoutMillis(4000);
        DishTagProperties dishTagProperties = new DishTagProperties();
        dishTagProperties.setModelVersionDishtag("test-dishtag-model");
        return new OpenAiCompatibleDishTagClient(objectMapper, connectionProperties, dishTagProperties);
    }

    private String renderedLog(ListAppender<ILoggingEvent> appender) {
        StringBuilder rendered = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            rendered.append(event.getFormattedMessage()).append('\n');
        }
        return rendered.toString();
    }
}
