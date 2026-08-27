package com.example.healthreport.task;

import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 已提交事务的任务向本机线程池投递入口。
 * <p>调用方必须先完成任务与文件绑定事务，再调用本服务。</p>
 */
@Service
@Slf4j
public class AnalysisTaskExecutionService {

    private final ThreadPoolExecutor analysisExecutor;
    private final AnalysisTaskWorker taskWorker;
    private final TaskStateService taskStateService;

    public AnalysisTaskExecutionService(@Qualifier("analysisExecutor") ThreadPoolExecutor analysisExecutor,
                                        AnalysisTaskWorker taskWorker,
                                        TaskStateService taskStateService) {
        this.analysisExecutor = analysisExecutor;
        this.taskWorker = taskWorker;
        this.taskStateService = taskStateService;
    }

    /**
     * 事务提交后投递任务；拒绝或运行池异常时立即把 QUEUED 任务判为 SERVER_ERROR。
     */
    public void submit(final String taskId) {
        // 本方法的调用契约是事务提交后执行；先独立记录“创建”，确保线程池拒绝时
        // 仍然能从日志确认数据库里确实产生过这个任务。
        log.info("任务创建成功，taskId={}", taskId);
        try {
            analysisExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    taskWorker.run(taskId);
                }
            });
            log.info("任务投递成功并已入队，taskId={}", taskId);
        } catch (RuntimeException exception) {
            // 拒绝是有界队列打满的正常反压，不是缺陷；但任务已经建出来了，必须留痕。
            // 【不查线程池状态】拒绝本身就意味着「队列已满且 W 个线程都在忙」，
            // 再把 getQueue().size() 打出来不增加信息，只增加一处对执行器内部结构的依赖。
            log.warn("任务投递被线程池拒绝，taskId={}", taskId);
            try {
                taskStateService.markFailed(taskId, FailCode.SERVER_ERROR);
            } catch (RuntimeException ignoredException) {
                // 投递失败的对外口径固定为 SERVER_ERROR；状态写入异常由巡检继续收敛。
                IllegalStateException sanitizedException = new IllegalStateException(
                        "状态写入异常类型:" + ignoredException.getClass().getName());
                sanitizedException.setStackTrace(ignoredException.getStackTrace());
                log.error("线程池拒绝后的失败状态写入异常", sanitizedException);
            }
            throw new HealthReportException(FailCode.SERVER_ERROR, 500);
        }
    }
}
