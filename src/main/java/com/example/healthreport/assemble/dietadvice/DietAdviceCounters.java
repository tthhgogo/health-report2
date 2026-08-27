package com.example.healthreport.assemble.dietadvice;

import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 模块三进程级安全计数，只记录条数，不记录建议原文或模型内容。
 */
@Component
public class DietAdviceCounters {

    /** 高危词命中后退出结构化链路的条数，用于安全闸观测。 */
    @Getter
    private final AtomicLong highRiskSuppressedCount = new AtomicLong();

    /** 显式 OTHER 或被安全闸抑制的条数，用于评估枚举覆盖度。 */
    @Getter
    private final AtomicLong adviceOtherCount = new AtomicLong();

    /** 记录一条被高危安全闸抑制的建议。 */
    public void recordHighRiskSuppressed() {
        highRiskSuppressedCount.incrementAndGet();
    }

    /** 记录一条按 OTHER 路径处理的建议。 */
    public void recordAdviceOther() {
        adviceOtherCount.incrementAndGet();
    }
}
