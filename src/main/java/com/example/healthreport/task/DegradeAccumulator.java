package com.example.healthreport.task;

import com.example.healthreport.support.PartialReason;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 任务级降级原因累加器。
 * <p>三个原因只增不减；抑制范围按 OR 累积，主原因仅用于单值持久化和展示。</p>
 */
@Slf4j
public class DegradeAccumulator {

    private final AtomicBoolean pageTruncated = new AtomicBoolean(false);
    private final AtomicBoolean batchUnreadable = new AtomicBoolean(false);
    private final AtomicBoolean allergenSuspectMiss = new AtomicBoolean(false);
    private final AtomicBoolean schemaItemDropped = new AtomicBoolean(false);
    private final AtomicBoolean dietRequirementDropped = new AtomicBoolean(false);

    /**
     * 记录页数截断；重复调用保持幂等。
     * 只在状态首次翻转时记一条日志——降级是需要留痕的决策，
     * 但每次调用都记会在批次并发下刷屏。日志只含原因枚举，不含任何报告内容。
     */
    public void recordPageTruncated() {
        if (pageTruncated.compareAndSet(false, true)) {
            log.info("任务降级：命中 {}", PartialReason.PAGE_TRUNCATED);
        }
    }

    /** 记录批次不可读；重复调用保持幂等，仅首次翻转时留痕。 */
    public void recordBatchUnreadable() {
        if (batchUnreadable.compareAndSet(false, true)) {
            log.info("任务降级：命中 {}，模块三与模块四不再输出", PartialReason.BATCH_UNREADABLE);
        }
    }

    /** 记录疑似漏抽过敏原；重复调用保持幂等，仅首次翻转时留痕。 */
    public void recordAllergenSuspectMiss() {
        if (allergenSuspectMiss.compareAndSet(false, true)) {
            log.info("任务降级：命中 {}，菜品推荐不再输出", PartialReason.ALLERGEN_SUSPECT_MISS);
        }
    }

    /**
     * 记录个别条目因不合 Schema 被剔除；重复调用保持幂等，仅首次翻转时留痕。
     * <p>剔除明细由 {@code ExtractionSchemaValidator} 记日志，这里只翻转降级标志。</p>
     *
     * @param dietRequirementDroppedInBatch 本次剔除是否含 {@code dietRequirements}；
     *     含则必须抑制菜品推荐——那一类在模块四生成 REJECT 方向集合，少一条等于少一道排除
     */
    public void recordSchemaItemDropped(boolean dietRequirementDroppedInBatch) {
        if (schemaItemDropped.compareAndSet(false, true)) {
            log.info("任务降级：命中 {}，个别条目已剔除", PartialReason.SCHEMA_ITEM_DROPPED);
        }
        if (dietRequirementDroppedInBatch && dietRequirementDropped.compareAndSet(false, true)) {
            log.info("任务降级：命中 {}，菜品推荐不再输出", PartialReason.DIET_REQUIREMENT_DROPPED);
        }
    }

    /** 是否命中过任何部分结果原因。 */
    public boolean partial() {
        return pageTruncated.get() || batchUnreadable.get() || allergenSuspectMiss.get()
                || schemaItemDropped.get() || dietRequirementDropped.get();
    }

    /** 页数或批次不完整时抑制饮食建议。 */
    public boolean suppressDietAdvice() {
        return pageTruncated.get() || batchUnreadable.get();
    }

    /** 任一降级原因都抑制菜品推荐；剔除了饮食注意同样必须抑制。 */
    public boolean suppressDishRecommend() {
        return pageTruncated.get() || batchUnreadable.get() || allergenSuspectMiss.get()
                || dietRequirementDropped.get();
    }

    /**
     * 按抑制范围从大到小返回单个主原因，与原因命中顺序无关。
     *
     * @return 主降级原因；未降级时返回 {@code null}
     */
    public PartialReason primaryReason() {
        if (pageTruncated.get()) {
            return PartialReason.PAGE_TRUNCATED;
        }
        if (batchUnreadable.get()) {
            return PartialReason.BATCH_UNREADABLE;
        }
        if (allergenSuspectMiss.get()) {
            return PartialReason.ALLERGEN_SUSPECT_MISS;
        }
        // 抑制菜品推荐，排在只标记不抑制的那一类之前。
        if (dietRequirementDropped.get()) {
            return PartialReason.DIET_REQUIREMENT_DROPPED;
        }
        // 抑制范围为空，排在最后：任何其他原因都比它更需要被展示。
        if (schemaItemDropped.get()) {
            return PartialReason.SCHEMA_ITEM_DROPPED;
        }
        return null;
    }
}
