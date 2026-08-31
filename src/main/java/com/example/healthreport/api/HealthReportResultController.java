package com.example.healthreport.api;

import com.example.healthreport.cache.AnalysisResult;
import com.example.healthreport.infra.CurrentUserProvider;
import com.example.healthreport.task.TaskQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 已成功任务的四模块结果读取接口。 */
@RestController
@RequestMapping("/api/health-report/result")
public class HealthReportResultController {

	private final TaskQueryService taskQueryService;

	private final CurrentUserProvider currentUserProvider;

	public HealthReportResultController(TaskQueryService taskQueryService, CurrentUserProvider currentUserProvider) {
		this.taskQueryService = taskQueryService;
		this.currentUserProvider = currentUserProvider;
	}

	/** MySQL 判定任务成功后才读取 Redis 结果。 */
	@GetMapping("/{taskId}")
	public AnalysisResult getResult(@PathVariable("taskId") String taskId) {
		return taskQueryService.getResult(taskId, currentUserProvider.currentUserId(),
				currentUserProvider.currentCompanyId());
	}

}
