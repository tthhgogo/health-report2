package com.example.healthreport.infra;

/** 请求体超限；异常只携带字节数（进消息），不携带报告内容。 */
public class RequestTooLargeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RequestTooLargeException(long attemptedBytes, int maxBytes) {
        super("模型请求体超限：" + attemptedBytes + " > " + maxBytes);
    }
}
