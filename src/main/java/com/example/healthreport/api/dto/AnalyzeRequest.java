package com.example.healthreport.api.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.List;

/** 创建分析任务请求；fileIds 顺序即文件展示与处理顺序。 */
@ApiModel(value = "AnalyzeRequest", description = "创建体检报告分析任务请求")
@Data
public class AnalyzeRequest {

    @ApiModelProperty(value = "已上传文件的 fileId 列表，1~5 个；顺序即文件展示与处理顺序",
            required = true, example = "[\"9f8e7d6c-5b4a-3210-fedc-ba9876543210\"]")
    @NotNull
    @Size(min = 1, max = 5)
    private List<@NotBlank String> fileIds;
}
