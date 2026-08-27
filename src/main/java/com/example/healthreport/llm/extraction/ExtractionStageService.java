package com.example.healthreport.llm.extraction;

import com.example.healthreport.parse.ParsePlan;
import com.example.healthreport.parse.ParsedFile;
import com.example.healthreport.parse.ParsedPage;
import com.example.healthreport.parse.segment.Segment;
import com.example.healthreport.task.DegradeAccumulator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM-A 阶段编排：分批 → 执行 → 校验合并。
 *
 * <p>本类只负责把三步按顺序串起来并保证它们看到的是<b>同一份 segment</b>；
 * 分批规则在 {@link BatchPlanner}、并发与降级在 {@link ExtractionBatchExecutor}、
 * 校验与合并在 {@link ExtractionValidationPipeline}，这里一条业务规则都不重复实现。</p>
 */
@Slf4j
@Service
public class ExtractionStageService {

    private final BatchPlanner batchPlanner;
    private final ExtractionBatchExecutor batchExecutor;
    private final ExtractionValidationPipeline validationPipeline;

    public ExtractionStageService(BatchPlanner batchPlanner,
                                  ExtractionBatchExecutor batchExecutor,
                                  ExtractionValidationPipeline validationPipeline) {
        this.batchPlanner = batchPlanner;
        this.batchExecutor = batchExecutor;
        this.validationPipeline = validationPipeline;
    }

    /**
     * 执行整个抽取阶段。
     *
     * @param parsePlan 已通过页数预算与零 segment 裁决的解析计划
     * @return 已完成 Schema、引用、来源与同一性校验的任务级抽取结果
     */
    public ValidatedExtractionOutput extract(ParsePlan parsePlan, DegradeAccumulator degradeAccumulator) {
        if (parsePlan == null || degradeAccumulator == null) {
            throw new IllegalArgumentException("抽取阶段参数不能为空");
        }
        long startMillis = System.currentTimeMillis();
        List<ExtractionBatchPlan> batchPlanList = batchPlanner.plan(parsePlan);
        List<ExtractionBatchResult> batchResultList =
                batchExecutor.execute(batchPlanList, degradeAccumulator);
        List<Segment> allSegmentList = allSegments(parsePlan);
        ValidatedExtractionOutput output = validationPipeline.validateAndMerge(
                batchResultList, allSegmentList, degradeAccumulator);
        // 【只记过程量，不记内容量】批次数、块数、耗时描述的是「跑了多大一摊活」；
        // 而「抽出几条指标 / 几个过敏原」是内容量——它和同一条日志里的 taskId 拼起来
        // 就成了这个用户的健康画像，落在 AGENTS.md §6 白名单红线里。想看内容去开 SensitiveLog。
        log.info("抽取阶段完成，批次数={}，参与回切的文字块数={}，耗时={}ms",
                batchPlanList.size(), allSegmentList.size(),
                System.currentTimeMillis() - startMillis);
        return output;
    }

    /**
     * 收集校验层用于回切的全部 segment。
     * <p><b>必须与 {@link BatchPlanner} 取自同一处</b>——两边都读
     * {@code parsePlan.getReadableFileList()} 的页列表。若这里换成「解析出来的全部 segment」，
     * 被页数预算截断掉的那些也会进来，来源校验就会放过实际上没有发给模型的引用。</p>
     */
    private List<Segment> allSegments(ParsePlan parsePlan) {
        List<Segment> allSegmentList = new ArrayList<Segment>();
        for (ParsedFile file : parsePlan.getReadableFileList()) {
            for (ParsedPage page : file.getPageList()) {
                allSegmentList.addAll(page.getSegmentList());
            }
        }
        return allSegmentList;
    }
}
