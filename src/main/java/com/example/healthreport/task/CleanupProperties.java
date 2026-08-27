package com.example.healthreport.task;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 生命周期清理巡检的单轮批量参数。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "health-report.cleanup")
public class CleanupProperties {

    /**
     * 单轮候选上限。
     *
     * <p><b>存在的理由不是省 CPU，是防故障放大。</b> 任务行只有在文件与结果都清干净之后
     * 才会被物理删除；对象存储一旦不可用，task 行就删不掉、候选集不再被两小时保留期钳住、
     * 开始无限增长——而这个巡检正是唯一能把它恢复回来的东西，却会随候选数线性变慢，
     * 最终单轮超时或 OOM。<b>越是需要它工作的时候它越跑不动。</b>
     * 有了上限，最坏情况从「巡检死掉」变成「巡检每轮推进固定条数」。</p>
     *
     * <p>调大要同时看单轮耗时是否仍远小于五分钟的调度间隔。</p>
     */
    private int batchSize = 500;
}
