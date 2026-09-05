package com.example.healthreport.api.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** 分析任务创建成功响应。 */
@ApiModel(value = "AnalyzeResponse", description = "分析任务创建成功响应")
@Getter
@AllArgsConstructor
public class AnalyzeResponse {

    @ApiModelProperty(value = "分析任务 ID；用于轮询任务状态与读取分析结果", required = true,
            example = "123e4567-e89b-12d3-a456-426614174000")
    private final String taskId;
}
