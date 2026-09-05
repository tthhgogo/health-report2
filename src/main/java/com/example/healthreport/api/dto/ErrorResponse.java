package com.example.healthreport.api.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;

/**
 * 统一错误响应；FILE_ALREADY_BOUND 时附带可继续轮询的已绑定 taskId。
 */
@ApiModel(value = "ErrorResponse", description = "统一错误响应")
@Getter
public class ErrorResponse {

    @ApiModelProperty(value = "错误码，取值见 FailCode 枚举", required = true,
            example = "FILE_TOO_LARGE")
    private final String code;

    @ApiModelProperty(value = "仅 FILE_ALREADY_BOUND 时返回：文件已绑定的任务 ID，可直接继续轮询；其余错误为 null",
            example = "123e4567-e89b-12d3-a456-426614174000")
    private final String taskId;

    public ErrorResponse(String code, String taskId) {
        this.code = code;
        this.taskId = taskId;
    }
}
