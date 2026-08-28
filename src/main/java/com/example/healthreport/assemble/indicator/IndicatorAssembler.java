package com.example.healthreport.assemble.indicator;

import com.example.healthreport.assemble.sort.DisplayOrder;
import com.example.healthreport.constants.DisclaimerConstants;
import com.example.healthreport.llm.extraction.IndicatorConclusionBasis;
import com.example.healthreport.constants.EmptyStateConstants;
import com.example.healthreport.llm.extraction.IndicatorStatus;
import com.example.healthreport.llm.extraction.ValidatedExtractionOutput;
import lombok.Getter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 模块一健康指标组装器。
 * <p>本类原样展示 LLM-A 的状态和报告结论，不校正方向、不补通用参考范围。</p>
 */
@Service
public class IndicatorAssembler {

    /** 报告没有印刷参考范围时使用的固定占位，不代表任何医学参考值。 */
    private static final String REF_RANGE_NOT_PROVIDED = "报告未提供";

    /** 报告原文汇总数字旁的固定来源标记。 */
    private static final String REPORT_ORIGINAL_MARK = "（报告原文）";

    private final DisplayOrder displayOrder;

    public IndicatorAssembler(DisplayOrder displayOrder) {
        this.displayOrder = displayOrder;
    }

    /**
     * 组装健康指标总览和按章节平铺的卡片。
     *
     * @param output 已校验 LLM-A 结果
     * @param fileCount 报告文件数
     * @return 始终存在的模块一结果；无卡片时包含零值总览和空态
     */
    public Result assemble(ValidatedExtractionOutput output, int fileCount) {
        DisplayOrder.DisplayPlan plan = displayOrder.plan(output, fileCount);
        List<Group> groupList = new ArrayList<Group>(plan.getGroupList().size());
        int normalCount = 0;
        for (DisplayOrder.DisplayGroup displayGroup : plan.getGroupList()) {
            List<Card> cardList = new ArrayList<Card>();
            for (ValidatedExtractionOutput.Indicator indicator : plan.getIndicatorList()) {
                if (plan.groupOf(indicator) == displayGroup) {
                    cardList.add(toCard(indicator, plan.indicatorIdOf(indicator)));
                    if (indicator.getStatus() == IndicatorStatus.NORMAL) {
                        normalCount++;
                    }
                }
            }
            if (!cardList.isEmpty()) {
                groupList.add(new Group(displayGroup.getGroupKey(), displayGroup.getDisplayName(),
                        cardList));
            }
        }

        int cardCount = plan.getIndicatorList().size();
        Overview overview = overview(output.getReportOverviewList(), cardCount, normalCount);
        String emptyState = cardCount == 0 ? EmptyStateConstants.MODULE_ONE : null;
        return new Result(overview, groupList, emptyState, DisclaimerConstants.MODULE_ONE);
    }

    private Card toCard(ValidatedExtractionOutput.Indicator indicator, String indicatorId) {
        // Schema 允许 refRange 为空串且校验流水线不转 null，只判 null 会让卡片显示成空白，
        // 而不是约定的「报告未提供」。仍然只做占位，绝不填充通用参考值。
        String rawRefRange = indicator.getRefRange();
        String refRange = rawRefRange == null || rawRefRange.trim().isEmpty()
                ? REF_RANGE_NOT_PROVIDED : rawRefRange;
        // 报告没印结论的指标走参考值准入，展示固定文案并标明是系统判定的，
        // 不把系统推导冒充成报告原文（需求 §5-3 要求结论直接引用原文）。
        // 两种依据给不同文案：数值说「在参考范围内」，定性说「符合报告参考值」，
        // 都只陈述「与报告给的参考值一致」这个事实，不做任何医学结论。
        String conclusionText = indicator.getConclusionText();
        boolean conclusionGenerated = false;
        if (indicator.getConclusionBasis() == IndicatorConclusionBasis.REFERENCE_RANGE_IN_RANGE) {
            conclusionText = DisclaimerConstants.INDICATOR_IN_REFERENCE_RANGE;
            conclusionGenerated = true;
        } else if (indicator.getConclusionBasis()
                == IndicatorConclusionBasis.REFERENCE_VALUE_MATCH) {
            conclusionText = DisclaimerConstants.INDICATOR_MATCHES_REFERENCE_VALUE;
            conclusionGenerated = true;
        }
        return new Card(indicatorId, indicator.getName(), indicator.getValue(), indicator.getUnit(),
                refRange, conclusionText, conclusionGenerated, indicator.getStatus());
    }

    private Overview overview(List<ValidatedExtractionOutput.ReportOverview> reportOverviewList,
                              int cardCount, int normalCardCount) {
        // 空模块按方案固定展示零值总览，不能让报告级项目数看起来像已生成了指标卡片。
        if (cardCount > 0 && !reportOverviewList.isEmpty()) {
            int totalCount = 0;
            int abnormalCount = 0;
            Set<Integer> includedFileIndexSet =
                    new LinkedHashSet<Integer>(reportOverviewList.size());
            for (ValidatedExtractionOutput.ReportOverview reportOverview : reportOverviewList) {
                // 校验链路已验证数字来源；同一文件只采用首个有效汇总，避免跨批重复累计。
                if (includedFileIndexSet.add(reportOverview.getFileIndex())) {
                    totalCount += reportOverview.getTotalCount();
                    abnormalCount += reportOverview.getAbnormalCount();
                }
            }
            int normalCount = Math.max(0, totalCount - abnormalCount);
            return new Overview(totalCount, normalCount, abnormalCount,
                    percentage(abnormalCount, totalCount), true, REPORT_ORIGINAL_MARK);
        }
        int abnormalCount = cardCount - normalCardCount;
        return new Overview(cardCount, normalCardCount, abnormalCount,
                percentage(abnormalCount, cardCount), false, null);
    }

    private int percentage(int abnormalCount, int totalCount) {
        if (totalCount == 0) {
            return 0;
        }
        return (int) Math.round(abnormalCount * 100D / totalCount);
    }

    /** 模块一完整返回结构。 */
    @Getter
    public static final class Result {
        private final Overview overview;
        private final List<Group> groupList;
        private final String emptyState;
        private final String disclaimer;

        private Result(Overview overview, List<Group> groupList, String emptyState,
                       String disclaimer) {
            this.overview = overview;
            this.groupList = java.util.Collections.unmodifiableList(
                    new ArrayList<Group>(groupList));
            this.emptyState = emptyState;
            this.disclaimer = disclaimer;
        }
    }

    /** 模块一总览条；reportOriginal 为真时数字来自报告印刷汇总。 */
    @Getter
    public static final class Overview {
        private final int totalCount;
        private final int normalCount;
        private final int abnormalCount;
        private final int abnormalPercentage;
        private final boolean reportOriginal;
        private final String sourceMark;

        private Overview(int totalCount, int normalCount, int abnormalCount,
                         int abnormalPercentage, boolean reportOriginal, String sourceMark) {
            this.totalCount = totalCount;
            this.normalCount = normalCount;
            this.abnormalCount = abnormalCount;
            this.abnormalPercentage = abnormalPercentage;
            this.reportOriginal = reportOriginal;
            this.sourceMark = sourceMark;
        }
    }

    /** 一个有效章节下的指标卡片集合。 */
    @Getter
    public static final class Group {
        private final String groupKey;
        private final String displayName;
        private final List<Card> cardList;

        private Group(String groupKey, String displayName, List<Card> cardList) {
            this.groupKey = groupKey;
            this.displayName = displayName;
            this.cardList = java.util.Collections.unmodifiableList(new ArrayList<Card>(cardList));
        }
    }

    /**
     * 健康指标卡片；标签颜色由 status 决定。
     *
     * <p>标签文字优先使用报告结论原文；报告没印结论、经参考范围比较准入的指标，
     * 使用固定文案并把 {@code conclusionGenerated} 置为 true，
     * <b>前端必须据此在视觉上区分</b>——否则用户会以为那句话是报告上写的。</p>
     */
    @Getter
    public static final class Card {
        private final String indicatorId;
        private final String name;
        private final String value;
        private final String unit;
        private final String refRange;
        private final String conclusionText;
        /** true 表示结论由系统按参考范围判定，不是报告原文。 */
        private final boolean conclusionGenerated;
        private final IndicatorStatus status;

        private Card(String indicatorId, String name, String value, String unit, String refRange,
                     String conclusionText, boolean conclusionGenerated, IndicatorStatus status) {
            this.indicatorId = indicatorId;
            this.name = name;
            this.value = value;
            this.unit = unit;
            this.refRange = refRange;
            this.conclusionText = conclusionText;
            this.conclusionGenerated = conclusionGenerated;
            this.status = status;
        }
    }
}
