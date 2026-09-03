package com.example.healthreport.infra;

import com.example.healthreport.support.FailCode;
import lombok.Getter;

/** 体检报告分析模型调用失败；刻意不持有请求体、响应体或可能携带正文的底层异常。 */
@Getter
public class HealthReportAnalysisCallException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final FailCode failCode;
    private final int httpStatus;

    public HealthReportAnalysisCallException(FailCode failCode, int httpStatus) {
        super("体检报告分析模型调用失败，httpStatus=" + httpStatus);
        this.failCode = failCode;
        this.httpStatus = httpStatus;
    }
}
