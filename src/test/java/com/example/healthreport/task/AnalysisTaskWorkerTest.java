package com.example.healthreport.task;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.example.healthreport.assemble.AnalysisAssembleService;
import com.example.healthreport.assemble.TestAnalysisModulesFactory;
import com.example.healthreport.cache.AnalysisModules;
import com.example.healthreport.cache.AnalysisResult;
import com.example.healthreport.llm.extraction.DietAdviceResult;
import com.example.healthreport.llm.extraction.ExtractionOrchestrator;
import com.example.healthreport.llm.extraction.ExtractionOutcome;
import com.example.healthreport.llm.extraction.IndicatorsResult;
import com.example.healthreport.llm.extraction.ProblemsResult;
import com.example.healthreport.persistence.CtHealthReportTaskEntity;
import com.example.healthreport.persistence.CtHealthReportTaskService;
import com.example.healthreport.render.ImageTooLargeException;
import com.example.healthreport.render.PageImageSequence;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** R35：删除后领取 CAS 影响零行时 Worker 必须无副作用退出；阶段顺序与结果链路。 */
class AnalysisTaskWorkerTest {

	@Test
	void failedClaimShouldDoNothingAndWriteNoTerminalState() {
		String taskId = "123e4567-e89b-12d3-a456-426614174000";
		TaskStateService stateService = mock(TaskStateService.class);
		TaskResourceCleanupService cleanupService = mock(TaskResourceCleanupService.class);
		TaskRenderService renderService = mock(TaskRenderService.class);
		ExtractionOrchestrator orchestrator = mock(ExtractionOrchestrator.class);
		AnalysisAssembleService assembleService = mock(AnalysisAssembleService.class);
		ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
		AnalysisExecutorProperties properties = new AnalysisExecutorProperties();
		when(stateService.claim(taskId)).thenReturn(false);
		AnalysisTaskWorker worker = new AnalysisTaskWorker(stateService, taskService(taskId), cleanupService,
				renderService, orchestrator, assembleService, scheduler, properties);

		worker.run(taskId);

		verify(stateService).claim(taskId);
		verify(stateService, never()).markFailed(taskId, FailCode.SERVER_ERROR);
		verify(cleanupService, never()).deleteFiles(taskId);
	}

	@Test
	void successfulFlowShouldCleanOriginalFilesAfterSuccessCommit() {
		String taskId = "123e4567-e89b-12d3-a456-426614174000";
		TaskStateService stateService = mock(TaskStateService.class);
		TaskResourceCleanupService cleanupService = mock(TaskResourceCleanupService.class);
		TaskRenderService renderService = mock(TaskRenderService.class);
		ExtractionOrchestrator orchestrator = mock(ExtractionOrchestrator.class);
		AnalysisAssembleService assembleService = mock(AnalysisAssembleService.class);
		ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
		@SuppressWarnings("unchecked")
		ScheduledFuture<Object> heartbeatFuture = mock(ScheduledFuture.class);
		doReturn(heartbeatFuture).when(scheduler)
			.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.SECONDS));
		when(stateService.claim(taskId)).thenReturn(true);
		when(stateService.enterExtracting(taskId)).thenReturn(true);
		when(stateService.enterAssembling(taskId)).thenReturn(true);
		when(stateService.markSucceeded(eq(taskId), any(AnalysisResult.class))).thenReturn(true);
		when(renderService.renderFiles(taskId)).thenReturn(images());
		when(orchestrator.extract(any(PageImageSequence.class), any(DegradeAccumulator.class)))
			.thenReturn(outcome());
		when(assembleService.assemble(any(ExtractionOutcome.class), any(PageImageSequence.class), anyInt(),
				eq("company-a"), any(LocalDate.class), any(DegradeAccumulator.class)))
			.thenReturn(AnalysisModules.empty());
		AnalysisExecutorProperties properties = new AnalysisExecutorProperties();
		AnalysisTaskWorker worker = new AnalysisTaskWorker(stateService, taskService(taskId), cleanupService,
				renderService, orchestrator, assembleService, scheduler, properties);
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
			verify(stateService, never()).markFailed(taskId, FailCode.SERVER_ERROR);
			StringBuilder renderedLog = new StringBuilder();
			for (ILoggingEvent event : appender.list) {
				renderedLog.append(event.getFormattedMessage()).append('\n');
			}
			assertThat(renderedLog.toString()).contains("任务执行开始，taskId=" + taskId)
				.contains("任务转图阶段处理完成，taskId=" + taskId)
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
	void renderFailureShouldSettleBeforeAnyModelStage() {
		String taskId = "123e4567-e89b-12d3-a456-426614174000";
		TaskStateService stateService = mock(TaskStateService.class);
		TaskResourceCleanupService cleanupService = mock(TaskResourceCleanupService.class);
		TaskRenderService renderService = mock(TaskRenderService.class);
		ExtractionOrchestrator orchestrator = mock(ExtractionOrchestrator.class);
		AnalysisAssembleService assembleService = mock(AnalysisAssembleService.class);
		ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
		AnalysisExecutorProperties properties = new AnalysisExecutorProperties();
		when(stateService.claim(taskId)).thenReturn(true);
		when(renderService.renderFiles(taskId)).thenThrow(new HealthReportException(FailCode.UNREADABLE, 400));
		AnalysisTaskWorker worker = new AnalysisTaskWorker(stateService, taskService(taskId), cleanupService,
				renderService, orchestrator, assembleService, scheduler, properties);

		worker.run(taskId);

		verify(stateService).markFailed(taskId, FailCode.UNREADABLE);
		// 转图在任何模型调用之前；失败时绝不能已经进入抽取阶段。
		verify(stateService, never()).enterExtracting(taskId);
		verify(cleanupService, never()).deleteFiles(taskId);
	}

	@Test
	void storageIntegrityFailureShouldBecomeServerErrorBeforeAnyModelStage() {
		String taskId = "123e4567-e89b-12d3-a456-426614174000";
		TaskStateService stateService = mock(TaskStateService.class);
		TaskResourceCleanupService cleanupService = mock(TaskResourceCleanupService.class);
		TaskRenderService renderService = mock(TaskRenderService.class);
		ExtractionOrchestrator orchestrator = mock(ExtractionOrchestrator.class);
		AnalysisAssembleService assembleService = mock(AnalysisAssembleService.class);
		ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
		when(stateService.claim(taskId)).thenReturn(true);
		when(renderService.renderFiles(taskId)).thenThrow(new IllegalStateException("对象内容哈希不一致"));
		AnalysisTaskWorker worker = new AnalysisTaskWorker(stateService, taskService(taskId), cleanupService,
				renderService, orchestrator, assembleService, scheduler, new AnalysisExecutorProperties());

		worker.run(taskId);

		verify(stateService).markFailed(taskId, FailCode.SERVER_ERROR);
		verify(stateService, never()).enterExtracting(taskId);
		verify(orchestrator, never()).extract(any(PageImageSequence.class), any(DegradeAccumulator.class));
		verify(cleanupService, never()).deleteFiles(taskId);
	}

	@Test
	void imageCapacityFailureShouldKeepDeterministicFailCode() {
		String taskId = "123e4567-e89b-12d3-a456-426614174000";
		TaskStateService stateService = mock(TaskStateService.class);
		TaskResourceCleanupService cleanupService = mock(TaskResourceCleanupService.class);
		TaskRenderService renderService = mock(TaskRenderService.class);
		ExtractionOrchestrator orchestrator = mock(ExtractionOrchestrator.class);
		AnalysisAssembleService assembleService = mock(AnalysisAssembleService.class);
		ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
		when(stateService.claim(taskId)).thenReturn(true);
		when(renderService.renderFiles(taskId))
			.thenThrow(new HealthReportException(FailCode.IMAGE_TOO_LARGE, 400,
					new ImageTooLargeException(11L, 10L)));
		AnalysisExecutorProperties properties = new AnalysisExecutorProperties();
		AnalysisTaskWorker worker = new AnalysisTaskWorker(stateService, taskService(taskId), cleanupService,
				renderService, orchestrator, assembleService, scheduler, properties);

		worker.run(taskId);

		verify(stateService).markFailed(taskId, FailCode.IMAGE_TOO_LARGE);
		verify(stateService, never()).markFailed(taskId, FailCode.SERVER_ERROR);
	}

	@Test
	void r49WorkerFailureLogsShouldExcludeSensitiveExceptionMessages() {
		String taskId = "123e4567-e89b-12d3-a456-426614174000";
		List<String> prohibitedMarkerList = Arrays.asList("R49_REPORT_PAYLOAD_TOKEN", "R49_PERSON_NAME_TOKEN",
				"R49_REPORT_TEXT_TOKEN", "R49_HEALTH_TOKEN", "R49_CREDENTIAL_TOKEN", "R49_MODEL_BODY_TOKEN",
				"R49_ORIGIN_NAME_TOKEN");
		TaskStateService stateService = mock(TaskStateService.class);
		TaskResourceCleanupService cleanupService = mock(TaskResourceCleanupService.class);
		TaskRenderService renderService = mock(TaskRenderService.class);
		ExtractionOrchestrator orchestrator = mock(ExtractionOrchestrator.class);
		AnalysisAssembleService assembleService = mock(AnalysisAssembleService.class);
		ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
		when(stateService.claim(taskId)).thenReturn(true);
		when(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.SECONDS)))
			.thenThrow(new IllegalStateException(String.join("|", prohibitedMarkerList)));
		AnalysisTaskWorker worker = new AnalysisTaskWorker(stateService, taskService(taskId), cleanupService,
				renderService, orchestrator, assembleService, scheduler,
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
		TaskRenderService renderService = mock(TaskRenderService.class);
		ExtractionOrchestrator orchestrator = mock(ExtractionOrchestrator.class);
		AnalysisAssembleService assembleService = mock(AnalysisAssembleService.class);
		ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
		AnalysisModules assembled = TestAnalysisModulesFactory.populated();
		ExtractionOutcome outcome = outcome();
		PageImageSequence images = images();
		when(stateService.claim(taskId)).thenReturn(true);
		when(stateService.enterExtracting(taskId)).thenReturn(true);
		when(stateService.enterAssembling(taskId)).thenReturn(true);
		when(stateService.markSucceeded(eq(taskId), any(AnalysisResult.class))).thenReturn(true);
		when(renderService.renderFiles(taskId)).thenReturn(images);
		when(orchestrator.extract(eq(images), any(DegradeAccumulator.class))).thenReturn(outcome);
		when(assembleService.assemble(eq(outcome), eq(images), eq(1), eq("company-a"), any(LocalDate.class),
				any(DegradeAccumulator.class)))
			.thenReturn(assembled);
		AnalysisTaskWorker worker = new AnalysisTaskWorker(stateService, taskService(taskId), cleanupService,
				renderService, orchestrator, assembleService, scheduler,
				new AnalysisExecutorProperties());

		worker.run(taskId);

		InOrder order = inOrder(renderService, orchestrator, assembleService);
		order.verify(renderService).renderFiles(taskId);
		order.verify(orchestrator).extract(eq(images), any(DegradeAccumulator.class));
		order.verify(assembleService)
			.assemble(eq(outcome), eq(images), eq(1), eq("company-a"), any(LocalDate.class),
					any(DegradeAccumulator.class));
		// 关键：写进结果的必须是组装产物本身。链路没接上时这里会是 empty，断言直接红。
		ArgumentCaptor<AnalysisResult> captor = ArgumentCaptor.forClass(AnalysisResult.class);
		verify(stateService).markSucceeded(eq(taskId), captor.capture());
		AnalysisResult written = captor.getValue();
		assertThat(written.getModules().getHealthIndicators()).isSameAs(assembled.getHealthIndicators());
		assertThat(written.getModules().getDishRecommendations()).isSameAs(assembled.getDishRecommendations());
		assertThat(written.getProcessedPages()).isEqualTo(2);
		assertThat(written.getTotalPages()).isEqualTo(2);
	}

	@Test
	void extractionFailureShouldNotAssembleAndShouldNotWriteAnyResult() {
		String taskId = "123e4567-e89b-12d3-a456-426614174000";
		TaskStateService stateService = mock(TaskStateService.class);
		TaskResourceCleanupService cleanupService = mock(TaskResourceCleanupService.class);
		TaskRenderService renderService = mock(TaskRenderService.class);
		ExtractionOrchestrator orchestrator = mock(ExtractionOrchestrator.class);
		AnalysisAssembleService assembleService = mock(AnalysisAssembleService.class);
		ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
		when(stateService.claim(taskId)).thenReturn(true);
		when(stateService.enterExtracting(taskId)).thenReturn(true);
		when(renderService.renderFiles(taskId)).thenReturn(images());
		// 全部非报告、身份不一致等确定性失败都由抽取阶段抛出，Worker 只负责收敛。
		when(orchestrator.extract(any(PageImageSequence.class), any(DegradeAccumulator.class)))
			.thenThrow(new HealthReportException(FailCode.UNREADABLE, 400));
		AnalysisTaskWorker worker = new AnalysisTaskWorker(stateService, taskService(taskId), cleanupService,
				renderService, orchestrator, assembleService, scheduler,
				new AnalysisExecutorProperties());

		worker.run(taskId);

		verify(stateService).markFailed(taskId, FailCode.UNREADABLE);
		verify(assembleService, never()).assemble(any(ExtractionOutcome.class), any(PageImageSequence.class),
				anyInt(), any(String.class), any(LocalDate.class), any(DegradeAccumulator.class));
		verify(stateService, never()).markSucceeded(eq(taskId), any(AnalysisResult.class));
		verify(cleanupService, never()).deleteFiles(taskId);
	}

	private PageImageSequence images() {
		return new PageImageSequence.Builder()
			.addPage(0, 1, new byte[]{1})
			.addPage(0, 2, new byte[]{2})
			.build();
	}

	private ExtractionOutcome outcome() {
		return new ExtractionOutcome(
				new IndicatorsResult("OK", null, null, Collections.<IndicatorsResult.Section>emptyList()),
				new ProblemsResult("OK", Collections.<ProblemsResult.Problem>emptyList()),
				new DietAdviceResult("OK", Collections.<DietAdviceResult.DietTag>emptyList(),
						Collections.<DietAdviceResult.DietTag>emptyList()));
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
