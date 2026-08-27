package com.example.healthreport.infra;

import lombok.Getter;

/** LLM-B 调用失败；异常只携带状态码与耗时，不携带请求或响应正文。 */
@Getter
public class DishTagModelCallException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int httpStatus;
    private final long elapsedMillis;

    public DishTagModelCallException(int httpStatus, long elapsedMillis) {
        super("LLM-B 调用失败，httpStatus=" + httpStatus);
        this.httpStatus = httpStatus;
        this.elapsedMillis = elapsedMillis;
    }
}
