package com.example.healthreport.api.dto;

import com.example.healthreport.support.FailCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** 任务轮询响应，只包含状态与进度，不包含分析结果。 */
@Getter
@AllArgsConstructor
public class TaskStatusResponse {

    private final String status;
    private final String stage;
    private final int progress;
    private final FailCode failCode;
    private final boolean reanalyzable;
}
