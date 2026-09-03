package com.example.healthreport.infra;

import lombok.Getter;

/** LLM-B 调用失败；异常只携带状态码，不携带请求或响应正文。 */
@Getter
public class DishTagModelCallException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int httpStatus;

    public DishTagModelCallException(int httpStatus) {
        super("LLM-B 调用失败，httpStatus=" + httpStatus);
        this.httpStatus = httpStatus;
    }
}
