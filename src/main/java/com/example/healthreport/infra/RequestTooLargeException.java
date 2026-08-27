package com.example.healthreport.infra;

import lombok.Getter;

/** 请求体超限；异常只携带字节数，不携带报告内容。 */
@Getter
public class RequestTooLargeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long attemptedBytes;
    private final int maxBytes;

    public RequestTooLargeException(long attemptedBytes, int maxBytes) {
        super("LLM-A 请求体超限：" + attemptedBytes + " > " + maxBytes);
        this.attemptedBytes = attemptedBytes;
        this.maxBytes = maxBytes;
    }
}
