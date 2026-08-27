package com.example.healthreport.task;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 分析任务线程池容量参数。
 * <p>线程数同时受模型并发配额和 JVM 堆预算约束，任何一侧都不能单独决定容量。</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "health-report.executor")
public class AnalysisExecutorProperties {

    /** 模型服务允许的总并发数 C。 */
    private int modelConcurrencyQuota = 16;

    /** 共同分享模型配额的应用实例数。 */
    private int instanceCount = 1;

    /** 可用于整个 JVM 的堆预算，单位 MiB。 */
    private int heapBudgetMb = 2048;

    /** Web 层预留的堆预算，单位 MiB。 */
    private int webReservedMb = 512;

    /** 单个分析任务的峰值堆占用预算，单位 MiB。 */
    private int taskPeakMb = 256;

    /** analysisExecutor 的有界等待队列容量。 */
    private int queueCapacity = 8;

    /** 心跳更新周期，生产默认三十秒；测试可以缩短。 */
    private long heartbeatIntervalSeconds = 30L;

    /**
     * 按模型配额与堆预算两个上界取最小值。
     *
     * @return 当前实例允许并行执行的完整任务数 W
     */
    public int calculateWorkerCount() {
        validate();
        int quotaUpperBound = modelConcurrencyQuota / (4 * instanceCount);
        int memoryUpperBound = (heapBudgetMb - webReservedMb) / taskPeakMb;
        int workerCount = Math.min(quotaUpperBound, memoryUpperBound);
        if (workerCount < 1) {
            throw new IllegalStateException("线程池容量计算结果必须大于零");
        }
        return workerCount;
    }

    /** 校验所有容量配置，避免错误配置延迟到首次请求才暴露。 */
    public void validate() {
        if (modelConcurrencyQuota <= 0 || instanceCount <= 0 || heapBudgetMb <= 0
                || webReservedMb < 0 || taskPeakMb <= 0 || queueCapacity <= 0
                || heartbeatIntervalSeconds <= 0L) {
            throw new IllegalStateException("线程池容量配置必须为有效正值");
        }
        if (heapBudgetMb <= webReservedMb) {
            throw new IllegalStateException("堆预算必须大于Web层预留");
        }
    }
}
