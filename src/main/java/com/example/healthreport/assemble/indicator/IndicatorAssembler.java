package com.example.healthreport.assemble.indicator;

import com.example.healthreport.constants.DisclaimerConstants;
import com.example.healthreport.constants.EmptyStateConstants;
import com.example.healthreport.llm.extraction.IndicatorStatus;
import com.example.healthreport.llm.extraction.IndicatorsResult;
import com.example.healthreport.render.PageImageSequence;
import lombok.Getter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 模块一健康指标组装器（设计方案 §5）。
 * <p>Java 只做取值、拼接与计数，不新增医疗语义：状态原样下发，
 * 展示结论按 {@code conclusionGenerated} 二选一，总览数字直接采信不交叉核对。</p>
 */
@Service
public class IndicatorAssembler {

    /** 报告没有印刷参考范围时使用的固定占位，不代表任何医学参考值。 */
    private static final String REF_RANGE_NOT_PROVIDED = "报告未提供";

    /** section 为 null 时的固定分组文案（设计方案 §5.3）。 */
    private static final String UNSECTIONED_GROUP_NAME = "未标注章节";

    /**
     * 组装模块一。
     *
     * @param images 全局图序列，用于多文件分组名的文件归属查表
     */
    public Result assemble(IndicatorsResult indicators, PageImageSequence images, int fileCount) {
        List<Group> groupList = new ArrayList<Group>(indicators.getSections().size());
        int cardSequence = 0;
        for (IndicatorsResult.Section section : indicators.getSections()) {
            List<Card> cardList = new ArrayList<Card>(section.getIndicators().size());
            for (IndicatorsResult.Indicator indicator : section.getIndicators()) {
                cardSequence++;
                cardList.add(toCard("ind-" + cardSequence, indicator));
            }
            String baseName = section.getSection() == null || section.getSection().trim().isEmpty()
                    ? UNSECTIONED_GROUP_NAME : section.getSection();
            String displayName = fileCount > 1
                    ? "报告" + (images.locate(section.getPage()).getFileIndex() + 1) + "-" + baseName
                    : baseName;
            groupList.add(new Group(displayName, section.getPage(), cardList));
        }

        Overview overview = overview(indicators.getOverview());
        String emptyState = groupList.isEmpty() ? EmptyStateConstants.MODULE_ONE : null;
        return new Result(overview, groupList, emptyState, DisclaimerConstants.MODULE_ONE);
    }

    private Card toCard(String indicatorId, IndicatorsResult.Indicator indicator) {
        String refRange = indicator.getRefRange() == null || indicator.getRefRange().trim().isEmpty()
                ? REF_RANGE_NOT_PROVIDED : indicator.getRefRange();
        String conclusionText;
        if (indicator.isConclusionGenerated()) {
            // 系统按参考值判定：数值项与定性项各自的固定文案，前端必须做视觉区分。
            conclusionText = isQualitative(indicator.getValue())
                    ? DisclaimerConstants.INDICATOR_MATCHES_REFERENCE_VALUE
                    : DisclaimerConstants.INDICATOR_IN_REFERENCE_RANGE;
        } else {
            // 报告印了结论：展示 status 对应的标准文案（设计方案 §5.1，产品确认口径）。
            conclusionText = statusLabel(indicator.getStatus());
        }
        return new Card(indicatorId, indicator.getName(), indicator.getValue(),
                indicator.getUnit(), refRange, conclusionText,
                indicator.isConclusionGenerated(), indicator.getStatus());
    }

    /** 定性结果（阴性、阳性等非数值开头）走「符合报告参考值」文案。 */
    private boolean isQualitative(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        char first = value.trim().charAt(0);
        return !(first >= '0' && first <= '9') && first != '.' && first != '-' && first != '+';
    }

    /** status 的标准展示文案；这不是 Java 的语义判断，只是枚举到文案的固定映射。 */
    private String statusLabel(IndicatorStatus status) {
        switch (status) {
            case HIGH:
                return "偏高";
            case LOW:
                return "偏低";
            case ABNORMAL:
                return "异常";
            case NORMAL:
            default:
                return "正常";
        }
    }

    /** 两个数字直接采信；totalCount 为 0 或 overview 缺失时总览条不展示（返回 null）。 */
    private Overview overview(IndicatorsResult.Overview source) {
        if (source == null || source.getTotalCount() <= 0) {
            return null;
        }
        int normalCount = Math.max(0, source.getTotalCount() - source.getAbnormalCount());
        // 两个数字直接采信，但占比是本层自己的除法：abnormal > total（模型自相矛盾）时
        // 钳到 100%，避免页面出现 130% 这类展示事故——这是展示计算的边界，不是语义改写。
        int percentage = Math.min(100, Math.max(0, (int) Math.round(
                source.getAbnormalCount() * 100D / source.getTotalCount())));
        return new Overview(source.getTotalCount(), normalCount, source.getAbnormalCount(),
                percentage, "REPORT".equals(source.getSource()));
    }

    /** 模块一完整返回结构。 */
    @Getter
    public static final class Result {
        private final Overview overview;
        private final List<Group> groupList;
        private final String emptyState;
        private final String disclaimer;

        Result(Overview overview, List<Group> groupList, String emptyState, String disclaimer) {
            this.overview = overview;
            this.groupList = Collections.unmodifiableList(new ArrayList<Group>(groupList));
            this.emptyState = emptyState;
            this.disclaimer = disclaimer;
        }
    }

    /** 模块一总览条；reportOriginal 为真时两个数字来自报告印刷汇总。 */
    @Getter
    public static final class Overview {
        private final int totalCount;
        private final int normalCount;
        private final int abnormalCount;
        private final int abnormalPercentage;
        private final boolean reportOriginal;

        Overview(int totalCount, int normalCount, int abnormalCount,
                 int abnormalPercentage, boolean reportOriginal) {
            this.totalCount = totalCount;
            this.normalCount = normalCount;
            this.abnormalCount = abnormalCount;
            this.abnormalPercentage = abnormalPercentage;
            this.reportOriginal = reportOriginal;
        }
    }

    /** 一个章节一张卡片区；数组顺序即展示顺序。 */
    @Getter
    public static final class Group {
        private final String displayName;
        private final int page;
        private final List<Card> cardList;

        Group(String displayName, int page, List<Card> cardList) {
            this.displayName = displayName;
            this.page = page;
            this.cardList = Collections.unmodifiableList(new ArrayList<Card>(cardList));
        }
    }

    /**
     * 健康指标卡片；标签颜色由 status 决定。
     * <p>{@code conclusionGenerated=true} 表示结论由系统按参考值判定，不是报告原文，
     * <b>前端必须据此在视觉上区分</b>（需求 §5-3 第 80 行）。</p>
     */
    @Getter
    public static final class Card {
        private final String indicatorId;
        private final String name;
        private final String value;
        private final String unit;
        private final String refRange;
        private final String conclusionText;
        private final boolean conclusionGenerated;
        private final IndicatorStatus status;

        Card(String indicatorId, String name, String value, String unit, String refRange,
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
