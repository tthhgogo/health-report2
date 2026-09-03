package com.example.healthreport.task;

import com.example.healthreport.support.PartialReason;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 任务级降级原因累加器。
 * <p>两个原因只增不减；主原因仅用于单值持久化与展示，
 * 抑制状态必须从本类读取，禁止从单个 partialReason 反推。</p>
 */
@Slf4j
public class DegradeAccumulator {

    private final AtomicBoolean schemaItemDropped = new AtomicBoolean(false);
    private final AtomicBoolean dietTagDropped = new AtomicBoolean(false);

    /**
     * 记录阶段一/二的条目剔除；重复调用保持幂等，仅首次翻转时留痕。
     * <p>剔除明细由校验层记日志，这里只翻转降级标志。</p>
     */
    public void recordSchemaItemDropped() {
        if (schemaItemDropped.compareAndSet(false, true)) {
            log.info("任务降级：命中 {}，个别条目已剔除", PartialReason.SCHEMA_ITEM_DROPPED);
        }
    }

    /** 记录阶段三的饮食标签剔除；模块四整体抑制。重复调用保持幂等。 */
    public void recordDietTagDropped() {
        if (dietTagDropped.compareAndSet(false, true)) {
            log.info("任务降级：命中 {}，菜品推荐不再输出", PartialReason.DIET_TAG_DROPPED);
        }
    }

    /** 是否命中过任何部分结果原因。 */
    public boolean partial() {
        return schemaItemDropped.get() || dietTagDropped.get();
    }

    /** 任一饮食标签被剔除即抑制菜品推荐——缺失的拒绝标签会反向放出菜品。 */
    public boolean suppressDishRecommend() {
        return dietTagDropped.get();
    }

    /**
     * 按抑制范围从大到小返回单个主原因（设计方案 §4.4：单值列，DIET_TAG_DROPPED 优先）。
     *
     * @return 主降级原因；未降级时返回 {@code null}
     */
    public PartialReason primaryReason() {
        if (dietTagDropped.get()) {
            return PartialReason.DIET_TAG_DROPPED;
        }
        if (schemaItemDropped.get()) {
            return PartialReason.SCHEMA_ITEM_DROPPED;
        }
        return null;
    }
}
