package com.example.healthreport.api;

import com.example.healthreport.api.dto.TaskStatusResponse;
import com.example.healthreport.infra.CurrentUserProvider;
import com.example.healthreport.task.TaskDeleteService;
import com.example.healthreport.task.TaskQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 任务状态轮询与用户删除接口。 */
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
	@GetMapping("/{taskId}")
	public TaskStatusResponse getTask(@PathVariable("taskId") String taskId) {
		return taskQueryService.getStatus(taskId, currentUserProvider.currentUserId(),
				currentUserProvider.currentCompanyId());
	}

	/** 设置不可逆删除标志并清理全部关联存储，不中断运行中的 Worker。 */
	@DeleteMapping("/{taskId}")
	public ResponseEntity<Void> deleteTask(@PathVariable("taskId") String taskId) {
		taskDeleteService.delete(taskId, currentUserProvider.currentUserId(), currentUserProvider.currentCompanyId());
		return ResponseEntity.noContent().build();
	}

}
