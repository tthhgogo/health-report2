package com.example.healthreport.api;

import com.example.healthreport.api.dto.CommonResponse;
import com.example.healthreport.cache.AnalysisResult;
import com.example.healthreport.infra.CurrentUserProvider;
import com.example.healthreport.task.TaskQueryService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 已成功任务的四模块结果读取接口。 */
@Api(tags = "体检报告分析结果", produces = MediaType.APPLICATION_JSON_VALUE)
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
	@ApiOperation(value = "读取四模块分析结果", notes = "MySQL 判定任务成功后才读取 Redis 结果；"
			+ "任务未成功时 retMsg 为 TASK_NOT_FINISHED 等原错误码，结果缓存 TTL 为 2 小时。"
			+ "响应统一为 CommonResponse，data 为 AnalysisResult，失败时 data 为 null。",
			httpMethod = "GET", response = CommonResponse.class,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@GetMapping("/{taskId}")
	public CommonResponse<AnalysisResult> getResult(
			@ApiParam(name = "taskId", value = "分析任务 ID，由创建分析任务接口返回", required = true,
					example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable("taskId") String taskId) {
		return CommonResponse.successWithData(taskQueryService.getResult(taskId,
				currentUserProvider.currentUserId(), currentUserProvider.currentCompanyId()));
	}

}
