package com.example.healthreport.api;

import com.example.healthreport.api.dto.TaskStatusResponse;
import com.example.healthreport.infra.CurrentUserProvider;
import com.example.healthreport.task.TaskDeleteService;
import com.example.healthreport.task.TaskQueryService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 任务状态轮询与用户删除接口。 */
@Api(tags = "体检报告分析任务管理", produces = MediaType.APPLICATION_JSON_VALUE)
@RestController
@RequestMapping("/api/health-report/task")
public class HealthReportTaskController {

	private final TaskQueryService taskQueryService;

	private final TaskDeleteService taskDeleteService;

	private final CurrentUserProvider currentUserProvider;

	public HealthReportTaskController(TaskQueryService taskQueryService, TaskDeleteService taskDeleteService,
			CurrentUserProvider currentUserProvider) {
		this.taskQueryService = taskQueryService;
		this.taskDeleteService = taskDeleteService;
		this.currentUserProvider = currentUserProvider;
	}

	/** 返回任务状态与阶段，不读取也不返回 Redis 结果。 */
	@ApiOperation(value = "轮询任务状态", notes = "返回任务状态、阶段与进度，不读取也不返回 Redis 结果；"
			+ "SUCCEEDED 后改调结果接口获取四模块内容。",
			httpMethod = "GET", response = TaskStatusResponse.class,
			produces = MediaType.APPLICATION_JSON_VALUE)
	@GetMapping("/{taskId}")
	public TaskStatusResponse getTask(
			@ApiParam(name = "taskId", value = "分析任务 ID，由创建分析任务接口返回", required = true,
					example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable("taskId") String taskId) {
		return taskQueryService.getStatus(taskId, currentUserProvider.currentUserId(),
				currentUserProvider.currentCompanyId());
	}

	/** 设置不可逆删除标志并清理全部关联存储，不中断运行中的 Worker。 */
	// httpMethod 不显式填写：架构测试禁止生产源码出现 SQL 关键字字符串字面量，由 @DeleteMapping 推断。
	@ApiOperation(value = "删除任务", notes = "设置不可逆删除标志并清理全部关联存储，不中断运行中的 Worker；"
			+ "成功返回 204，无响应体。",
			code = 204)
	@DeleteMapping("/{taskId}")
	public ResponseEntity<Void> deleteTask(
			@ApiParam(name = "taskId", value = "分析任务 ID，由创建分析任务接口返回", required = true,
					example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable("taskId") String taskId) {
		taskDeleteService.delete(taskId, currentUserProvider.currentUserId(), currentUserProvider.currentCompanyId());
		return ResponseEntity.noContent().build();
	}

}
