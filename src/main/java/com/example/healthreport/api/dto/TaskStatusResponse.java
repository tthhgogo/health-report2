package com.example.healthreport.api.dto;

import com.example.healthreport.support.FailCode;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** 任务轮询响应，只包含状态与进度，不包含分析结果。 */
@ApiModel(value = "TaskStatusResponse", description = "任务轮询响应；只含状态与进度，不含分析结果")
@Getter
@AllArgsConstructor
public class TaskStatusResponse {

    @ApiModelProperty(value = "任务状态，取值见 TaskStatus 枚举", required = true,
            allowableValues = "QUEUED,PARSING,EXTRACTING,ASSEMBLING,SUCCEEDED,FAILED",
            example = "EXTRACTING")
    private final String status;

    @ApiModelProperty(value = "前端展示的三阶段进度标识，取值见 TaskStage 枚举",
            allowableValues = "UPLOADING,PARSING,ASSEMBLING", example = "PARSING")
    private final String stage;

    @ApiModelProperty(value = "进度百分比，0~100", required = true, example = "60")
    private final int progress;

    @ApiModelProperty(value = "任务失败原因；仅 FAILED 时非 null", example = "PAGE_LIMIT_EXCEEDED")
    private final FailCode failCode;

    @ApiModelProperty(value = "失败后是否允许重新发起分析", required = true, example = "true")
    private final boolean reanalyzable;
}
