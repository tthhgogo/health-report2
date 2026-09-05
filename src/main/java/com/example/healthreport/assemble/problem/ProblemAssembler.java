package com.example.healthreport.assemble.problem;

import com.example.healthreport.assemble.indicator.IndicatorAssembler;
import com.example.healthreport.constants.DisclaimerConstants;
import com.example.healthreport.constants.EmptyStateConstants;
import com.example.healthreport.llm.extraction.ProblemsResult;
import com.example.healthreport.render.PageImageSequence;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模块二健康问题组装器（设计方案 §6）。
 * <p>{@code problems} 数组即最终展示列表，准入完全由模型判定。Java 只做三件事：
 * 拼来源标注、按指标名精确查表关联跳转、下发。展示名直接用 {@code name}
 * ——不把 status 翻译成「偏高」拼进问题名。</p>
 */
@Service
public class ProblemAssembler {

    /** 来源标注中章节与指标名之间的固定连接符。 */
    private static final String SOURCE_SEPARATOR = "–";

    /**
     * 组装模块二。
     *
     * @param moduleOne 已组装的模块一，用于 indicatorName → indicatorId 的精确查表；
     *     匹配不到或同名不唯一时不下发跳转按钮，绝不模糊匹配
     */
    public Result assemble(ProblemsResult problems, PageImageSequence images, int fileCount,
                           IndicatorAssembler.Result moduleOne) {
        Map<String, String> indicatorIdByName = uniqueIndicatorIds(moduleOne);
        List<Item> itemList = new ArrayList<Item>(problems.getProblems().size());
        for (ProblemsResult.Problem problem : problems.getProblems()) {
            itemList.add(toItem(problem, images, fileCount, indicatorIdByName));
        }
        String emptyState = itemList.isEmpty() ? EmptyStateConstants.MODULE_TWO : null;
        return new Result(itemList, emptyState, DisclaimerConstants.MODULE_TWO);
    }

    private Item toItem(ProblemsResult.Problem problem, PageImageSequence images, int fileCount,
                        Map<String, String> indicatorIdByName) {
        String sectionName = problem.getSection() == null || problem.getSection().trim().isEmpty()
                ? "" : problem.getSection();
        String sourceLabel;
        if ("INDICATOR".equals(problem.getSourceType())) {
            sourceLabel = sectionName.isEmpty()
                    ? problem.getIndicatorName()
                    : sectionName + SOURCE_SEPARATOR + problem.getIndicatorName();
        } else {
            sourceLabel = problem.getItemNo() == null
                    ? sectionName
                    : sectionName + "第" + problem.getItemNo() + "条";
        }
        if (fileCount > 1) {
            int fileIndex = images.locate(problem.getPage()).getFileIndex();
            sourceLabel = "报告" + (fileIndex + 1) + "-" + sourceLabel;
        }
        // 精确查表：匹配不到就是匹配不到，宁可不显示按钮也不能跳到一张不是它的卡片。
        String indicatorId = problem.getIndicatorName() == null
                ? null : indicatorIdByName.get(problem.getIndicatorName());
        return new Item(problem.getSourceType(), problem.getName(), sourceLabel,
                problem.getRawText(), indicatorId);
    }

    /** 模块一里名称唯一的指标才可作为跳转目标；同名多卡时全部放弃。 */
    private Map<String, String> uniqueIndicatorIds(IndicatorAssembler.Result moduleOne) {
        Map<String, String> idByName = new HashMap<String, String>();
        Set<String> duplicatedNameSet = new HashSet<String>();
        if (moduleOne == null) {
            return idByName;
        }
        for (IndicatorAssembler.Group group : moduleOne.getGroupList()) {
            for (IndicatorAssembler.Card card : group.getCardList()) {
                if (idByName.containsKey(card.getName())) {
                    duplicatedNameSet.add(card.getName());
                } else {
                    idByName.put(card.getName(), card.getIndicatorId());
                }
            }
        }
        for (String duplicatedName : duplicatedNameSet) {
            idByName.remove(duplicatedName);
        }
        return idByName;
    }

    /** 模块二完整返回结构。 */
    @ApiModel(value = "ProblemResult", description = "模块二（健康问题）完整返回结构")
    @Getter
    public static final class Result {

        @ApiModelProperty(value = "健康问题列表；数组顺序即展示顺序", required = true)
        private final List<Item> itemList;

        @ApiModelProperty(value = "列表为空时的空态文案；有内容时为 null",
                example = "本次报告未提取到明确的异常结论或健康提示。")
        private final String emptyState;

        @ApiModelProperty(value = "模块底部声明", required = true)
        private final String disclaimer;

        @JsonCreator
        Result(@JsonProperty("itemList") List<Item> itemList,
               @JsonProperty("emptyState") String emptyState,
               @JsonProperty("disclaimer") String disclaimer) {
            this.itemList = Collections.unmodifiableList(new ArrayList<Item>(itemList));
            this.emptyState = emptyState;
            this.disclaimer = disclaimer;
        }
    }

    /** 一条仅引用报告原文的健康问题。 */
    @ApiModel(value = "ProblemItem", description = "一条仅引用报告原文的健康问题")
    @Getter
    public static final class Item {

        @ApiModelProperty(value = "问题来源类型：INDICATOR（指标被报告明确标注异常）或 SUMMARY（总检结论/医生建议）",
                required = true, allowableValues = "INDICATOR,SUMMARY", example = "INDICATOR")
        private final String sourceType;

        @ApiModelProperty(value = "问题展示名，模型归一化结论", required = true, example = "甘油三酯 偏高")
        private final String displayName;

        @ApiModelProperty(value = "来源标注；多文件时带「报告N-」前缀", required = true,
                example = "血脂检查–甘油三酯")
        private final String sourceLabel;

        @ApiModelProperty(value = "承载该问题的报告原文", required = true,
                example = "甘油三酯 2.8 mmol/L 0.56~1.70 偏高")
        private final String rawText;

        @ApiModelProperty(value = "可跳转的模块一指标卡片 ID；匹配不到或同名不唯一时为 null，前端不显示跳转按钮",
                example = "ind-1")
        private final String indicatorId;

        @JsonCreator
        Item(@JsonProperty("sourceType") String sourceType,
             @JsonProperty("displayName") String displayName,
             @JsonProperty("sourceLabel") String sourceLabel,
             @JsonProperty("rawText") String rawText,
             @JsonProperty("indicatorId") String indicatorId) {
            this.sourceType = sourceType;
            this.displayName = displayName;
            this.sourceLabel = sourceLabel;
            this.rawText = rawText;
            this.indicatorId = indicatorId;
        }
    }
}
