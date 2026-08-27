package com.example.healthreport.support;

import lombok.Getter;

/**
 * 可安全返回给上传与建任务接口的业务异常。
 * <p>异常只携带失败码、HTTP 状态和可选任务 ID，不携带文件名、报告内容或外部响应。</p>
 */
@Getter
public class HealthReportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final FailCode failCode;
    private final int httpStatus;
    private final String taskId;

    /**
     * 创建不带任务 ID 的业务异常。
     */
    public HealthReportException(FailCode failCode, int httpStatus) {
        this(failCode, httpStatus, null, null);
    }

    /**
     * 创建可返回已绑定任务 ID 的业务异常。
     */
    public HealthReportException(FailCode failCode, int httpStatus, String taskId) {
        this(failCode, httpStatus, taskId, null);
    }

    /**
     * 创建包装底层异常的业务异常；消息不拼接底层异常内容，避免敏感数据外泄。
     */
    public HealthReportException(FailCode failCode, int httpStatus, Throwable cause) {
        this(failCode, httpStatus, null, cause);
    }

    private HealthReportException(FailCode failCode, int httpStatus, String taskId, Throwable cause) {
        super(failCode.name(), cause);
        this.failCode = failCode;
        this.httpStatus = httpStatus;
        this.taskId = taskId;
    }
}
