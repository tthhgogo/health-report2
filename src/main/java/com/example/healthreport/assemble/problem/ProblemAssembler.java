package com.example.healthreport.assemble.problem;

import com.example.healthreport.assemble.sort.DisplayOrder;
import com.example.healthreport.constants.DisclaimerConstants;
import com.example.healthreport.constants.EmptyStateConstants;
import com.example.healthreport.llm.extraction.SummaryCategory;
import com.example.healthreport.llm.extraction.ValidatedExtractionOutput;
import lombok.Getter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 模块二健康问题组装器。
 * <p>三类来源只认 LLM-A 的准入字段；本类不从指标状态派生健康问题，也不做风险排序。</p>
 */
@Service
public class ProblemAssembler {

    /** 来源标签中章节与检查项之间的固定连接符，仅用于展示字段拼接。 */
    private static final String SOURCE_SEPARATOR = "–";

    /** 多段报告原文保持段边界时使用的展示换行符，不改变段内原文。 */
    private static final String RAW_TEXT_SEPARATOR = "\n";

    private final DisplayOrder displayOrder;

    public ProblemAssembler(DisplayOrder displayOrder) {
        this.displayOrder = displayOrder;
    }

    /**
     * 按唯一排序计划组装健康问题，数值与文字检查项在前，总检结论在后。
     */
    public Result assemble(ValidatedExtractionOutput output, int fileCount) {
        DisplayOrder.DisplayPlan plan = displayOrder.plan(output, fileCount);
        List<Item> itemList = new ArrayList<Item>(plan.getSectionFindingList().size()
                + plan.getSummaryConclusionList().size());
        for (DisplayOrder.SectionFinding sectionFinding : plan.getSectionFindingList()) {
            if (sectionFinding.isNumeric()) {
                ValidatedExtractionOutput.Indicator indicator =
                        (ValidatedExtractionOutput.Indicator) sectionFinding.getItem();
                if (indicator.isIncludeInHealthProblems()) {
                    itemList.add(numeric(output, plan, indicator));
                }
            } else {
                ValidatedExtractionOutput.TextualFinding finding =
                        (ValidatedExtractionOutput.TextualFinding) sectionFinding.getItem();
                if (finding.isIncludeInHealthProblems()) {
                    itemList.add(textual(output, plan, finding));
                }
            }
        }
        for (ValidatedExtractionOutput.SummaryConclusion summary : plan.getSummaryConclusionList()) {
            if (summary.isIncludeInHealthProblems() && hasHealthOrDietCategory(summary)) {
                itemList.add(summary(output, plan, summary));
            }
        }
        String emptyState = itemList.isEmpty() ? EmptyStateConstants.MODULE_TWO : null;
        return new Result(itemList, emptyState, DisclaimerConstants.MODULE_TWO);
    }

    private Item numeric(ValidatedExtractionOutput output, DisplayOrder.DisplayPlan plan,
                         ValidatedExtractionOutput.Indicator indicator) {
        boolean generated = indicator.getProblemName() == null;
        String displayName = generated
                ? indicator.getName() + " " + indicator.getConclusionText()
                : indicator.getProblemName();
        DisplayOrder.DisplayGroup group = plan.groupOf(indicator);
        List<String> rawTextList = output.rawTextList(indicator.getSegmentIdList());
        return new Item(SourceType.INDICATOR_NUMERIC, displayName, generated,
                group.getDisplayName() + SOURCE_SEPARATOR + indicator.getName(),
                rawTextList, joinRawText(rawTextList), plan.indicatorIdOf(indicator));
    }

    private Item textual(ValidatedExtractionOutput output, DisplayOrder.DisplayPlan plan,
                         ValidatedExtractionOutput.TextualFinding finding) {
        DisplayOrder.DisplayGroup group = plan.groupOf(finding);
        List<String> rawTextList = output.rawTextList(finding.getSegmentIdList());
        return new Item(SourceType.INDICATOR_TEXTUAL, finding.getTitle(), false,
                group.getDisplayName() + SOURCE_SEPARATOR + finding.getTitle(),
                rawTextList, joinRawText(rawTextList), null);
    }

    private Item summary(ValidatedExtractionOutput output, DisplayOrder.DisplayPlan plan,
                         ValidatedExtractionOutput.SummaryConclusion summary) {
        DisplayOrder.DisplayGroup group = plan.groupOf(summary);
        List<String> rawTextList = output.rawTextList(summary.getSegmentIdList());
        String sourceLabel = group.getDisplayName();
        if (summary.getItemNo() != null) {
            sourceLabel += "第" + summary.getItemNo() + "条";
        }
        String rawText = joinRawText(rawTextList);
        return new Item(SourceType.SUMMARY, rawText, false, sourceLabel,
                rawTextList, rawText, null);
    }

    private boolean hasHealthOrDietCategory(ValidatedExtractionOutput.SummaryConclusion summary) {
        return summary.getCategoryList().contains(SummaryCategory.HEALTH_PROBLEM)
                || summary.getCategoryList().contains(SummaryCategory.DIET_ADVICE);
    }

    private String joinRawText(List<String> rawTextList) {
        StringBuilder builder = new StringBuilder();
        for (String rawText : rawTextList) {
            if (builder.length() > 0) {
                builder.append(RAW_TEXT_SEPARATOR);
            }
            builder.append(rawText);
        }
        return builder.toString();
    }

    /** 健康问题来源类型，前端仅为数值指标显示跳转入口。 */
    public enum SourceType {
        /** 有数值、有结论的指标。 */
        INDICATOR_NUMERIC,
        /** 无数值但有结论的文字检查项。 */
        INDICATOR_TEXTUAL,
        /** 报告总检或汇总结论。 */
        SUMMARY
    }

    /** 模块二完整返回结构。 */
    @Getter
    public static final class Result {
        private final List<Item> itemList;
        private final String emptyState;
        private final String disclaimer;

        private Result(List<Item> itemList, String emptyState, String disclaimer) {
            this.itemList = Collections.unmodifiableList(new ArrayList<Item>(itemList));
            this.emptyState = emptyState;
            this.disclaimer = disclaimer;
        }
    }

    /** 一条仅引用报告原文的健康问题。 */
    @Getter
    public static final class Item {
        private final SourceType sourceType;
        private final String displayName;
        private final boolean displayNameGenerated;
        private final String sourceLabel;
        private final List<String> rawTextList;
        private final String rawText;
        private final String indicatorId;

        private Item(SourceType sourceType, String displayName, boolean displayNameGenerated,
                     String sourceLabel, List<String> rawTextList, String rawText,
                     String indicatorId) {
            this.sourceType = sourceType;
            this.displayName = displayName;
            this.displayNameGenerated = displayNameGenerated;
            this.sourceLabel = sourceLabel;
            this.rawTextList = Collections.unmodifiableList(new ArrayList<String>(rawTextList));
            this.rawText = rawText;
            this.indicatorId = indicatorId;
        }
    }
}
