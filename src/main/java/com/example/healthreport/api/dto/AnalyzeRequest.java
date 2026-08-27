package com.example.healthreport.api.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.List;

/** 创建分析任务请求；fileIds 顺序即文件展示与处理顺序。 */
@Data
public class AnalyzeRequest {

    @NotNull
    @Size(min = 1, max = 5)
    private List<@NotBlank String> fileIds;
}
