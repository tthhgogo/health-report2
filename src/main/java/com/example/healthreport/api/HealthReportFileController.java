package com.example.healthreport.api;

import com.example.healthreport.api.dto.FileUploadResponse;
import com.example.healthreport.infra.CurrentUserProvider;
import com.example.healthreport.task.FileUploadService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 体检报告单文件上传接口。 */
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
	@PostMapping(value = "/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public FileUploadResponse upload(@RequestParam("file") MultipartFile multipartFile) {
		String fileId = fileUploadService.upload(multipartFile, currentUserProvider.currentUserId(),
				currentUserProvider.currentCompanyId());
		return new FileUploadResponse(fileId);
	}

}
