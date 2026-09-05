package com.example.healthreport.assemble.indicator;

import com.example.healthreport.constants.DisclaimerConstants;
import com.example.healthreport.constants.EmptyStateConstants;
import com.example.healthreport.llm.extraction.IndicatorStatus;
import com.example.healthreport.llm.extraction.IndicatorsResult;
import com.example.healthreport.render.PageImageSequence;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
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
    @ApiModel(value = "IndicatorResult", description = "模块一（健康指标）完整返回结构")
    @Getter
    public static final class Result {

        @ApiModelProperty(value = "总览条；totalCount 为 0 或模型未给总览时为 null，前端不展示总览")
        private final Overview overview;

        @ApiModelProperty(value = "按章节分组的指标卡片区；数组顺序即展示顺序", required = true)
        private final List<Group> groupList;

        @ApiModelProperty(value = "分组为空时的空态文案；有内容时为 null",
                example = "本次报告未提取到带明确结论的指标项")
        private final String emptyState;

        @ApiModelProperty(value = "模块底部声明", required = true)
        private final String disclaimer;

        @JsonCreator
        Result(@JsonProperty("overview") Overview overview,
               @JsonProperty("groupList") List<Group> groupList,
               @JsonProperty("emptyState") String emptyState,
               @JsonProperty("disclaimer") String disclaimer) {
            this.overview = overview;
            this.groupList = Collections.unmodifiableList(new ArrayList<Group>(groupList));
            this.emptyState = emptyState;
            this.disclaimer = disclaimer;
        }
    }

    /** 模块一总览条；reportOriginal 为真时两个数字来自报告印刷汇总。 */
    @ApiModel(value = "IndicatorOverview", description = "模块一总览条")
    @Getter
    public static final class Overview {

        @ApiModelProperty(value = "指标总数", required = true, example = "42")
        private final int totalCount;

        @ApiModelProperty(value = "正常指标数（totalCount - abnormalCount，下限 0）", required = true,
                example = "38")
        private final int normalCount;

        @ApiModelProperty(value = "异常指标数", required = true, example = "4")
        private final int abnormalCount;

        @ApiModelProperty(value = "异常占比百分数，0~100 取整", required = true, example = "10")
        private final int abnormalPercentage;

        @ApiModelProperty(value = "为 true 时两个数字来自报告印刷汇总，否则为模型统计", required = true,
                example = "true")
        private final boolean reportOriginal;

        @JsonCreator
        Overview(@JsonProperty("totalCount") int totalCount,
                 @JsonProperty("normalCount") int normalCount,
                 @JsonProperty("abnormalCount") int abnormalCount,
                 @JsonProperty("abnormalPercentage") int abnormalPercentage,
                 @JsonProperty("reportOriginal") boolean reportOriginal) {
            this.totalCount = totalCount;
            this.normalCount = normalCount;
            this.abnormalCount = abnormalCount;
            this.abnormalPercentage = abnormalPercentage;
            this.reportOriginal = reportOriginal;
        }
    }

    /** 一个章节一张卡片区；数组顺序即展示顺序。 */
    @ApiModel(value = "IndicatorGroup", description = "模块一章节分组；一个章节一张卡片区")
    @Getter
    public static final class Group {

        @ApiModelProperty(value = "分组展示名；多文件时带「报告N-」前缀，无章节时为「未标注章节」",
                required = true, example = "血脂检查")
        private final String displayName;

        @ApiModelProperty(value = "章节所在页码，从 1 开始", required = true, example = "3")
        private final int page;

        @ApiModelProperty(value = "本章节的指标卡片列表", required = true)
        private final List<Card> cardList;

        @JsonCreator
        Group(@JsonProperty("displayName") String displayName,
              @JsonProperty("page") int page,
              @JsonProperty("cardList") List<Card> cardList) {
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
    @ApiModel(value = "IndicatorCard", description = "健康指标卡片；标签颜色由 status 决定")
    @Getter
    public static final class Card {

        @ApiModelProperty(value = "卡片 ID，模块二健康问题跳转的锚点", required = true, example = "ind-1")
        private final String indicatorId;

        @ApiModelProperty(value = "指标名称，报告原文", required = true, example = "甘油三酯")
        private final String name;

        @ApiModelProperty(value = "结果值，报告原文；可能为定性结果", required = true, example = "2.8")
        private final String value;

        @ApiModelProperty(value = "单位，报告原文；报告未印时为 null", example = "mmol/L")
        private final String unit;

        @ApiModelProperty(value = "参考范围；报告未印时为固定占位「报告未提供」", required = true,
                example = "0.56~1.70")
        private final String refRange;

        @ApiModelProperty(value = "展示结论文案；报告印了结论时为状态标准文案，否则为系统判定固定文案",
                required = true, example = "偏高")
        private final String conclusionText;

        @ApiModelProperty(value = "为 true 表示结论由系统按参考值判定而非报告原文，前端必须做视觉区分（需求 §5-3）",
                required = true, example = "false")
        private final boolean conclusionGenerated;

        @ApiModelProperty(value = "指标状态，决定标签颜色", required = true, example = "HIGH")
        private final IndicatorStatus status;

        @JsonCreator
        Card(@JsonProperty("indicatorId") String indicatorId,
             @JsonProperty("name") String name,
             @JsonProperty("value") String value,
             @JsonProperty("unit") String unit,
             @JsonProperty("refRange") String refRange,
             @JsonProperty("conclusionText") String conclusionText,
             @JsonProperty("conclusionGenerated") boolean conclusionGenerated,
             @JsonProperty("status") IndicatorStatus status) {
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
