package com.example.healthreport.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 分析业务线程池配置。
 * <p>只有一个有界任务池（P0-33e）：三阶段模型调用在任务线程内严格串行，
 * 不存在批次并发池；心跳走独立调度线程，避免被阻塞的任务线程拖死。</p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AnalysisExecutorProperties.class)
public class ExecutorConfig {

    /** 创建固定 W 线程、有界队列且直接拒绝的完整任务池。 */
    @Bean(name = "analysisExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor analysisExecutor(AnalysisExecutorProperties properties) {
        int workerCount = properties.calculateWorkerCount();
        logCapacity(properties, workerCount);
        return new ThreadPoolExecutor(workerCount, workerCount, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(properties.getQueueCapacity()),
                namedThreadFactory("health-analysis-"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** 心跳独占调度线程，不依赖被阻塞的任务池或批次池。 */
    @Bean(name = "analysisHeartbeatScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService analysisHeartbeatScheduler() {
        return Executors.newSingleThreadScheduledExecutor(namedThreadFactory("health-heartbeat-"));
    }

    private void logCapacity(AnalysisExecutorProperties properties, int workerCount) {
        int quotaUpperBound = properties.getModelConcurrencyQuota() / properties.getInstanceCount();
        int memoryUpperBound = (properties.getHeapBudgetMb() - properties.getWebReservedMb())
                / properties.getTaskPeakMb();
        log.info("分析线程池容量已确定：模型配额上界={}，内存上界={}，任务线程数={}，队列容量={}",
                quotaUpperBound, memoryUpperBound, workerCount, properties.getQueueCapacity());
    }

    private ThreadFactory namedThreadFactory(final String prefix) {
        final AtomicInteger sequence = new AtomicInteger(1);
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, prefix + sequence.getAndIncrement());
                thread.setDaemon(false);
                return thread;
            }
        };
    }
}
