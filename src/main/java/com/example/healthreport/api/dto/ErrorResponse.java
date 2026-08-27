package com.example.healthreport.api.dto;

import lombok.Getter;

/**
 * 统一错误响应；FILE_ALREADY_BOUND 时附带可继续轮询的已绑定 taskId。
 */
@Getter
public class ErrorResponse {

    private final String code;
    private final String taskId;

    public ErrorResponse(String code, String taskId) {
        this.code = code;
        this.taskId = taskId;
    }
}
