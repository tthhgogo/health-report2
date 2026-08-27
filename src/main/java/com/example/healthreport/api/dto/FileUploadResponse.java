package com.example.healthreport.api.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 单文件上传成功响应。 */
@Getter
@AllArgsConstructor
public class FileUploadResponse {

    private final String fileId;
}
