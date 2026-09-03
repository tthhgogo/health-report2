package com.example.healthreport.assemble.problem;

import com.example.healthreport.assemble.indicator.IndicatorAssembler;
import com.example.healthreport.constants.DisclaimerConstants;
import com.example.healthreport.constants.EmptyStateConstants;
import com.example.healthreport.llm.extraction.ProblemsResult;
import com.example.healthreport.render.PageImageSequence;
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
    @Getter
    public static final class Result {
        private final List<Item> itemList;
        private final String emptyState;
        private final String disclaimer;

        Result(List<Item> itemList, String emptyState, String disclaimer) {
            this.itemList = Collections.unmodifiableList(new ArrayList<Item>(itemList));
            this.emptyState = emptyState;
            this.disclaimer = disclaimer;
        }
    }

    /** 一条仅引用报告原文的健康问题。 */
    @Getter
    public static final class Item {
        private final String sourceType;
        private final String displayName;
        private final String sourceLabel;
        private final String rawText;
        private final String indicatorId;

        Item(String sourceType, String displayName, String sourceLabel, String rawText,
             String indicatorId) {
            this.sourceType = sourceType;
            this.displayName = displayName;
            this.sourceLabel = sourceLabel;
            this.rawText = rawText;
            this.indicatorId = indicatorId;
        }
    }
}
