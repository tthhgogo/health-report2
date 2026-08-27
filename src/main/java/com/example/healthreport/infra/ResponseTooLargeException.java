package com.example.healthreport.infra;

import lombok.Getter;

/** 响应体超限；异常只携带上限，不携带模型响应。 */
@Getter
public class ResponseTooLargeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int maxBytes;

    public ResponseTooLargeException(int maxBytes) {
        super("LLM-A 响应体超过上限 " + maxBytes + " 字节");
        this.maxBytes = maxBytes;
    }
}
