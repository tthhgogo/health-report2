package com.example.healthreport.llm.extraction;

import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * LLM-A 校验链路的进程级观测计数。
 * <p>只记录数量，不记录模型响应、证据文本或健康数据。</p>
 */
@Component
public class ExtractionValidationCounters {

    /** Schema 契约失败次数，用于发现提示词或模型版本漂移。 */
    @Getter
    private final AtomicLong schemaMissCount = new AtomicLong();

    /** 条目证据缺失或来源回切失败次数。 */
    @Getter
    private final AtomicLong evidenceMissCount = new AtomicLong();

    /** OCR 字段通过编辑距离一级放宽的次数。 */
    @Getter
    private final AtomicLong ocrFuzzyMatchCount = new AtomicLong();

    /** 过敏内容疑似漏抽并触发任务降级的次数。 */
    @Getter
    private final AtomicLong allergenSuspectMissCount = new AtomicLong();

    /** 同块阳性过敏原候选没有被模型覆盖的段数。 */
    @Getter
    private final AtomicLong allergenPositiveUncoveredCount = new AtomicLong();

    /** 过敏结果为 UNKNOWN 而未进入提醒链路的条数。 */
    @Getter
    private final AtomicLong allergenUnknownCount = new AtomicLong();

    /** 条目引用不存在章节而被整条丢弃的数量。 */
    @Getter
    private final AtomicLong sectionRefMissCount = new AtomicLong();

    /** 指标状态由模型结合临床含义判定的条数。 */
    @Getter
    private final AtomicLong statusJudgedByModelCount = new AtomicLong();

    /** UNKNOWN 章节或无法继承的章节数量。 */
    @Getter
    private final AtomicLong sectionUnknownCount = new AtomicLong();

    /** 记录一次 Schema 契约失败。 */
    public void recordSchemaMiss() {
        schemaMissCount.incrementAndGet();
    }

    /** 记录一次条目证据失败。 */
    public void recordEvidenceMiss() {
        evidenceMissCount.incrementAndGet();
    }

    /** 记录一个通过编辑距离放宽的 OCR 字段。 */
    public void recordOcrFuzzyMatch() {
        ocrFuzzyMatchCount.incrementAndGet();
    }

    /** 记录一次过敏疑似漏抽降级。 */
    public void recordAllergenSuspectMiss() {
        allergenSuspectMissCount.incrementAndGet();
    }

    /** 记录一个未被覆盖的同块阳性候选段。 */
    public void recordAllergenPositiveUncovered() {
        allergenPositiveUncoveredCount.incrementAndGet();
    }

    /** 记录一条 UNKNOWN 过敏结果。 */
    public void recordAllergenUnknown() {
        allergenUnknownCount.incrementAndGet();
    }

    /** 记录一条无效章节引用。 */
    public void recordSectionRefMiss() {
        sectionRefMissCount.incrementAndGet();
    }

    /** 记录一条由模型判定状态的指标。 */
    public void recordStatusJudgedByModel() {
        statusJudgedByModelCount.incrementAndGet();
    }

    /** 记录一个 UNKNOWN 或无法继承的章节。 */
    public void recordSectionUnknown() {
        sectionUnknownCount.incrementAndGet();
    }
}
