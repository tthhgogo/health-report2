package com.example.healthreport.infra;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.healthreport.parse.ImageTooLargeException;
import com.example.healthreport.parse.OcrProperties;
import com.example.healthreport.parse.OcrRequestEncoding;
import com.example.healthreport.parse.ocr.OcrBlock;
import com.example.healthreport.parse.ocr.OcrResult;
import com.example.healthreport.support.FailCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** OCR 直连的真实 HTTP 层验证：请求结构、坐标缺席、脱敏、零重试与容量兜底。 */
class PaddleOcrVlClientTest {

    private static final String PATH = "/v1/chat/completions";
    private static final String PRIVATE_RESPONSE_MARKER = "PRIVATE_OCR_TEXT_MARKER";

    private WireMockServer wireMockServer;
    private ObjectMapper objectMapper;
    private Logger applicationLogger;
    private Level previousLogLevel;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void startServer() {
        wireMockServer = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
        wireMockServer.start();
        objectMapper = new ObjectMapper();
        applicationLogger = (Logger) LoggerFactory.getLogger("com.example.healthreport");
        previousLogLevel = applicationLogger.getLevel();
        logAppender = new ListAppender<ILoggingEvent>();
        logAppender.start();
        applicationLogger.addAppender(logAppender);
        applicationLogger.setLevel(Level.ALL);
    }

    @AfterEach
    void stopServer() {
        applicationLogger.detachAppender(logAppender);
        applicationLogger.setLevel(previousLogLevel);
        logAppender.stop();
        if (wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

    @Test
    void shouldSendSingleUserMessageWithInlinedDataUriAndZeroTemperature() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(completion("血脂检查\n甘油三酯 1.85"))));

        OcrResult result = newClient().recognize(pngBytes());

        String requestBody = wireMockServer.findAll(postRequestedFor(urlEqualTo(PATH)))
                .get(0).getBodyAsString();
        JsonNode root = objectMapper.readTree(requestBody);
        assertThat(root.path("model").asText()).isEqualTo("test-ocr-model");
        assertThat(root.path("temperature").asInt()).isZero();
        assertThat(root.path("messages").size()).isEqualTo(1);
        assertThat(root.path("messages").path(0).path("role").asText()).isEqualTo("user");
        JsonNode content = root.path("messages").path(0).path("content");
        assertThat(content.size()).isEqualTo(2);
        assertThat(content.path(0).path("type").asText()).isEqualTo("image_url");
        assertThat(content.path(0).path("image_url").path("url").asText())
                .startsWith("data:image/png;base64,");
        assertThat(content.path(1).path("text").asText())
                .isEqualTo(PaddleOcrVlClient.TRANSCRIBE_INSTRUCTION);
        assertThat(result.getBlockList()).extracting(OcrBlock::getRawText)
                .containsExactly("血脂检查", "甘油三酯 1.85");
    }

    @Test
    void everyBlockShouldHaveNullBboxBecauseProtocolCarriesNoCoordinates() {
        wireMockServer.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(completion("第一行\n\n  \n第二行"))));

        OcrResult result = newClient().recognize(jpegBytes());

        assertThat(result.getBlockList()).hasSize(2);
        assertThat(result.getBlockList()).allSatisfy(block ->
                assertThat(block.getBbox()).isNull());
        assertThat(result.getImageWidth()).isNull();
        assertThat(result.getImageHeight()).isNull();
    }

    @Test
    void shouldRejectOversizedImageBeforeSendingAnyRequest() {
        OcrProperties properties = new OcrProperties();
        properties.setMaxEncodedImageBytes(8L);
        properties.setMaxRequestBodyBytes(12L * 1024L * 1024L);
        properties.setRequestEncoding(OcrRequestEncoding.JSON_BASE64);
        properties.setAcceptsEncodedBytes(Boolean.TRUE);
        properties.setAppliesExifOrientation(Boolean.TRUE);
        properties.setReturnsImageDimensions(Boolean.FALSE);
        properties.afterPropertiesSet();

        assertThatThrownBy(() -> newClient(properties).recognize(pngBytes()))
                .isInstanceOf(ImageTooLargeException.class);
        assertThat(wireMockServer.findAll(postRequestedFor(urlEqualTo(PATH)))).isEmpty();
    }

    @Test
    void shouldRejectBytesThatAreNeitherPngNorJpeg() {
        byte[] notAnImage = "PKnot-an-image".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> newClient().recognize(notAnImage))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(wireMockServer.findAll(postRequestedFor(urlEqualTo(PATH)))).isEmpty();
    }

    @Test
    void shouldFailOnceWithoutRetryAndWithoutLeakingResponseBody() {
        wireMockServer.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"" + PRIVATE_RESPONSE_MARKER + "\"}")));

        assertThatThrownBy(() -> newClient().recognize(pngBytes()))
                .isInstanceOf(OcrCallException.class)
                .satisfies(thrown -> {
                    OcrCallException exception = (OcrCallException) thrown;
                    assertThat(exception.getFailCode()).isEqualTo(FailCode.SERVER_ERROR);
                    assertThat(exception.getHttpStatus()).isEqualTo(500);
                });
        assertThat(wireMockServer.findAll(postRequestedFor(urlEqualTo(PATH)))).hasSize(1);
        assertThat(renderedLog()).doesNotContain(PRIVATE_RESPONSE_MARKER);
    }

    @Test
    void shouldRejectResponseWithoutTextualContentAndNeverLogIt() {
        wireMockServer.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"choices\":[{\"message\":{\"content\":{\"nested\":\""
                        + PRIVATE_RESPONSE_MARKER + "\"}}}]}")));

        assertThatThrownBy(() -> newClient().recognize(pngBytes()))
                .isInstanceOf(OcrCallException.class);
        assertThat(renderedLog()).doesNotContain(PRIVATE_RESPONSE_MARKER);
    }

    @Test
    void recognizedTextShouldNeverReachTheLog() {
        wireMockServer.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(completion(PRIVATE_RESPONSE_MARKER))));

        OcrResult result = newClient().recognize(pngBytes());

        assertThat(result.getBlockList()).hasSize(1);
        assertThat(renderedLog()).doesNotContain(PRIVATE_RESPONSE_MARKER);
    }

    private String completion(String content) {
        try {
            return "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"model\":\"test-ocr-model\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":"
                    + objectMapper.writeValueAsString(content)
                    + "},\"finish_reason\":\"stop\"}]}";
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String renderedLog() {
        StringBuilder rendered = new StringBuilder();
        for (ILoggingEvent event : logAppender.list) {
            rendered.append(event.getFormattedMessage()).append('\n');
        }
        return rendered.toString();
    }

    private PaddleOcrVlClient newClient() {
        return newClient(validProperties());
    }

    private PaddleOcrVlClient newClient(OcrProperties ocrProperties) {
        OcrConnectionProperties connectionProperties = new OcrConnectionProperties();
        connectionProperties.setBaseUrl("http://127.0.0.1:" + wireMockServer.port());
        connectionProperties.setModel("test-ocr-model");
        connectionProperties.setApiKey("test-ocr-api-key");
        connectionProperties.setConnectTimeoutMillis(2000);
        connectionProperties.setReadTimeoutMillis(4000);
        return new PaddleOcrVlClient(objectMapper, connectionProperties, ocrProperties);
    }

    private OcrProperties validProperties() {
        OcrProperties properties = new OcrProperties();
        properties.setMaxEncodedImageBytes(8L * 1024L * 1024L);
        properties.setMaxRequestBodyBytes(12L * 1024L * 1024L);
        properties.setRequestEncoding(OcrRequestEncoding.JSON_BASE64);
        properties.setAcceptsEncodedBytes(Boolean.TRUE);
        properties.setAppliesExifOrientation(Boolean.TRUE);
        properties.setReturnsImageDimensions(Boolean.FALSE);
        properties.afterPropertiesSet();
        return properties;
    }

    private byte[] pngBytes() {
        byte[] header = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        return Arrays.copyOf(header, 512);
    }

    private byte[] jpegBytes() {
        byte[] header = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        return Arrays.copyOf(header, 512);
    }
}
