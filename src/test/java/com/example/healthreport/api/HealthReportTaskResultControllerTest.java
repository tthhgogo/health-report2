package com.example.healthreport.api;

import com.example.healthreport.api.dto.TaskStatusResponse;
import com.example.healthreport.cache.AnalysisModules;
import com.example.healthreport.cache.AnalysisResult;
import com.example.healthreport.constants.ResponseCodes;
import com.example.healthreport.infra.CurrentUserProvider;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.BusinessException;
import com.example.healthreport.task.DegradeAccumulator;
import com.example.healthreport.task.TaskDeleteService;
import com.example.healthreport.task.TaskQueryService;
import com.example.healthreport.task.TaskStage;
import com.example.healthreport.task.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.nullValue;

/** 两个 GET 与 DELETE 的 HTTP 契约测试。 */
class HealthReportTaskResultControllerTest {

	private static final String TASK_ID = "123e4567-e89b-12d3-a456-426614174000";

	private static final String USER_ID = "CaseSensitiveUser";

	private static final String COMPANY_ID = "company-a";

	private MockMvc mockMvc;

	private TaskQueryService queryService;

	private TaskDeleteService deleteService;

	@BeforeEach
	void setUp() {
		queryService = mock(TaskQueryService.class);
		deleteService = mock(TaskDeleteService.class);
		CurrentUserProvider userProvider = mock(CurrentUserProvider.class);
		when(userProvider.currentUserId()).thenReturn(USER_ID);
		when(userProvider.currentCompanyId()).thenReturn(COMPANY_ID);
		mockMvc = MockMvcBuilders
			.standaloneSetup(new HealthReportTaskController(queryService, deleteService, userProvider),
					new HealthReportResultController(queryService, userProvider))
			.setControllerAdvice(new HealthReportExceptionHandler())
			.build();
	}

	@Test
	void taskEndpointShouldReturnQueuedWithoutResultPayload() throws Exception {
		when(queryService.getStatus(TASK_ID, USER_ID, COMPANY_ID))
			.thenReturn(new TaskStatusResponse(TaskStatus.QUEUED.name(), TaskStage.UPLOADING.name(), 0, null, false));

		mockMvc.perform(get("/api/health-report/task/{taskId}", TASK_ID))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.retCode").value(ResponseCodes.SUCCESS_CODE))
			.andExpect(jsonPath("$.data.status").value("QUEUED"))
			.andExpect(jsonPath("$.data.stage").value("UPLOADING"))
			.andExpect(jsonPath("$.data.progress").value(0))
			.andExpect(jsonPath("$.data.modules").doesNotExist());
	}

	@Test
	void resultEndpointShouldExposeAllDegradeAndPageFields() throws Exception {
		AnalysisResult result = AnalysisResult.create(new DegradeAccumulator(), 0, 0, AnalysisModules.empty());
		when(queryService.getResult(TASK_ID, USER_ID, COMPANY_ID)).thenReturn(result);

		mockMvc.perform(get("/api/health-report/result/{taskId}", TASK_ID))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.retCode").value(ResponseCodes.SUCCESS_CODE))
			.andExpect(jsonPath("$.data.partial").value(false))
			.andExpect(jsonPath("$.data.partialReason").doesNotExist())
			.andExpect(jsonPath("$.data.suppressDishRecommend").value(false))
			.andExpect(jsonPath("$.data.processedPages").value(0))
			.andExpect(jsonPath("$.data.totalPages").value(0));
	}

	@Test
	void unfinishedResultShouldReturnConflictCode() throws Exception {
		doThrow(new BusinessException(FailCode.TASK_NOT_FINISHED)).when(queryService)
			.getResult(TASK_ID, USER_ID, COMPANY_ID);

		mockMvc.perform(get("/api/health-report/result/{taskId}", TASK_ID))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.retCode").value(ResponseCodes.DEFAULT_ERROR_CODE))
			.andExpect(jsonPath("$.retMsg").value("TASK_NOT_FINISHED"))
			.andExpect(jsonPath("$.data").value(nullValue()));
	}

	/** 删除成功也走统一信封：200 + 成功码 + data 为 null，前端不再按 204 分支。 */
	@Test
	void deleteEndpointShouldReturnSuccessEnvelopeWithNullData() throws Exception {
		mockMvc.perform(delete("/api/health-report/task/{taskId}", TASK_ID))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.retCode").value(ResponseCodes.SUCCESS_CODE))
			.andExpect(jsonPath("$.data").value(nullValue()));
		verify(deleteService).delete(TASK_ID, USER_ID, COMPANY_ID);
	}

}
