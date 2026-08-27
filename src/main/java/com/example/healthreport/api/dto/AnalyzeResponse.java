package com.example.healthreport.api.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 分析任务创建成功响应。 */
@Getter
@AllArgsConstructor
public class AnalyzeResponse {

    private final String taskId;
}
