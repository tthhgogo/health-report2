package com.example.healthreport.api;

import com.example.healthreport.infra.CurrentUserProvider;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import com.example.healthreport.task.AnalysisTaskCreateService;
import com.example.healthreport.task.AnalysisTaskExecutionService;
import com.example.healthreport.task.FileUploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 上传和建任务 HTTP 契约测试。
 */
class HealthReportControllerTest {

	private static final String FILE_ID = "00000000-0000-0000-0000-000000000001";

	private static final String TASK_ID = "00000000-0000-0000-0000-000000000002";

	private static final String USER_ID = "case-sensitive-user";

	private static final String COMPANY_ID = "company-a";

	private MockMvc mockMvc;

	private FileUploadService fileUploadService;

	private AnalysisTaskCreateService taskCreateService;

	private AnalysisTaskExecutionService taskExecutionService;

	@BeforeEach
	void setUp() {
		fileUploadService = mock(FileUploadService.class);
		taskCreateService = mock(AnalysisTaskCreateService.class);
		taskExecutionService = mock(AnalysisTaskExecutionService.class);
		CurrentUserProvider userProvider = mock(CurrentUserProvider.class);
		when(userProvider.currentUserId()).thenReturn(USER_ID);
		when(userProvider.currentCompanyId()).thenReturn(COMPANY_ID);
		mockMvc = MockMvcBuilders
			.standaloneSetup(new HealthReportFileController(fileUploadService, userProvider),
					new HealthReportAnalyzeController(taskCreateService, taskExecutionService, userProvider))
			.setControllerAdvice(new HealthReportExceptionHandler())
			.build();
	}

	@Test
	void shouldExposeUploadAndAnalyzeEndpoints() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "renamed.txt", "text/plain", new byte[] { 1 });
		when(fileUploadService.upload(any(), eq(USER_ID), eq(COMPANY_ID))).thenReturn(FILE_ID);
		when(taskCreateService.createInTransaction(anyList(), eq(USER_ID), eq(COMPANY_ID))).thenReturn(TASK_ID);

		mockMvc.perform(multipart("/api/health-report/file").file(file))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.fileId").value(FILE_ID));
		mockMvc
			.perform(post("/api/health-report/analyze").contentType(MediaType.APPLICATION_JSON)
				.content("{\"fileIds\":[\"" + FILE_ID + "\"]}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.taskId").value(TASK_ID));
		org.mockito.Mockito.verify(taskExecutionService).submit(TASK_ID);
	}

	@Test
	void shouldReturnConflictCodeAndBoundTaskIdWithoutSensitiveMetadata() throws Exception {
		org.mockito.Mockito.doThrow(new HealthReportException(FailCode.FILE_ALREADY_BOUND, 409, TASK_ID))
			.when(taskCreateService)
			.precheck(anyList(), eq(USER_ID), eq(COMPANY_ID));

		mockMvc
			.perform(post("/api/health-report/analyze").contentType(MediaType.APPLICATION_JSON)
				.content("{\"fileIds\":[\"" + FILE_ID + "\"]}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(FailCode.FILE_ALREADY_BOUND.name()))
			.andExpect(jsonPath("$.taskId").value(TASK_ID))
			.andExpect(jsonPath("$.originName").doesNotExist());
	}

	@Test
	void shouldMapMultipartLimitToFileTooLargeWithoutSensitiveMetadata() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "sensitive-name.pdf", "application/pdf",
				new byte[] { 1 });
		when(fileUploadService.upload(any(), eq(USER_ID), eq(COMPANY_ID)))
			.thenThrow(new MaxUploadSizeExceededException(20L * 1024L * 1024L));

		mockMvc.perform(multipart("/api/health-report/file").file(file))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(FailCode.FILE_TOO_LARGE.name()))
			.andExpect(jsonPath("$.taskId").doesNotExist())
			.andExpect(jsonPath("$.originName").doesNotExist());
	}

	/**
	 * 报文畸形与字节超限必须是两个错误码：前者压缩文件重试无用， 复用 FILE_TOO_LARGE 会把用户引到解决不了问题的方向上。
	 */
	@Test
	void shouldMapMalformedMultipartToMalformedRequestNotFileTooLarge() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "sensitive-name.pdf", "application/pdf",
				new byte[] { 1 });
		when(fileUploadService.upload(any(), eq(USER_ID), eq(COMPANY_ID))).thenThrow(new MultipartException("边界符损坏"));

		mockMvc.perform(multipart("/api/health-report/file").file(file))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(FailCode.MALFORMED_REQUEST.name()))
			.andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(FailCode.FILE_TOO_LARGE.name())))
			.andExpect(jsonPath("$.taskId").doesNotExist())
			.andExpect(jsonPath("$.originName").doesNotExist());
	}

}
