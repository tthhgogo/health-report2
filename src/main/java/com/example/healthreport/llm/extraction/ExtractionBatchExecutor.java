package com.example.healthreport.llm.extraction;

import com.example.healthreport.infra.ExtractionModelClient;
import com.example.healthreport.infra.LlmCallException;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import com.example.healthreport.support.PartialReason;
import com.example.healthreport.task.DegradeAccumulator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 在独立批次线程池上执行 LLM-A 调用，并完成批次三态与任务级裁决。
 * <p>单任务至多同时提交四批；任何失败都不重试、不取消其他批次，等待全部结束后统一失败。</p>
 */
@Slf4j
@Service
public class ExtractionBatchExecutor {

    public static final int TASK_CONCURRENCY = 4;

    private final ThreadPoolExecutor llmBatchExecutor;
    private final ExtractionModelClient modelClient;
    private final ObjectMapper objectMapper;

    public ExtractionBatchExecutor(@Qualifier("llmBatchExecutor") ThreadPoolExecutor llmBatchExecutor,
                             ExtractionModelClient modelClient, ObjectMapper objectMapper) {
        this.llmBatchExecutor = llmBatchExecutor;
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行全部批次并只返回可合并的 OK 结果。
     *
     * @throws LlmCallException 任一调用、调度或最小响应解析失败
     * @throws HealthReportException 全不可读或全文件均无报告特征
     */
    public List<ExtractionBatchResult> execute(List<ExtractionBatchPlan> batchPlanList,
                                         DegradeAccumulator degradeAccumulator) {
        if (batchPlanList == null || batchPlanList.isEmpty() || degradeAccumulator == null) {
            throw new IllegalArgumentException("批次执行参数不能为空");
        }
        ExecutorCompletionService<ExtractionBatchResult> completionService =
                new ExecutorCompletionService<ExtractionBatchResult>(llmBatchExecutor);
        List<ExtractionBatchResult> completedResultList = new ArrayList<ExtractionBatchResult>(batchPlanList.size());
        Map<Future<ExtractionBatchResult>, Integer> batchIndexByFutureMap =
                new HashMap<Future<ExtractionBatchResult>, Integer>(batchPlanList.size());
        int nextToSubmit = 0;
        int submitted = 0;
        int completed = 0;
        boolean failed = false;
        boolean interrupted = false;

        while (nextToSubmit < batchPlanList.size() && submitted - completed < TASK_CONCURRENCY) {
            ExtractionBatchPlan plan = batchPlanList.get(nextToSubmit);
            try {
                Future<ExtractionBatchResult> future = completionService.submit(callable(plan));
                batchIndexByFutureMap.put(future, plan.getInput().getBatchIndex());
                nextToSubmit++;
                submitted++;
            } catch (RuntimeException exception) {
                failed = true;
                log.error("LLM-A 批次提交失败，batchIndex={}，batchStatus=SUBMIT_FAILED",
                        plan.getInput().getBatchIndex(), exception);
                nextToSubmit++;
            }
        }
        while (completed < submitted) {
            Future<ExtractionBatchResult> future;
            try {
                future = completionService.take();
            } catch (InterruptedException exception) {
                interrupted = true;
                failed = true;
                log.error("LLM-A 等待批次完成时被中断，已提交批次数={}，已完成批次数={}",
                        submitted, completed, exception);
                continue;
            }
            completed++;
            Integer completedBatchIndex = batchIndexByFutureMap.get(future);
            try {
                completedResultList.add(future.get());
            } catch (InterruptedException exception) {
                interrupted = true;
                failed = true;
                log.error("LLM-A 获取批次结果时被中断，batchIndex={}，batchStatus=INTERRUPTED",
                        completedBatchIndex, exception);
            } catch (ExecutionException exception) {
                failed = true;
                logBatchExecutionFailure(completedBatchIndex, exception.getCause());
            }
            if (nextToSubmit < batchPlanList.size()) {
                ExtractionBatchPlan plan = batchPlanList.get(nextToSubmit);
                try {
                    Future<ExtractionBatchResult> nextFuture = completionService.submit(callable(plan));
                    batchIndexByFutureMap.put(nextFuture, plan.getInput().getBatchIndex());
                    submitted++;
                } catch (RuntimeException exception) {
                    failed = true;
                    log.error("LLM-A 批次提交失败，batchIndex={}，batchStatus=SUBMIT_FAILED",
                            plan.getInput().getBatchIndex(), exception);
                }
                nextToSubmit++;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (failed || completedResultList.size() != batchPlanList.size()) {
            throw new LlmCallException(FailCode.SERVER_ERROR, 0, 0L);
        }
        return decide(completedResultList, degradeAccumulator);
    }

    /**
     * 记录异步批次失败；只有明确不持有正文的异常才记录堆栈。
     * <p>未知异常（尤其解析异常）可能在消息中携带模型响应片段，只记录类型名。</p>
     */
    private void logBatchExecutionFailure(Integer batchIndex, Throwable cause) {
        String causeType = cause == null ? "Unknown" : cause.getClass().getName();
        if (cause instanceof LlmCallException || cause instanceof IllegalStateException) {
            log.error("LLM-A 批次执行失败，batchIndex={}，batchStatus=FAILED，异常类型={}",
                    batchIndex, causeType, cause);
            return;
        }
        log.error("LLM-A 批次执行失败，batchIndex={}，batchStatus=FAILED，异常类型={}",
                batchIndex, causeType);
    }

    private java.util.concurrent.Callable<ExtractionBatchResult> callable(final ExtractionBatchPlan plan) {
        return new java.util.concurrent.Callable<ExtractionBatchResult>() {
            @Override
            public ExtractionBatchResult call() {
                int batchIndex = plan.getInput().getBatchIndex();
                long startMillis = System.currentTimeMillis();
                log.info("LLM-A 批次开始，batchIndex={}，耗时=0ms，batchStatus=STARTED", batchIndex);
                String rawContent = modelClient.call(plan.getInput());
                BatchStatus status = extractBatchStatus(rawContent, batchIndex);
                log.info("LLM-A 批次完成，batchIndex={}，耗时={}ms，batchStatus={}",
                        batchIndex, System.currentTimeMillis() - startMillis, status.name());
                return new ExtractionBatchResult(plan, status, rawContent);
            }
        };
    }

    private BatchStatus extractBatchStatus(String rawContent, int batchIndex) {
        if (rawContent == null) {
            log.error("LLM-A 批次状态缺失，batchIndex={}，耗时=0ms，batchStatus=INVALID", batchIndex);
            throw new LlmCallException(FailCode.SERVER_ERROR, 200, 0L);
        }
        try {
            JsonNode statusNode = objectMapper.readTree(rawContent).path("batchStatus");
            if (!statusNode.isTextual()) {
                throw new IllegalArgumentException("批次状态字段无效");
            }
            return BatchStatus.valueOf(statusNode.asText());
        } catch (IOException exception) {
            log.error("LLM-A 批次状态解析失败，batchIndex={}，耗时=0ms，batchStatus=INVALID",
                    batchIndex);
            throw new LlmCallException(FailCode.SERVER_ERROR, 200, 0L);
        } catch (IllegalArgumentException exception) {
            log.error("LLM-A 批次状态无效，batchIndex={}，耗时=0ms，batchStatus=INVALID",
                    batchIndex);
            throw new LlmCallException(FailCode.SERVER_ERROR, 200, 0L);
        }
    }

    private List<ExtractionBatchResult> decide(List<ExtractionBatchResult> resultList,
                                         DegradeAccumulator degradeAccumulator) {
        int unreadableCount = 0;
        Map<Integer, Integer> fileBatchCountMap = new HashMap<Integer, Integer>(5);
        Map<Integer, Integer> fileNoReportCountMap = new HashMap<Integer, Integer>(5);
        List<ExtractionBatchResult> okResultList = new ArrayList<ExtractionBatchResult>(resultList.size());
        for (ExtractionBatchResult result : resultList) {
            int fileIndex = result.getPlan().getInput().getFileIndex();
            increment(fileBatchCountMap, fileIndex);
            if (result.getBatchStatus() == BatchStatus.UNREADABLE) {
                unreadableCount++;
            } else if (result.getBatchStatus() == BatchStatus.NO_REPORT_FEATURE) {
                increment(fileNoReportCountMap, fileIndex);
            } else {
                okResultList.add(result);
            }
        }
        if (unreadableCount == resultList.size()) {
            log.warn("LLM-A 任务裁决为全部不可读，批次数={}，不可读批次数={}，"
                            + "batchStatus=UNREADABLE，failCode={}",
                    resultList.size(), unreadableCount, FailCode.UNREADABLE.name());
            throw new HealthReportException(FailCode.UNREADABLE, 400);
        }
        if (allFilesHaveNoReportFeature(fileBatchCountMap, fileNoReportCountMap)) {
            log.warn("LLM-A 任务裁决为非体检报告，批次数={}，文件数={}，"
                            + "batchStatus=NO_REPORT_FEATURE，failCode={}",
                    resultList.size(), fileBatchCountMap.size(), FailCode.NOT_HEALTH_REPORT.name());
            throw new HealthReportException(FailCode.NOT_HEALTH_REPORT, 400);
        }
        if (unreadableCount > 0) {
            degradeAccumulator.recordBatchUnreadable();
            log.warn("LLM-A 任务按不可读批次降级，批次数={}，不可读批次数={}，"
                            + "batchStatus=UNREADABLE，partialReason={}",
                    resultList.size(), unreadableCount, PartialReason.BATCH_UNREADABLE.name());
        }
        Collections.sort(okResultList, new Comparator<ExtractionBatchResult>() {
            @Override
            public int compare(ExtractionBatchResult left, ExtractionBatchResult right) {
                return Integer.compare(left.getPlan().getInput().getBatchIndex(),
                        right.getPlan().getInput().getBatchIndex());
            }
        });
        return okResultList;
    }

    private void increment(Map<Integer, Integer> countMap, int fileIndex) {
        Integer count = countMap.get(fileIndex);
        countMap.put(fileIndex, count == null ? 1 : count + 1);
    }

    private boolean allFilesHaveNoReportFeature(Map<Integer, Integer> fileBatchCountMap,
                                                Map<Integer, Integer> fileNoReportCountMap) {
        for (Map.Entry<Integer, Integer> entry : fileBatchCountMap.entrySet()) {
            Integer noReportCount = fileNoReportCountMap.get(entry.getKey());
            if (noReportCount == null || !noReportCount.equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }
}
