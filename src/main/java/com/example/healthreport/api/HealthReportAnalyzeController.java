package com.example.healthreport.api;

import com.example.healthreport.api.dto.AnalyzeRequest;
import com.example.healthreport.api.dto.AnalyzeResponse;
import com.example.healthreport.infra.CurrentUserProvider;
import com.example.healthreport.task.AnalysisTaskCreateService;
import com.example.healthreport.task.AnalysisTaskExecutionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/** 体检报告分析任务创建接口。 */
@RestController
@RequestMapping("/api/health-report")
public class HealthReportAnalyzeController {

	private final AnalysisTaskCreateService taskCreateService;

	private final AnalysisTaskExecutionService taskExecutionService;

	private final CurrentUserProvider currentUserProvider;

	public HealthReportAnalyzeController(AnalysisTaskCreateService taskCreateService,
			AnalysisTaskExecutionService taskExecutionService, CurrentUserProvider currentUserProvider) {
		this.taskCreateService = taskCreateService;
		this.taskExecutionService = taskExecutionService;
		this.currentUserProvider = currentUserProvider;
	}

	/** 事务外快速预检后，在事务内创建任务并原子绑定文件。 */
	@PostMapping("/analyze")
	public AnalyzeResponse analyze(@Valid @RequestBody AnalyzeRequest request) {
		String userId = currentUserProvider.currentUserId();
		String companyId = currentUserProvider.currentCompanyId();
		taskCreateService.precheck(request.getFileIds(), userId, companyId);
		String taskId = taskCreateService.createInTransaction(request.getFileIds(), userId, companyId);
		// createInTransaction 返回即代表事务已提交；线程池提交必须严格发生在此后。
		taskExecutionService.submit(taskId);
		return new AnalyzeResponse(taskId);
	}

}
