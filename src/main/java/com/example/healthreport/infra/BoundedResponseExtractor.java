package com.example.healthreport.infra;

import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseExtractor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 成功响应有界读取器，超过上限便中断，不构造完整响应对象。 */
public class BoundedResponseExtractor implements ResponseExtractor<String> {

    private final int maxBytes;

    public BoundedResponseExtractor(int maxBytes) {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("响应体上限必须大于零");
        }
        this.maxBytes = maxBytes;
    }

    @Override
    public String extractData(ClientHttpResponse response) throws IOException {
        CappedByteArrayOutputStream output = new CappedByteArrayOutputStream(
                Math.min(1 << 16, maxBytes), maxBytes);
        byte[] chunk = new byte[8192];
        InputStream input = response.getBody();
        int read;
        try {
            while ((read = input.read(chunk)) != -1) {
                output.write(chunk, 0, read);
            }
        } catch (RequestTooLargeException exception) {
            throw new ResponseTooLargeException(maxBytes);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
