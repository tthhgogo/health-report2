package com.example.healthreport.task;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.example.healthreport.assemble.AnalysisAssembleService;
import com.example.healthreport.cache.AnalysisResult;
import com.example.healthreport.cache.AnalysisModules;
import com.example.healthreport.llm.extraction.ExtractionStageService;
import com.example.healthreport.llm.extraction.ValidatedExtractionOutput;
import com.example.healthreport.llm.extraction.ValidatedExtractionOutputTestFactory;
import com.example.healthreport.parse.ContentType;
import com.example.healthreport.parse.ImageTooLargeException;
import com.example.healthreport.parse.ParsedPage;
import com.example.healthreport.parse.segment.Segment;
import com.example.healthreport.parse.segment.TextSource;
import com.example.healthreport.parse.ParseOrchestrator;
import com.example.healthreport.parse.ParsePlan;
import com.example.healthreport.parse.ParsedFile;
import com.example.healthreport.persistence.CtHealthReportTaskEntity;
import com.example.healthreport.persistence.CtHealthReportTaskService;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

/** R35：删除后领取 CAS 影响零行时 Worker 必须无副作用退出。 */
class AnalysisTaskWorkerTest {

	@Test
	void failedClaimShouldDoNothingAndWriteNoTerminalState() {
		String taskId = "123e4567-e89b-12d3-a456-426614174000";
		TaskStateService stateService = mock(TaskStateService.class);
		TaskResourceCleanupService cleanupService = mock(TaskResourceCleanupService.class);
		TaskParseService parseService = mock(TaskParseService.class);
		ParseOrchestrator orchestrator = mock(ParseOrchestrator.class);
		ExtractionStageService extractionStage = mock(ExtractionStageService.class);
		AnalysisAssembleService assembleService = mock(AnalysisAssembleService.class);
		ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
		AnalysisExecutorProperties properties = new AnalysisExecutorProperties();
		when(stateService.claim(taskId)).thenReturn(false);
		AnalysisTaskWorker worker = new AnalysisTaskWorker(stateService, taskService(taskId), cleanupService,
				parseService, orchestrator, extractionStage, assembleService, scheduler, properties);

		worker.run(taskId);

		verify(stateService).claim(taskId);
		verify(stateService, never()).markFailed(taskId, com.example.healthreport.support.FailCode.SERVER_ERROR);
		verify(cleanupService, never()).deleteFiles(taskId);
	}

	@Test
	void successfulFlowShouldCleanOriginalFilesAfterSuccessCommit() {
		String taskId = "123e4567-e89b-12d3-a456-426614174000";
		TaskStateService stateService = mock(TaskStateService.class);
		TaskResourceCleanupService cleanupService = mock(TaskResourceCleanupService.class);
		TaskParseService parseService = mock(TaskParseService.class);
		ParseOrchestrator orchestrator = mock(ParseOrchestrator.class);
		ExtractionStageService extractionStage = mock(ExtractionStageService.class);
		AnalysisAssembleService assembleService = mock(AnalysisAssembleService.class);
		ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
		@SuppressWarnings("unchecked")
		ScheduledFuture<Object> heartbeatFuture = mock(ScheduledFuture.class);
		doReturn(heartbeatFuture).when(scheduler)
			.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.SECONDS));
		when(stateService.claim(taskId)).thenReturn(true);
		when(stateService.enterExtracting(taskId)).thenReturn(true);
		when(stateService.enterAssembling(taskId)).thenReturn(true);
		when(stateService.markSucceeded(eq(taskId), any(com.example.healthreport.cache.AnalysisResult.class)))
			.thenReturn(true);
		when(parseService.parseFiles(taskId)).thenReturn(Collections.<ParsedFile>emptyList());
		when(orchestrator.prepare(any(), any(DegradeAccumulator.class)))
			.thenReturn(new ParsePlan(Collections.singletonList(readableFile()), 3, 5));
		when(extractionStage.extract(any(ParsePlan.class), any(DegradeAccumulator.class)))
			.thenReturn(ValidatedExtractionOutputTestFactory.withUnreferencedSourceText("体检结论"));
		when(assembleService.assemble(any(ValidatedExtractionOutput.class), anyInt(), eq("company-a"),
				any(LocalDate.class), any(DegradeAccumulator.class)))
			.thenReturn(AnalysisModules.empty());
		AnalysisExecutorProperties properties = new AnalysisExecutorProperties();
		AnalysisTaskWorker worker = new AnalysisTaskWorker(stateService, taskService(taskId), cleanupService,
				parseService, orchestrator, extractionStage, assembleService, scheduler, properties);
		Logger logger = (Logger) LoggerFactory.getLogger(AnalysisTaskWorker.class);
		Level previousLevel = logger.getLevel();
		ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
		appender.start();
		logger.addAppender(appender);
		logger.setLevel(Level.INFO);

		try {
			worker.run(taskId);

			verify(cleanupService).deleteFiles(taskId);
			verify(heartbeatFuture).cancel(false);
			verify(stateService, never()).markFailed(taskId, com.example.healthreport.support.FailCode.SERVER_ERROR);
			StringBuilder renderedLog = new StringBuilder();
			for (ILoggingEvent event : appender.list) {
				renderedLog.append(event.getFormattedMessage()).append('\n');
			}
			assertThat(renderedLog.toString()).contains("任务执行开始，taskId=" + taskId)
				.contains("任务解析阶段处理完成，taskId=" + taskId)
				.contains("任务抽取阶段处理完成，taskId=" + taskId)
				.contains("任务组装阶段处理完成，taskId=" + taskId)
				.contains("任务分析全部完成，taskId=" + taskId);
		}
		finally {
			logger.detachAppender(appender);
			logger.setLevel(previousLevel);
			appender.stop();
		}
	}

	@Test
	void parseFailureShouldSettleBeforeAnyModelStage() {
		String taskId = "123e4567-e89b-12d3-a456-426614174000";
		TaskStateService stateService = mock(TaskStateService.class);
		TaskResourceCleanupService cleanupService = mock(TaskResourceCleanupService.class);
		TaskParseService parseService = mock(TaskParseService.class);
		ParseOrchestrator orchestrator = mock(ParseOrchestrator.class);
		ExtractionStageService extractionStage = mock(ExtractionStageService.class);
		AnalysisAssembleService assembleService = mock(AnalysisAssembleService.class);
		ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
		AnalysisExecutorProperties properties = new AnalysisExecutorProperties();
		when(stateService.claim(taskId)).thenReturn(true);
		when(parseService.parseFiles(taskId)).thenThrow(new HealthReportException(FailCode.UNREADABLE, 400));
		AnalysisTaskWorker worker = new AnalysisTaskWorker(stateService, taskService(taskId), cleanupService,
				parseService, orchestrator, extractionStage, assembleService, scheduler, properties);

		worker.run(taskId);

		verify(stateService).markFailed(taskId, FailCode.UNREADABLE);
		// 解析在任何模型调用之前；失败时绝不能已经进入抽取阶段。
		verify(stateService, never()).enterExtracting(taskId);
		verify(cleanupService, never()).deleteFiles(taskId);
	}

	@Test
	void imageCapacityFailureShouldKeepDeterministicFailCode() {
		String taskId = "123e4567-e89b-12d3-a456-426614174000";
		TaskStateService stateService = mock(TaskStateService.class);
		TaskResourceCleanupService cleanupService = mock(TaskResourceCleanupService.class);
		TaskParseService parseService = mock(TaskParseService.class);
		ParseOrchestrator orchestrator = mock(ParseOrchestrator.class);
		ExtractionStageService extractionStage = mock(ExtractionStageService.class);
		AnalysisAssembleService assembleService = mock(AnalysisAssembleService.class);
		ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
		when(stateService.claim(taskId)).thenReturn(true);
		when(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.SECONDS)))
			.thenThrow(new ImageTooLargeException(11L, 10L));
		AnalysisExecutorProperties properties = new AnalysisExecutorProperties();
		AnalysisTaskWorker worker = new AnalysisTaskWorker(stateService, taskService(taskId), cleanupService,
				parseService, orchestrator, extractionStage, assembleService, scheduler, properties);

		worker.run(taskId);

		verify(stateService).markFailed(taskId, FailCode.IMAGE_TOO_LARGE);
		verify(stateService, never()).markFailed(taskId, FailCode.SERVER_ERROR);
	}

	@Test
	void r49WorkerFailureLogsShouldExcludeSensitiveExceptionMessages() {
		String taskId = "123e4567-e89b-12d3-a456-426614174000";
		List<String> prohibitedMarkerList = Arrays.asList("R49_REPORT_PAYLOAD_TOKEN", "R49_PERSON_NAME_TOKEN",
				"R49_OCR_TOKEN", "R49_HEALTH_TOKEN", "R49_CREDENTIAL_TOKEN", "R49_MODEL_BODY_TOKEN",
				"R49_ORIGIN_NAME_TOKEN");
		TaskStateService stateService = mock(TaskStateService.class);
		TaskResourceCleanupService cleanupService = mock(TaskResourceCleanupService.class);
		TaskParseService parseService = mock(TaskParseService.class);
		ParseOrchestrator orchestrator = mock(ParseOrchestrator.class);
		ExtractionStageService extractionStage = mock(ExtractionStageService.class);
		AnalysisAssembleService assembleService = mock(AnalysisAssembleService.class);
		ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
		when(stateService.claim(taskId)).thenReturn(true);
		when(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.SECONDS)))
			.thenThrow(new IllegalStateException(String.join("|", prohibitedMarkerList)));
		AnalysisTaskWorker worker = new AnalysisTaskWorker(stateService, taskService(taskId), cleanupService,
				parseService, orchestrator, extractionStage, assembleService, scheduler,
				new AnalysisExecutorProperties());
		Logger logger = (Logger) LoggerFactory.getLogger(AnalysisTaskWorker.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
		appender.start();
		logger.addAppender(appender);
		try {
			worker.run(taskId);

			verify(stateService).markFailed(taskId, FailCode.SERVER_ERROR);
			assertThat(appender.list).isNotEmpty().allSatisfy(event -> {
				String throwableText = event.getThrowableProxy() == null ? ""
						: ThrowableProxyUtil.asString(event.getThrowableProxy());
				for (String prohibitedMarker : prohibitedMarkerList) {
					assertThat(event.getFormattedMessage()).doesNotContain(prohibitedMarker);
					assertThat(throwableText).doesNotContain(prohibitedMarker);
				}
				if (event.getThrowableProxy() != null) {
					assertThat(event.getThrowableProxy().getStackTraceElementProxyArray())
						.extracting(proxy -> proxy.getStackTraceElement().getClassName())
						.contains(AnalysisTaskWorkerTest.class.getName());
				}
			});
			assertThat(appender.list).anySatisfy(event -> assertThat(event.getThrowableProxy()).isNotNull());
		}
		finally {
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	@Test
	void everyStageShouldRunInOrderAndAssembledModulesShouldReachTheResult() {
		String taskId = "123e4567-e89b-12d3-a456-426614174000";
		TaskStateService stateService = mock(TaskStateService.class);
		TaskResourceCleanupService cleanupService = mock(TaskResourceCleanupService.class);
		TaskParseService parseService = mock(TaskParseService.class);
		ParseOrchestrator orchestrator = mock(ParseOrchestrator.class);
		ExtractionStageService extractionStage = mock(ExtractionStageService.class);
		AnalysisAssembleService assembleService = mock(AnalysisAssembleService.class);
		ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
		AnalysisModules assembled = new AnalysisModules("indicator", "problem", "advice", "dish");
		ValidatedExtractionOutput extractionOutput = ValidatedExtractionOutputTestFactory
			.withUnreferencedSourceText("体检结论");
		ParsePlan parsePlan = new ParsePlan(Collections.singletonList(readableFile()), 3, 5);
		when(stateService.claim(taskId)).thenReturn(true);
		when(stateService.enterExtracting(taskId)).thenReturn(true);
		when(stateService.enterAssembling(taskId)).thenReturn(true);
		when(stateService.markSucceeded(eq(taskId), any(AnalysisResult.class))).thenReturn(true);
		when(parseService.parseFiles(taskId)).thenReturn(Collections.<ParsedFile>emptyList());
		when(orchestrator.prepare(any(), any(DegradeAccumulator.class))).thenReturn(parsePlan);
		when(extractionStage.extract(eq(parsePlan), any(DegradeAccumulator.class))).thenReturn(extractionOutput);
		when(assembleService.assemble(eq(extractionOutput), eq(1), eq("company-a"), any(LocalDate.class),
				any(DegradeAccumulator.class)))
			.thenReturn(assembled);
		AnalysisTaskWorker worker = new AnalysisTaskWorker(stateService, taskService(taskId), cleanupService,
				parseService, orchestrator, extractionStage, assembleService, scheduler,
				new AnalysisExecutorProperties());

		worker.run(taskId);

		InOrder order = inOrder(parseService, orchestrator, extractionStage, assembleService);
		order.verify(parseService).parseFiles(taskId);
		order.verify(orchestrator).prepare(any(), any(DegradeAccumulator.class));
		order.verify(extractionStage).extract(eq(parsePlan), any(DegradeAccumulator.class));
		order.verify(assembleService)
			.assemble(eq(extractionOutput), eq(1), eq("company-a"), any(LocalDate.class),
					any(DegradeAccumulator.class));
		// 关键：写进结果的必须是组装产物本身。链路没接上时这里会是 empty，断言直接红。
		ArgumentCaptor<AnalysisResult> captor = ArgumentCaptor.forClass(AnalysisResult.class);
		verify(stateService).markSucceeded(eq(taskId), captor.capture());
		AnalysisResult written = captor.getValue();
		assertThat(written.getModules().getHealthIndicators()).isEqualTo("indicator");
		assertThat(written.getModules().getDishRecommendations()).isEqualTo("dish");
		assertThat(written.getProcessedPages()).isEqualTo(3);
		assertThat(written.getTotalPages()).isEqualTo(5);
	}

	@Test
	void extractionFailureShouldNotAssembleAndShouldNotWriteAnyResult() {
		String taskId = "123e4567-e89b-12d3-a456-426614174000";
		TaskStateService stateService = mock(TaskStateService.class);
		TaskResourceCleanupService cleanupService = mock(TaskResourceCleanupService.class);
		TaskParseService parseService = mock(TaskParseService.class);
		ParseOrchestrator orchestrator = mock(ParseOrchestrator.class);
		ExtractionStageService extractionStage = mock(ExtractionStageService.class);
		AnalysisAssembleService assembleService = mock(AnalysisAssembleService.class);
		ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
		when(stateService.claim(taskId)).thenReturn(true);
		when(stateService.enterExtracting(taskId)).thenReturn(true);
		when(parseService.parseFiles(taskId)).thenReturn(Collections.<ParsedFile>emptyList());
		when(orchestrator.prepare(any(), any(DegradeAccumulator.class)))
			.thenReturn(new ParsePlan(Collections.singletonList(readableFile()), 1, 1));
		// 全部非报告、身份不一致等确定性失败都由抽取阶段抛出，Worker 只负责收敛。
		when(extractionStage.extract(any(ParsePlan.class), any(DegradeAccumulator.class)))
			.thenThrow(new HealthReportException(FailCode.UNREADABLE, 400));
		AnalysisTaskWorker worker = new AnalysisTaskWorker(stateService, taskService(taskId), cleanupService,
				parseService, orchestrator, extractionStage, assembleService, scheduler,
				new AnalysisExecutorProperties());

		worker.run(taskId);

		verify(stateService).markFailed(taskId, FailCode.UNREADABLE);
		verify(assembleService, never()).assemble(any(), org.mockito.ArgumentMatchers.anyInt(), any(String.class),
				any(LocalDate.class), any(DegradeAccumulator.class));
		verify(stateService, never()).markSucceeded(eq(taskId), any(AnalysisResult.class));
		verify(cleanupService, never()).deleteFiles(taskId);
	}

	/** 真实 ParsedFile：它是 final 类，Mockito 造不出来，也不该为测试放开 final。 */
	private ParsedFile readableFile() {
		Segment segment = new Segment("f0-p1-s0", "血脂检查", "血脂检查", TextSource.OCR, null);
		ParsedPage page = new ParsedPage(1, Collections.singletonList(segment), null, false);
		return new ParsedFile(0, ContentType.PDF, 1, Collections.singletonList(page));
	}

	private CtHealthReportTaskService taskService(String taskId) {
		CtHealthReportTaskService taskService = mock(CtHealthReportTaskService.class);
		CtHealthReportTaskEntity taskEntity = new CtHealthReportTaskEntity();
		taskEntity.setTaskId(taskId);
		taskEntity.setCompanyId("company-a");
		when(taskService.findByTaskId(taskId)).thenReturn(taskEntity);
		return taskService;
	}

}
