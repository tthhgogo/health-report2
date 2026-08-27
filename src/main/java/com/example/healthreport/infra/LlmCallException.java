package com.example.healthreport.infra;

import com.example.healthreport.support.FailCode;
import lombok.Getter;

/** LLM-A 调用失败；刻意不持有请求体、响应体或可能携带正文的底层异常。 */
@Getter
public class LlmCallException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final FailCode failCode;
    private final int httpStatus;
    private final long elapsedMillis;

    public LlmCallException(FailCode failCode, int httpStatus, long elapsedMillis) {
        super("LLM-A 调用失败，httpStatus=" + httpStatus);
        this.failCode = failCode;
        this.httpStatus = httpStatus;
        this.elapsedMillis = elapsedMillis;
    }
}
