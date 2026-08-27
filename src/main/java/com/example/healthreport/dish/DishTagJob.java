package com.example.healthreport.dish;

import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** 菜品打标与清理唯一调度 Handler，保证整条链路共享同一业务日。 */
@Slf4j
@Component
public class DishTagJob {

    private final DishTagService tagService;
    private final DishTagCleanupService cleanupService;

    public DishTagJob(DishTagService tagService, DishTagCleanupService cleanupService) {
        this.tagService = tagService;
        this.cleanupService = cleanupService;
    }

    /** 先打标后清理；业务日只在本方法获取一次并逐层传递。 */
    @XxlJob("dishTagJob")
    public void execute() {
        LocalDate bizDate = LocalDate.now();
        long startMillis = System.currentTimeMillis();
        // 这个 Job 是在线菜品推荐的唯一数据来源，跑没跑过、跑到哪一步全靠日志。
        // 只记开始与结束两条：中间的分维度明细由 DishTagService 自己记。
        log.info("菜品打标调度开始，业务日={}", bizDate);
        tagService.run(bizDate);
        cleanupService.run(bizDate);
        log.info("菜品打标调度完成，业务日={}，总耗时={}ms", bizDate, System.currentTimeMillis() - startMillis);
    }
}
