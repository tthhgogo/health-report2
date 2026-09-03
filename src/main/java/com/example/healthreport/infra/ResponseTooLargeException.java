package com.example.healthreport.infra;

/** 响应体超限；异常只携带上限（进消息），不携带模型响应。 */
public class ResponseTooLargeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ResponseTooLargeException(int maxBytes) {
        super("模型响应体超过上限 " + maxBytes + " 字节");
    }
}
