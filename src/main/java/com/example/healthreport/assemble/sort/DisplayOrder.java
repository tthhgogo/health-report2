package com.example.healthreport.assemble.sort;

import com.example.healthreport.llm.extraction.ValidatedExtractionOutput;
import com.github.promeg.pinyinhelper.Pinyin;
import lombok.Getter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全案展示排序的唯一实现。
 * <p>本类只比较 LLM-A 给出的顺序字段和校验层计算出的真实页码，不使用 segmentId 中的
 * 解析顺序，也不做任何版面或医疗语义推断。</p>
 */
@Service
public class DisplayOrder {

    /** 多文件章节名前缀中的报告序号从一开始，供用户阅读而不是作为排序依据。 */
    private static final int DISPLAY_FILE_NUMBER_OFFSET = 1;

    /**
     * 按菜名拼音排序模块四候选；汉字开头优先，原名与 ID 用于稳定打破平局。
     */
    public <T extends DishNameItem> List<T> sortDishByPinyin(List<T> sourceList) {
        if (sourceList == null) {
            throw new IllegalArgumentException("菜品排序输入不能为空");
        }
        List<T> resultList = new ArrayList<T>(sourceList);
        Collections.sort(resultList, new Comparator<T>() {
            @Override
            public int compare(T left, T right) {
                boolean leftChinese = startsWithChinese(left.getDishName());
                boolean rightChinese = startsWithChinese(right.getDishName());
                if (leftChinese != rightChinese) {
                    return leftChinese ? -1 : 1;
                }
                String leftPinyin = Pinyin.toPinyin(left.getDishName(), "");
                String rightPinyin = Pinyin.toPinyin(right.getDishName(), "");
                int pinyinResult = leftPinyin.compareToIgnoreCase(rightPinyin);
                if (pinyinResult != 0) {
                    return pinyinResult;
                }
                int nameResult = left.getDishName().compareTo(right.getDishName());
                if (nameResult != 0) {
                    return nameResult;
                }
                return Long.compare(left.getDishId(), right.getDishId());
            }
        });
        return Collections.unmodifiableList(resultList);
    }

    private static boolean startsWithChinese(String dishName) {
        return dishName.length() > 0 && Pinyin.isChinese(dishName.charAt(0));
    }

    /** 模块四提供给唯一排序实现的最小菜名视图。 */
    public interface DishNameItem {

        /** 菜品展示名。 */
        String getDishName();

        /** 全系统唯一菜品 ID，用于稳定打破同名平局。 */
        long getDishId();
    }

    /**
     * 为三个展示模块生成同一份分组和顺序计划。
     *
     * @param output 已完成 Schema、来源引用及页码校验的 LLM-A 输出
     * @param fileCount 本任务包含的报告文件数，至少为一
     * @return 不可变展示计划
     */
    public DisplayPlan plan(ValidatedExtractionOutput output, int fileCount) {
        if (output == null) {
            throw new IllegalArgumentException("校验结果不能为空");
        }
        if (fileCount < 1) {
            throw new IllegalArgumentException("报告文件数必须大于零");
        }

        Map<String, ValidatedExtractionOutput.Section> sectionByLocationMap = sectionByLocation(output);
        int itemCount = evidenceItemCount(output);
        Map<String, MutableGroup> mutableGroupByKeyMap =
                new LinkedHashMap<String, MutableGroup>(itemCount);
        Map<ValidatedExtractionOutput.EvidenceItem, MutableGroup> mutableGroupByItemMap =
                new IdentityHashMap<ValidatedExtractionOutput.EvidenceItem, MutableGroup>(itemCount);

        registerAll(output.getIndicatorList(), sectionByLocationMap, mutableGroupByKeyMap,
                mutableGroupByItemMap, fileCount);
        registerAll(output.getTextualFindingList(), sectionByLocationMap, mutableGroupByKeyMap,
                mutableGroupByItemMap, fileCount);
        registerAll(output.getSummaryConclusionList(), sectionByLocationMap, mutableGroupByKeyMap,
                mutableGroupByItemMap, fileCount);
        registerAll(output.getAllergenList(), sectionByLocationMap, mutableGroupByKeyMap,
                mutableGroupByItemMap, fileCount);
        registerAll(output.getNutritionSupplementList(), sectionByLocationMap, mutableGroupByKeyMap,
                mutableGroupByItemMap, fileCount);
        registerAll(output.getDietRequirementList(), sectionByLocationMap, mutableGroupByKeyMap,
                mutableGroupByItemMap, fileCount);

        List<MutableGroup> mutableGroupList = new ArrayList<MutableGroup>(mutableGroupByKeyMap.values());
        Collections.sort(mutableGroupList, GROUP_COMPARATOR);

        List<DisplayGroup> groupList = new ArrayList<DisplayGroup>(mutableGroupList.size());
        Map<MutableGroup, DisplayGroup> displayGroupByMutableMap =
                new IdentityHashMap<MutableGroup, DisplayGroup>();
        for (MutableGroup mutableGroup : mutableGroupList) {
            DisplayGroup group = mutableGroup.toDisplayGroup();
            groupList.add(group);
            displayGroupByMutableMap.put(mutableGroup, group);
        }

        Map<ValidatedExtractionOutput.EvidenceItem, DisplayGroup> groupByItemMap =
                new IdentityHashMap<ValidatedExtractionOutput.EvidenceItem, DisplayGroup>(itemCount);
        for (Map.Entry<ValidatedExtractionOutput.EvidenceItem, MutableGroup> entry
                : mutableGroupByItemMap.entrySet()) {
            groupByItemMap.put(entry.getKey(), displayGroupByMutableMap.get(entry.getValue()));
        }

        List<ValidatedExtractionOutput.Indicator> indicatorList = sectionSorted(
                output.getIndicatorList(), groupByItemMap, true);
        List<ValidatedExtractionOutput.TextualFinding> textualFindingList = sectionSorted(
                output.getTextualFindingList(), groupByItemMap, false);
        List<SectionFinding> sectionFindingList = combinedSectionSorted(
                output.getIndicatorList(), output.getTextualFindingList(), groupByItemMap);
        List<ValidatedExtractionOutput.SummaryConclusion> summaryConclusionList = sourceSorted(
                output.getSummaryConclusionList(), groupByItemMap, SourceOrderType.SUMMARY);
        List<ValidatedExtractionOutput.Allergen> allergenList = sourceSorted(
                output.getAllergenList(), groupByItemMap, SourceOrderType.ALLERGEN);
        List<ValidatedExtractionOutput.AdviceItem<com.example.healthreport.constants.NutritionKey>>
                nutritionSupplementList = sourceSorted(output.getNutritionSupplementList(),
                groupByItemMap, SourceOrderType.ADVICE);
        List<ValidatedExtractionOutput.AdviceItem<com.example.healthreport.constants.DietRequirementKey>>
                dietRequirementList = sourceSorted(output.getDietRequirementList(),
                groupByItemMap, SourceOrderType.ADVICE);

        Map<ValidatedExtractionOutput.Indicator, String> indicatorIdMap =
                new IdentityHashMap<ValidatedExtractionOutput.Indicator, String>();
        for (int index = 0; index < indicatorList.size(); index++) {
            indicatorIdMap.put(indicatorList.get(index), "indicator-" + (index + 1));
        }
        return new DisplayPlan(groupList, groupByItemMap, indicatorList, textualFindingList,
                sectionFindingList, summaryConclusionList, allergenList, nutritionSupplementList,
                dietRequirementList, indicatorIdMap);
    }

    private Map<String, ValidatedExtractionOutput.Section> sectionByLocation(ValidatedExtractionOutput output) {
        Map<String, ValidatedExtractionOutput.Section> resultMap =
                new LinkedHashMap<String, ValidatedExtractionOutput.Section>(output.getSectionList().size());
        for (ValidatedExtractionOutput.Section section : output.getSectionList()) {
            resultMap.put(locationKey(section.getFileIndex(), section.getBatchIndex(),
                    section.getSectionIndex()), section);
        }
        return resultMap;
    }

    private int evidenceItemCount(ValidatedExtractionOutput output) {
        return output.getIndicatorList().size()
                + output.getTextualFindingList().size()
                + output.getSummaryConclusionList().size()
                + output.getAllergenList().size()
                + output.getNutritionSupplementList().size()
                + output.getDietRequirementList().size();
    }

    private <T extends ValidatedExtractionOutput.EvidenceItem> void registerAll(
            List<T> itemList,
            Map<String, ValidatedExtractionOutput.Section> sectionByLocationMap,
            Map<String, MutableGroup> mutableGroupByKeyMap,
            Map<ValidatedExtractionOutput.EvidenceItem, MutableGroup> mutableGroupByItemMap,
            int fileCount) {
        for (T item : itemList) {
            String locationKey = locationKey(item.getFileIndex(), item.getBatchIndex(),
                    sectionIndex(item));
            ValidatedExtractionOutput.Section section = sectionByLocationMap.get(locationKey);
            if (section == null) {
                throw new IllegalArgumentException("展示条目找不到已校验章节");
            }
            String groupKey = item.getFileIndex() + "-" + section.getSectionSegmentId();
            MutableGroup group = mutableGroupByKeyMap.get(groupKey);
            if (group == null) {
                String displayName = section.getDisplayName();
                if (fileCount > 1) {
                    displayName = "报告" + (item.getFileIndex() + DISPLAY_FILE_NUMBER_OFFSET)
                            + "-" + displayName;
                }
                group = new MutableGroup(groupKey, item.getFileIndex(), displayName,
                        item.getPage(), item.getBatchIndex(), section.getSectionIndex());
                mutableGroupByKeyMap.put(groupKey, group);
            } else {
                group.include(item.getPage(), item.getBatchIndex(), section.getSectionIndex());
            }
            mutableGroupByItemMap.put(item, group);
        }
    }

    private int sectionIndex(ValidatedExtractionOutput.EvidenceItem item) {
        if (item instanceof ValidatedExtractionOutput.Indicator) {
            return ((ValidatedExtractionOutput.Indicator) item).getSectionIndex();
        }
        if (item instanceof ValidatedExtractionOutput.TextualFinding) {
            return ((ValidatedExtractionOutput.TextualFinding) item).getSectionIndex();
        }
        if (item instanceof ValidatedExtractionOutput.SummaryConclusion) {
            return ((ValidatedExtractionOutput.SummaryConclusion) item).getSectionIndex();
        }
        if (item instanceof ValidatedExtractionOutput.Allergen) {
            return ((ValidatedExtractionOutput.Allergen) item).getSectionIndex();
        }
        return ((ValidatedExtractionOutput.AdviceItem<?>) item).getSectionIndex();
    }

    private int orderInSection(ValidatedExtractionOutput.EvidenceItem item) {
        if (item instanceof ValidatedExtractionOutput.Indicator) {
            return ((ValidatedExtractionOutput.Indicator) item).getOrderInSection();
        }
        return ((ValidatedExtractionOutput.TextualFinding) item).getOrderInSection();
    }

    private int sourceOrder(ValidatedExtractionOutput.EvidenceItem item, SourceOrderType type) {
        if (type == SourceOrderType.SUMMARY) {
            return ((ValidatedExtractionOutput.SummaryConclusion) item).getSourceOrder();
        }
        if (type == SourceOrderType.ALLERGEN) {
            return ((ValidatedExtractionOutput.Allergen) item).getSourceOrder();
        }
        return ((ValidatedExtractionOutput.AdviceItem<?>) item).getSourceOrder();
    }

    private <T extends ValidatedExtractionOutput.EvidenceItem> List<T> sectionSorted(
            List<T> sourceList,
            final Map<ValidatedExtractionOutput.EvidenceItem, DisplayGroup> groupByItemMap,
            boolean indicator) {
        List<T> resultList = new ArrayList<T>(sourceList);
        final boolean indicatorType = indicator;
        Collections.sort(resultList, new Comparator<T>() {
            @Override
            public int compare(T left, T right) {
                int groupResult = compareGroup(groupByItemMap.get(left), groupByItemMap.get(right));
                if (groupResult != 0) {
                    return groupResult;
                }
                int pageResult = Integer.compare(left.getPage(), right.getPage());
                if (pageResult != 0) {
                    return pageResult;
                }
                int leftOrder = indicatorType
                        ? ((ValidatedExtractionOutput.Indicator) left).getOrderInSection()
                        : ((ValidatedExtractionOutput.TextualFinding) left).getOrderInSection();
                int rightOrder = indicatorType
                        ? ((ValidatedExtractionOutput.Indicator) right).getOrderInSection()
                        : ((ValidatedExtractionOutput.TextualFinding) right).getOrderInSection();
                return Integer.compare(leftOrder, rightOrder);
            }
        });
        return Collections.unmodifiableList(resultList);
    }

    private List<SectionFinding> combinedSectionSorted(
            List<ValidatedExtractionOutput.Indicator> indicatorList,
            List<ValidatedExtractionOutput.TextualFinding> textualFindingList,
            final Map<ValidatedExtractionOutput.EvidenceItem, DisplayGroup> groupByItemMap) {
        List<SectionFinding> resultList = new ArrayList<SectionFinding>(
                indicatorList.size() + textualFindingList.size());
        for (ValidatedExtractionOutput.Indicator indicator : indicatorList) {
            resultList.add(SectionFinding.numeric(indicator));
        }
        for (ValidatedExtractionOutput.TextualFinding finding : textualFindingList) {
            resultList.add(SectionFinding.textual(finding));
        }
        Collections.sort(resultList, new Comparator<SectionFinding>() {
            @Override
            public int compare(SectionFinding left, SectionFinding right) {
                ValidatedExtractionOutput.EvidenceItem leftItem = left.getItem();
                ValidatedExtractionOutput.EvidenceItem rightItem = right.getItem();
                int groupResult = compareGroup(groupByItemMap.get(leftItem), groupByItemMap.get(rightItem));
                if (groupResult != 0) {
                    return groupResult;
                }
                int pageResult = Integer.compare(leftItem.getPage(), rightItem.getPage());
                if (pageResult != 0) {
                    return pageResult;
                }
                return Integer.compare(orderInSection(leftItem), orderInSection(rightItem));
            }
        });
        return Collections.unmodifiableList(resultList);
    }

    private <T extends ValidatedExtractionOutput.EvidenceItem> List<T> sourceSorted(
            List<T> sourceList,
            final Map<ValidatedExtractionOutput.EvidenceItem, DisplayGroup> groupByItemMap,
            SourceOrderType sourceOrderType) {
        List<T> resultList = new ArrayList<T>(sourceList);
        final SourceOrderType type = sourceOrderType;
        Collections.sort(resultList, new Comparator<T>() {
            @Override
            public int compare(T left, T right) {
                int groupResult = compareGroup(groupByItemMap.get(left), groupByItemMap.get(right));
                if (groupResult != 0) {
                    return groupResult;
                }
                int pageResult = Integer.compare(left.getPage(), right.getPage());
                if (pageResult != 0) {
                    return pageResult;
                }
                return Integer.compare(sourceOrder(left, type), sourceOrder(right, type));
            }
        });
        return Collections.unmodifiableList(resultList);
    }

    private static int compareGroup(DisplayGroup left, DisplayGroup right) {
        int fileResult = Integer.compare(left.getFileIndex(), right.getFileIndex());
        if (fileResult != 0) {
            return fileResult;
        }
        int pageResult = Integer.compare(left.getPage(), right.getPage());
        if (pageResult != 0) {
            return pageResult;
        }
        int batchResult = Integer.compare(left.getBatchIndex(), right.getBatchIndex());
        if (batchResult != 0) {
            return batchResult;
        }
        return Integer.compare(left.getSectionIndex(), right.getSectionIndex());
    }

    private static String locationKey(int fileIndex, int batchIndex, int sectionIndex) {
        return fileIndex + ":" + batchIndex + ":" + sectionIndex;
    }

    /** 分组排序器只存在于本类，先按文件，再按组首次出现位置。 */
    private static final Comparator<MutableGroup> GROUP_COMPARATOR = new Comparator<MutableGroup>() {
        @Override
        public int compare(MutableGroup left, MutableGroup right) {
            int fileResult = Integer.compare(left.fileIndex, right.fileIndex);
            if (fileResult != 0) {
                return fileResult;
            }
            int pageResult = Integer.compare(left.page, right.page);
            if (pageResult != 0) {
                return pageResult;
            }
            int batchResult = Integer.compare(left.batchIndex, right.batchIndex);
            if (batchResult != 0) {
                return batchResult;
            }
            return Integer.compare(left.sectionIndex, right.sectionIndex);
        }
    };

    /** 来源序号字段所在的已校验条目类型，用于避免模块自行比较。 */
    private enum SourceOrderType {
        /** 总检结论。 */
        SUMMARY,
        /** 过敏原。 */
        ALLERGEN,
        /** 营养补充或饮食要求。 */
        ADVICE
    }

    /** 构建期间累计一组的最小页码及首次位置。 */
    private static final class MutableGroup {
        private final String groupKey;
        private final int fileIndex;
        private final String displayName;
        private int page;
        private int batchIndex;
        private int sectionIndex;

        private MutableGroup(String groupKey, int fileIndex, String displayName, int page,
                             int batchIndex, int sectionIndex) {
            this.groupKey = groupKey;
            this.fileIndex = fileIndex;
            this.displayName = displayName;
            this.page = page;
            this.batchIndex = batchIndex;
            this.sectionIndex = sectionIndex;
        }

        private void include(int itemPage, int itemBatchIndex, int itemSectionIndex) {
            if (itemPage < page) {
                page = itemPage;
                batchIndex = itemBatchIndex;
                sectionIndex = itemSectionIndex;
            } else if (itemPage == page && (itemBatchIndex < batchIndex
                    || (itemBatchIndex == batchIndex && itemSectionIndex < sectionIndex))) {
                batchIndex = itemBatchIndex;
                sectionIndex = itemSectionIndex;
            }
        }

        private DisplayGroup toDisplayGroup() {
            return new DisplayGroup(groupKey, fileIndex, page, batchIndex, sectionIndex, displayName);
        }
    }

    /** 对外展示分组；groupKey 使用有效 sectionSegmentId，跨文件不会合并。 */
    @Getter
    public static final class DisplayGroup {
        private final String groupKey;
        private final int fileIndex;
        private final int page;
        private final int batchIndex;
        private final int sectionIndex;
        private final String displayName;

        private DisplayGroup(String groupKey, int fileIndex, int page, int batchIndex,
                             int sectionIndex, String displayName) {
            this.groupKey = groupKey;
            this.fileIndex = fileIndex;
            this.page = page;
            this.batchIndex = batchIndex;
            this.sectionIndex = sectionIndex;
            this.displayName = displayName;
        }
    }

    /** 模块二中数值指标与文字检查项的统一有序视图。 */
    @Getter
    public static final class SectionFinding {
        private final ValidatedExtractionOutput.EvidenceItem item;
        private final boolean numeric;

        private SectionFinding(ValidatedExtractionOutput.EvidenceItem item, boolean numeric) {
            this.item = item;
            this.numeric = numeric;
        }

        private static SectionFinding numeric(ValidatedExtractionOutput.Indicator indicator) {
            return new SectionFinding(indicator, true);
        }

        private static SectionFinding textual(ValidatedExtractionOutput.TextualFinding finding) {
            return new SectionFinding(finding, false);
        }
    }

    /** 三个展示模块共享的不可变排序计划。 */
    @Getter
    public static final class DisplayPlan {
        private final List<DisplayGroup> groupList;
        private final Map<ValidatedExtractionOutput.EvidenceItem, DisplayGroup> groupByItemMap;
        private final List<ValidatedExtractionOutput.Indicator> indicatorList;
        private final List<ValidatedExtractionOutput.TextualFinding> textualFindingList;
        private final List<SectionFinding> sectionFindingList;
        private final List<ValidatedExtractionOutput.SummaryConclusion> summaryConclusionList;
        private final List<ValidatedExtractionOutput.Allergen> allergenList;
        private final List<ValidatedExtractionOutput.AdviceItem<com.example.healthreport.constants.NutritionKey>>
                nutritionSupplementList;
        private final List<ValidatedExtractionOutput.AdviceItem<com.example.healthreport.constants.DietRequirementKey>>
                dietRequirementList;
        private final Map<ValidatedExtractionOutput.Indicator, String> indicatorIdMap;

        private DisplayPlan(List<DisplayGroup> groupList,
                            Map<ValidatedExtractionOutput.EvidenceItem, DisplayGroup> groupByItemMap,
                            List<ValidatedExtractionOutput.Indicator> indicatorList,
                            List<ValidatedExtractionOutput.TextualFinding> textualFindingList,
                            List<SectionFinding> sectionFindingList,
                            List<ValidatedExtractionOutput.SummaryConclusion> summaryConclusionList,
                            List<ValidatedExtractionOutput.Allergen> allergenList,
                            List<ValidatedExtractionOutput.AdviceItem<com.example.healthreport.constants.NutritionKey>>
                                    nutritionSupplementList,
                            List<ValidatedExtractionOutput.AdviceItem<com.example.healthreport.constants.DietRequirementKey>>
                                    dietRequirementList,
                            Map<ValidatedExtractionOutput.Indicator, String> indicatorIdMap) {
            this.groupList = Collections.unmodifiableList(new ArrayList<DisplayGroup>(groupList));
            this.groupByItemMap = Collections.unmodifiableMap(groupByItemMap);
            this.indicatorList = indicatorList;
            this.textualFindingList = textualFindingList;
            this.sectionFindingList = sectionFindingList;
            this.summaryConclusionList = summaryConclusionList;
            this.allergenList = allergenList;
            this.nutritionSupplementList = nutritionSupplementList;
            this.dietRequirementList = dietRequirementList;
            this.indicatorIdMap = Collections.unmodifiableMap(indicatorIdMap);
        }

        /** 返回条目所属展示组；缺失表示调用方传入了计划之外的条目。 */
        public DisplayGroup groupOf(ValidatedExtractionOutput.EvidenceItem item) {
            DisplayGroup group = groupByItemMap.get(item);
            if (group == null) {
                throw new IllegalArgumentException("条目不属于当前展示计划");
            }
            return group;
        }

        /** 返回模块一指标卡片的稳定页内跳转标识。 */
        public String indicatorIdOf(ValidatedExtractionOutput.Indicator indicator) {
            String indicatorId = indicatorIdMap.get(indicator);
            if (indicatorId == null) {
                throw new IllegalArgumentException("指标不属于当前展示计划");
            }
            return indicatorId;
        }
    }
}
