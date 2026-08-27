package com.example.healthreport.infra;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** HTTP 错误体零读取与成功响应硬上限的无端口安全测试。 */
class BoundedHttpSafetyTest {

    @Test
    void statusHandlerMustNeverOpenErrorBody() throws Exception {
        StatusOnlyErrorHandler handler = new StatusOnlyErrorHandler();
        ClientHttpResponse response = response(500, new byte[]{1}, true);

        assertThat(handler.hasError(response)).isTrue();
        assertThatThrownBy(() -> handler.handleError(response))
                .isInstanceOfSatisfying(LlmCallException.class,
                        exception -> assertThat(exception.getHttpStatus()).isEqualTo(500));
    }

    @Test
    void boundedExtractorShouldAcceptBoundaryAndRejectNextByte() throws Exception {
        BoundedResponseExtractor extractor = new BoundedResponseExtractor(8);

        assertThat(extractor.extractData(response(200,
                "12345678".getBytes(StandardCharsets.UTF_8), false))).isEqualTo("12345678");
        assertThatThrownBy(() -> extractor.extractData(response(200,
                "123456789".getBytes(StandardCharsets.UTF_8), false)))
                .isInstanceOf(ResponseTooLargeException.class);
    }

    private ClientHttpResponse response(final int status, final byte[] body,
                                        final boolean rejectBodyAccess) {
        return new ClientHttpResponse() {
            @Override
            public HttpStatus getStatusCode() throws IOException {
                return HttpStatus.valueOf(status);
            }

            @Override
            public int getRawStatusCode() throws IOException {
                return status;
            }

            @Override
            public String getStatusText() throws IOException {
                return String.valueOf(status);
            }

            @Override
            public void close() {
                // 测试响应没有外部资源。
            }

            @Override
            public InputStream getBody() throws IOException {
                if (rejectBodyAccess) {
                    throw new AssertionError("错误处理器不得读取响应体");
                }
                return new ByteArrayInputStream(body);
            }

            @Override
            public HttpHeaders getHeaders() {
                return new HttpHeaders();
            }
        };
    }
}
