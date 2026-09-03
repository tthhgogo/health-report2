package com.example.healthreport.task;

import com.example.healthreport.assemble.AnalysisAssembleService;
import com.example.healthreport.cache.AnalysisModules;
import com.example.healthreport.cache.AnalysisResult;
import com.example.healthreport.llm.extraction.ExtractionOrchestrator;
import com.example.healthreport.llm.extraction.ExtractionOutcome;
import com.example.healthreport.persistence.CtHealthReportTaskEntity;
import com.example.healthreport.persistence.CtHealthReportTaskService;
import com.example.healthreport.render.FileLocation;
import com.example.healthreport.render.PageImageSequence;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 分析工作线程：转图 → 三次串行模型调用 → 组装 → 写结果。
 * <p>三个阶段各自有编排类，本类只负责阶段顺序、状态机推进与失败收敛，<b>不实现任何业务规则</b>。</p>
 */
@Slf4j
@Component
public class AnalysisTaskWorker {

	private final TaskStateService taskStateService;

	private final CtHealthReportTaskService taskService;

	private final TaskResourceCleanupService resourceCleanupService;

	private final TaskRenderService taskRenderService;

	private final ExtractionOrchestrator extractionOrchestrator;

	private final AnalysisAssembleService analysisAssembleService;

	private final ScheduledExecutorService heartbeatScheduler;

	private final AnalysisExecutorProperties properties;

	public AnalysisTaskWorker(TaskStateService taskStateService, CtHealthReportTaskService taskService,
			TaskResourceCleanupService resourceCleanupService, TaskRenderService taskRenderService,
			ExtractionOrchestrator extractionOrchestrator, AnalysisAssembleService analysisAssembleService,
			@Qualifier("analysisHeartbeatScheduler") ScheduledExecutorService heartbeatScheduler,
			AnalysisExecutorProperties properties) {
		this.taskStateService = taskStateService;
		this.taskService = taskService;
		this.resourceCleanupService = resourceCleanupService;
		this.taskRenderService = taskRenderService;
		this.extractionOrchestrator = extractionOrchestrator;
		this.analysisAssembleService = analysisAssembleService;
		this.heartbeatScheduler = heartbeatScheduler;
		this.properties = properties;
	}

	/**
	 * 运行单个任务；领取 CAS 失败时不创建心跳、不写失败终态，也不做任何业务工作。
	 */
	public void run(String taskId) {
		ScheduledFuture<?> heartbeatFuture = null;
		long startMillis = System.currentTimeMillis();
		try {
			if (!taskStateService.claim(taskId)) {
				log.info("任务领取 CAS 未生效，工作线程结束，taskId={}", taskId);
				return;
			}
			startMillis = System.currentTimeMillis();
			heartbeatFuture = scheduleHeartbeat(taskId);
			log.info("任务执行开始，taskId={}", taskId);

			CtHealthReportTaskEntity taskEntity = taskService.findByTaskId(taskId);
			if (taskEntity == null || taskEntity.getCompanyId() == null || taskEntity.getCompanyId().length() == 0) {
				throw new IllegalStateException("任务企业归属缺失");
			}
			String companyId = taskEntity.getCompanyId();
			LocalDate bizDate = LocalDate.now();

			// PARSING：业务容量在创建时已同步裁决；转图前仍复核对象完整性与精确页数，防止存储漂移。
			long stageStartMillis = System.currentTimeMillis();
			DegradeAccumulator accumulator = new DegradeAccumulator();
			PageImageSequence images = taskRenderService.renderFiles(taskId);
			int fileCount = fileCount(images);
			log.info("任务转图阶段处理完成，taskId={}，文件数={}，总页数={}，耗时={}ms", taskId, fileCount,
					images.size(), System.currentTimeMillis() - stageStartMillis);

			if (!taskStateService.enterExtracting(taskId)) {
				log.info("任务进入抽取阶段未生效，工作线程结束，taskId={}", taskId);
				return;
			}
			// EXTRACTING：三次严格串行调用（指标 → 问题 → 饮食标签），共用同一份完整图序列。
			stageStartMillis = System.currentTimeMillis();
			ExtractionOutcome outcome = extractionOrchestrator.extract(images, accumulator);
			log.info("任务抽取阶段处理完成，taskId={}，耗时={}ms", taskId, System.currentTimeMillis() - stageStartMillis);

			if (!taskStateService.enterAssembling(taskId)) {
				log.info("任务进入组装阶段未生效，工作线程结束，taskId={}", taskId);
				return;
			}
			// ASSEMBLING：四个模块纯 Java 组装，只读离线打标集合，不再调用任何模型。
			stageStartMillis = System.currentTimeMillis();
			AnalysisModules modules = analysisAssembleService.assemble(outcome, images, fileCount, companyId,
					bizDate, accumulator);
			log.info("任务组装阶段处理完成，taskId={}，耗时={}ms", taskId, System.currentTimeMillis() - stageStartMillis);

			if (accumulator.partial() && !taskStateService.markPartial(taskId, accumulator)) {
				log.info("任务部分结果标记未生效，工作线程结束，taskId={}，partialReason={}", taskId, accumulator.primaryReason());
				return;
			}
			AnalysisResult result = AnalysisResult.create(accumulator, images.size(), images.size(), modules);
			if (taskStateService.markSucceeded(taskId, result)) {
				// 端到端耗时只有这里能算出来：三个阶段各自的耗时加起来不等于它，
				// 中间还有状态迁移、心跳与线程池排队。容量参数 W 要按这个数校准（§4.2）。
				log.info("任务分析全部完成，taskId={}，是否部分结果={}，总页数={}，端到端耗时={}ms", taskId, accumulator.partial(),
						images.size(), System.currentTimeMillis() - startMillis);
				// 成功后立即删原文件与 file 行；结果本身继续保留两小时。
				resourceCleanupService.deleteFiles(taskId);
			}
			else {
				log.info("任务成功提交未生效，工作线程结束，taskId={}", taskId);
			}
		}
		catch (HealthReportException exception) {
			markFailure(taskId, exception.getFailCode());
			logWorkerFailure(taskId, exception);
		}
		catch (RuntimeException exception) {
			markFailure(taskId, FailCode.SERVER_ERROR);
			logWorkerFailure(taskId, exception);
		}
		finally {
			if (heartbeatFuture != null) {
				// 这里只停止独立心跳调度；从未保存或取消分析 Worker 自身的 Future。
				heartbeatFuture.cancel(false);
			}
		}
	}

	/** 图序列覆盖的文件数 = 末页 fileIndex + 1（fileIndex 连续升序由绑定与转图共同保证）。 */
	private int fileCount(PageImageSequence images) {
		FileLocation lastLocation = images.locate(images.size());
		return lastLocation.getFileIndex() + 1;
	}

	private ScheduledFuture<?> scheduleHeartbeat(final String taskId) {
		long intervalSeconds = properties.getHeartbeatIntervalSeconds();
		return heartbeatScheduler.scheduleAtFixedRate(new Runnable() {
			@Override
			public void run() {
				try {
					taskStateService.heartbeat(taskId);
				}
				catch (RuntimeException exception) {
					logWorkerFailure(taskId, exception);
				}
			}
		}, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
	}

	/** 业务异常保留其确定性失败码；未知运行时异常才由调用方映射为服务端失败。 */
	private void markFailure(String taskId, FailCode failCode) {
		try {
			if (!taskStateService.markFailed(taskId, failCode)) {
				log.info("任务失败终态写入未生效，taskId={}，failCode={}", taskId, failCode);
			}
		}
		catch (RuntimeException stateException) {
			logWorkerFailure(taskId, stateException);
		}
	}

	private void logWorkerFailure(String taskId, RuntimeException exception) {
		// 外部异常消息可能带响应正文；脱敏副本保留不含业务数据的原始调用栈用于定位。
		IllegalStateException sanitizedException = new IllegalStateException(
				"执行异常类型:" + exception.getClass().getName());
		sanitizedException.setStackTrace(exception.getStackTrace());
		log.error("分析任务执行异常，taskId={}", taskId, sanitizedException);
	}

}
