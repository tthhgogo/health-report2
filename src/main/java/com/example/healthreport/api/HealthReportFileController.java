package com.example.healthreport.api;

import com.example.healthreport.api.dto.CommonResponse;
import com.example.healthreport.api.dto.FileUploadResponse;
import com.example.healthreport.infra.CurrentUserProvider;
import com.example.healthreport.task.FileUploadService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 体检报告单文件上传接口。 */
@Api(tags = "体检报告文件上传", produces = MediaType.APPLICATION_JSON_VALUE)
@RestController
@RequestMapping("/api/health-report")
public class HealthReportFileController {

	private final FileUploadService fileUploadService;

	private final CurrentUserProvider currentUserProvider;

	public HealthReportFileController(FileUploadService fileUploadService, CurrentUserProvider currentUserProvider) {
		this.fileUploadService = fileUploadService;
		this.currentUserProvider = currentUserProvider;
	}

	/** 校验并上传单个文件，不创建分析任务。 */
	@ApiOperation(value = "上传单个体检报告文件", notes = "校验格式与大小后上传单个文件并返回 fileId，"
			+ "不创建分析任务；fileId 随后作为创建分析任务请求的入参。"
			+ "响应统一为 CommonResponse，data 为 FileUploadResponse，失败时 data 为 null。",
			httpMethod = "POST", response = CommonResponse.class,
			consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	@PostMapping(value = "/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public CommonResponse<FileUploadResponse> upload(
			@ApiParam(name = "file", value = "体检报告文件，单文件上传；支持格式与大小限制见开发方案 §3", required = true)
			@RequestParam("file") MultipartFile multipartFile) {
		String fileId = fileUploadService.upload(multipartFile, currentUserProvider.currentUserId(),
				currentUserProvider.currentCompanyId());
		return CommonResponse.successWithData(new FileUploadResponse(fileId));
	}

}
