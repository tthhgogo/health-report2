package com.example.healthreport.task;

import com.example.healthreport.assemble.AnalysisAssembleService;
import com.example.healthreport.cache.AnalysisModules;
import com.example.healthreport.cache.AnalysisResult;
import com.example.healthreport.llm.extraction.ExtractionStageService;
import com.example.healthreport.llm.extraction.ValidatedExtractionOutput;
import com.example.healthreport.parse.ParseOrchestrator;
import com.example.healthreport.parse.ParsePlan;
import com.example.healthreport.parse.ParsedFile;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 分析工作线程：解析 → 抽取 → 组装 → 写结果。
 * <p>三个阶段各自有编排类，本类只负责阶段顺序、状态机推进与失败收敛，
 * <b>不实现任何业务规则</b>。</p>
 */
@Slf4j
@Component
public class AnalysisTaskWorker {

    private final TaskStateService taskStateService;
    private final TaskResourceCleanupService resourceCleanupService;
    private final TaskParseService taskParseService;
    private final ParseOrchestrator parseOrchestrator;
    private final ExtractionStageService extractionStageService;
    private final AnalysisAssembleService analysisAssembleService;
    private final ScheduledExecutorService heartbeatScheduler;
    private final AnalysisExecutorProperties properties;

    public AnalysisTaskWorker(TaskStateService taskStateService,
                              TaskResourceCleanupService resourceCleanupService,
                              TaskParseService taskParseService,
                              ParseOrchestrator parseOrchestrator,
                              ExtractionStageService extractionStageService,
                              AnalysisAssembleService analysisAssembleService,
                              @Qualifier("analysisHeartbeatScheduler")
                              ScheduledExecutorService heartbeatScheduler,
                              AnalysisExecutorProperties properties) {
        this.taskStateService = taskStateService;
        this.resourceCleanupService = resourceCleanupService;
        this.taskParseService = taskParseService;
        this.parseOrchestrator = parseOrchestrator;
        this.extractionStageService = extractionStageService;
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
                return;
            }
            startMillis = System.currentTimeMillis();
            heartbeatFuture = scheduleHeartbeat(taskId);

            // PARSING：解析、OCR、页数预算与零 segment 裁决，全部在任何模型调用之前完成。
            DegradeAccumulator accumulator = new DegradeAccumulator();
            List<ParsedFile> parsedFileList = taskParseService.parseFiles(taskId);
            ParsePlan parsePlan = parseOrchestrator.prepare(parsedFileList, accumulator);

            if (!taskStateService.enterExtracting(taskId)) {
                return;
            }
            // EXTRACTING：分批、并发调用、Schema 与来源校验、多批与多文件合并。
            ValidatedExtractionOutput extractionOutput =
                    extractionStageService.extract(parsePlan, accumulator);

            if (!taskStateService.enterAssembling(taskId)) {
                return;
            }
            // ASSEMBLING：四个模块纯 Java 组装，只读离线打标，不再调用任何模型。
            AnalysisModules modules = analysisAssembleService.assemble(extractionOutput,
                    parsePlan.getReadableFileList().size(), accumulator);

            if (accumulator.partial() && !taskStateService.markPartial(taskId, accumulator)) {
                return;
            }
            AnalysisResult result = AnalysisResult.create(accumulator,
                    parsePlan.getProcessedPages(), parsePlan.getTotalPages(), modules);
            if (taskStateService.markSucceeded(taskId, result)) {
                // 端到端耗时只有这里能算出来：三个阶段各自的耗时加起来不等于它，
                // 中间还有状态迁移、心跳与线程池排队。容量参数 W 要按这个数校准（§4.2）。
                log.info("任务分析全部完成，taskId={}，是否部分结果={}，处理页数={}，总页数={}，端到端耗时={}ms",
                        taskId, accumulator.partial(), parsePlan.getProcessedPages(),
                        parsePlan.getTotalPages(), System.currentTimeMillis() - startMillis);
                // 成功后立即删原文件与 file 行；结果本身继续保留两小时。
                resourceCleanupService.deleteFiles(taskId);
            }
        } catch (HealthReportException exception) {
            markFailure(taskId, exception.getFailCode());
            logWorkerFailure(exception);
        } catch (RuntimeException exception) {
            markFailure(taskId, FailCode.SERVER_ERROR);
            logWorkerFailure(exception);
        } finally {
            if (heartbeatFuture != null) {
                // 这里只停止独立心跳调度；从未保存或取消分析 Worker 自身的 Future。
                heartbeatFuture.cancel(false);
            }
        }
    }

    private ScheduledFuture<?> scheduleHeartbeat(final String taskId) {
        long intervalSeconds = properties.getHeartbeatIntervalSeconds();
        return heartbeatScheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    taskStateService.heartbeat(taskId);
                } catch (RuntimeException exception) {
                    logWorkerFailure(exception);
                }
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    /** 业务异常保留其确定性失败码；未知运行时异常才由调用方映射为服务端失败。 */
    private void markFailure(String taskId, FailCode failCode) {
        try {
            taskStateService.markFailed(taskId, failCode);
        } catch (RuntimeException stateException) {
            logWorkerFailure(stateException);
        }
    }

    private void logWorkerFailure(RuntimeException exception) {
        // 外部异常消息可能带响应正文；脱敏副本保留不含业务数据的原始调用栈用于定位。
        IllegalStateException sanitizedException = new IllegalStateException(
                "执行异常类型:" + exception.getClass().getName());
        sanitizedException.setStackTrace(exception.getStackTrace());
        log.error("分析任务执行异常", sanitizedException);
    }
}
