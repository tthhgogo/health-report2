package com.example.healthreport.infra;

import com.example.healthreport.support.FailCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;

import java.io.IOException;

/**
 * 只读取 HTTP 状态码的错误处理器。
 * <p>绝不访问错误响应 body，防止默认错误处理器绕过响应容量上限。</p>
 */
public class StatusOnlyErrorHandler implements ResponseErrorHandler {

    @Override
    public boolean hasError(ClientHttpResponse response) throws IOException {
        int series = response.getRawStatusCode() / 100;
        return series == 4 || series == 5;
    }

    @Override
    public void handleError(ClientHttpResponse response) throws IOException {
        int status = response.getRawStatusCode();
        throw new LlmCallException(FailCode.SERVER_ERROR, status, 0L);
    }
}
