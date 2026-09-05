package com.example.healthreport.api.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** 单文件上传成功响应。 */
@ApiModel(value = "FileUploadResponse", description = "单文件上传成功响应")
@Getter
@AllArgsConstructor
public class FileUploadResponse {

    @ApiModelProperty(value = "文件 ID；作为创建分析任务请求 fileIds 的元素", required = true,
            example = "9f8e7d6c-5b4a-3210-fedc-ba9876543210")
    private final String fileId;
}
