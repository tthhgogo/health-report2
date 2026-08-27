package com.example.healthreport.llm.extraction;

import com.example.healthreport.constants.AllergenKey;
import com.example.healthreport.constants.DietRequirementKey;
import com.example.healthreport.constants.NutritionKey;
import com.example.healthreport.parse.segment.Segment;
import lombok.AccessLevel;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Schema、引用和来源校验后的 LLM-A 任务级结果。
 * <p>
 * 下游只接触全局 {@code segmentId}，不会再看到批内 {@code blockRef}。原文只在内存中
 * 按段读取，不拼接模型返回的长展示文案，也不提供包含健康数据的 {@code toString()}。
 * </p>
 */
@Getter
public final class ValidatedExtractionOutput {

	private final List<ReportOverview> reportOverviewList;

	private final List<Section> sectionList;

	private final List<Indicator> indicatorList;

	private final List<TextualFinding> textualFindingList;

	private final List<SummaryConclusion> summaryConclusionList;

	private final List<Allergen> allergenList;

	private final List<AdviceItem<NutritionKey>> nutritionSupplementList;

	private final List<AdviceItem<DietRequirementKey>> dietRequirementList;

	private final Set<String> allergenSectionSegmentIdSet;

	private final Set<String> allergenDataSegmentIdSet;

	@Getter(AccessLevel.NONE)
	private final Map<String, Segment> segmentByIdMap;

	ValidatedExtractionOutput(List<ReportOverview> reportOverviewList, List<Section> sectionList,
			List<Indicator> indicatorList, List<TextualFinding> textualFindingList,
			List<SummaryConclusion> summaryConclusionList, List<Allergen> allergenList,
			List<AdviceItem<NutritionKey>> nutritionSupplementList,
			List<AdviceItem<DietRequirementKey>> dietRequirementList, Set<String> allergenSectionSegmentIdSet,
			Set<String> allergenDataSegmentIdSet, Map<String, Segment> segmentByIdMap) {
		this.reportOverviewList = immutableList(reportOverviewList);
		this.sectionList = immutableList(sectionList);
		this.indicatorList = immutableList(indicatorList);
		this.textualFindingList = immutableList(textualFindingList);
		this.summaryConclusionList = immutableList(summaryConclusionList);
		this.allergenList = immutableList(allergenList);
		this.nutritionSupplementList = immutableList(nutritionSupplementList);
		this.dietRequirementList = immutableList(dietRequirementList);
		this.allergenSectionSegmentIdSet = immutableSet(allergenSectionSegmentIdSet);
		this.allergenDataSegmentIdSet = immutableSet(allergenDataSegmentIdSet);
		this.segmentByIdMap = Collections.unmodifiableMap(new LinkedHashMap<String, Segment>(segmentByIdMap));
	}

	/**
	 * 按引用顺序返回完整原始段，供模块组装展示健康问题与建议来源。
	 * <p>
	 * 不做字符切片；不存在的段会被校验链路提前丢弃。
	 * </p>
	 */
	public List<String> rawTextList(List<String> segmentIdList) {
		if (segmentIdList == null) {
			throw new IllegalArgumentException("segmentId 列表不能为空");
		}
		List<String> rawTextList = new ArrayList<String>(segmentIdList.size());
		for (String segmentId : segmentIdList) {
			Segment segment = segmentByIdMap.get(segmentId);
			if (segment == null) {
				throw new IllegalArgumentException("segmentId 不存在");
			}
			rawTextList.add(segment.getRawText());
		}
		return Collections.unmodifiableList(rawTextList);
	}

	private static <T> List<T> immutableList(List<T> sourceList) {
		return Collections.unmodifiableList(new ArrayList<T>(sourceList));
	}

	private static Set<String> immutableSet(Set<String> sourceSet) {
		return Collections.unmodifiableSet(new LinkedHashSet<String>(sourceSet));
	}

	/** 所有可跨批去重条目共享的确定性定位信息。 */
	public interface EvidenceItem {

		/** 条目证据段，全局唯一且不含 blockRef。 */
		List<String> getSegmentIdList();

		/** 同一原子块内的模型条目序号。 */
		int getItemIndex();

		/** 文件下标。 */
		int getFileIndex();

		/** 批次下标。 */
		int getBatchIndex();

		/** 证据段中的最小真实页码。 */
		int getPage();

	}

	/** 通过来源校验的患者同一性字段；仅在任务执行期参与弱冲突检查。 */
	@Getter
	static final class PatientIdentity {

		private final int fileIndex;

		private final String name;

		private final String gender;

		PatientIdentity(int fileIndex, String name, String gender) {
			this.fileIndex = fileIndex;
			this.name = name;
			this.gender = gender;
		}

	}

	/** 报告原文印刷的汇总数字，不是 Java 计算值。 */
	@Getter
	public static final class ReportOverview {

		private final int fileIndex;

		private final int batchIndex;

		private final int totalCount;

		private final int abnormalCount;

		private final List<String> segmentIdList;

		ReportOverview(int fileIndex, int batchIndex, int totalCount, int abnormalCount, List<String> segmentIdList) {
			this.fileIndex = fileIndex;
			this.batchIndex = batchIndex;
			this.totalCount = totalCount;
			this.abnormalCount = abnormalCount;
			this.segmentIdList = immutableList(segmentIdList);
		}

	}

	/** 批内章节及其经过来源校验的展示名。 */
	@Getter
	public static final class Section {

		private final int fileIndex;

		private final int batchIndex;

		private final int sectionIndex;

		private final SectionRelation relation;

		private final String sectionSegmentId;

		private final String displayName;

		private final List<String> coveredSegmentIdList;

		Section(int fileIndex, int batchIndex, int sectionIndex, SectionRelation relation, String sectionSegmentId,
				String displayName, List<String> coveredSegmentIdList) {
			this.fileIndex = fileIndex;
			this.batchIndex = batchIndex;
			this.sectionIndex = sectionIndex;
			this.relation = relation;
			this.sectionSegmentId = sectionSegmentId;
			this.displayName = displayName;
			this.coveredSegmentIdList = immutableList(coveredSegmentIdList);
		}

	}

	/** 有数值且有结论的指标；状态与健康问题开关保留模型原值。 */
	@Getter
	public static final class Indicator implements EvidenceItem {

		private final int fileIndex;

		private final int batchIndex;

		private final int sectionIndex;

		private final int orderInSection;

		private final int itemIndex;

		private final int page;

		private final List<String> segmentIdList;

		private final String name;

		private final String value;

		private final String unit;

		private final String refRange;

		private final String conclusionText;

		private final IndicatorStatus status;

		private final boolean statusJudgedByModel;

		private final boolean includeInHealthProblems;

		private final String problemName;

		Indicator(int fileIndex, int batchIndex, int sectionIndex, int orderInSection, int itemIndex, int page,
				List<String> segmentIdList, String name, String value, String unit, String refRange,
				String conclusionText, IndicatorStatus status, boolean statusJudgedByModel,
				boolean includeInHealthProblems, String problemName) {
			this.fileIndex = fileIndex;
			this.batchIndex = batchIndex;
			this.sectionIndex = sectionIndex;
			this.orderInSection = orderInSection;
			this.itemIndex = itemIndex;
			this.page = page;
			this.segmentIdList = immutableList(segmentIdList);
			this.name = name;
			this.value = value;
			this.unit = unit;
			this.refRange = refRange;
			this.conclusionText = conclusionText;
			this.status = status;
			this.statusJudgedByModel = statusJudgedByModel;
			this.includeInHealthProblems = includeInHealthProblems;
			this.problemName = problemName;
		}

	}

	/** 无数值但有结论的文字检查项。 */
	@Getter
	public static final class TextualFinding implements EvidenceItem {

		private final int fileIndex;

		private final int batchIndex;

		private final int sectionIndex;

		private final int orderInSection;

		private final int itemIndex;

		private final int page;

		private final List<String> segmentIdList;

		private final String title;

		private final String conclusionText;

		private final IndicatorStatus status;

		private final boolean includeInHealthProblems;

		TextualFinding(int fileIndex, int batchIndex, int sectionIndex, int orderInSection, int itemIndex, int page,
				List<String> segmentIdList, String title, String conclusionText, IndicatorStatus status,
				boolean includeInHealthProblems) {
			this.fileIndex = fileIndex;
			this.batchIndex = batchIndex;
			this.sectionIndex = sectionIndex;
			this.orderInSection = orderInSection;
			this.itemIndex = itemIndex;
			this.page = page;
			this.segmentIdList = immutableList(segmentIdList);
			this.title = title;
			this.conclusionText = conclusionText;
			this.status = status;
			this.includeInHealthProblems = includeInHealthProblems;
		}

	}

	/** 总检结论定位与模型分类；展示正文由 segmentId 回取整段原文。 */
	@Getter
	public static final class SummaryConclusion implements EvidenceItem {

		private final int fileIndex;

		private final int batchIndex;

		private final int sectionIndex;

		private final int sourceOrder;

		private final Integer itemNo;

		private final int itemIndex;

		private final int page;

		private final List<String> segmentIdList;

		private final List<SummaryCategory> categoryList;

		private final boolean includeInHealthProblems;

		SummaryConclusion(int fileIndex, int batchIndex, int sectionIndex, int sourceOrder, Integer itemNo,
				int itemIndex, int page, List<String> segmentIdList, List<SummaryCategory> categoryList,
				boolean includeInHealthProblems) {
			this.fileIndex = fileIndex;
			this.batchIndex = batchIndex;
			this.sectionIndex = sectionIndex;
			this.sourceOrder = sourceOrder;
			this.itemNo = itemNo;
			this.itemIndex = itemIndex;
			this.page = page;
			this.segmentIdList = immutableList(segmentIdList);
			this.categoryList = immutableList(categoryList);
			this.includeInHealthProblems = includeInHealthProblems;
		}

	}

	/** 通过来源与产品安全准入过滤的阳性或临界过敏原；临界准入不等同临床确诊。 */
	@Getter
	public static final class Allergen implements EvidenceItem {

		private final int fileIndex;

		private final int batchIndex;

		private final int sectionIndex;

		private final int sourceOrder;

		private final int itemIndex;

		private final int page;

		private final List<String> segmentIdList;

		private final AllergenKey enumKey;

		private final boolean foodBorne;

		private final String rawName;

		private final String rawResult;

		private final AllergenResultStatus resultStatus;

		public Allergen(int fileIndex, int batchIndex, int sectionIndex, int sourceOrder, int itemIndex, int page,
				List<String> segmentIdList, AllergenKey enumKey, boolean foodBorne, String rawName, String rawResult,
				AllergenResultStatus resultStatus) {
			this.fileIndex = fileIndex;
			this.batchIndex = batchIndex;
			this.sectionIndex = sectionIndex;
			this.sourceOrder = sourceOrder;
			this.itemIndex = itemIndex;
			this.page = page;
			this.segmentIdList = immutableList(segmentIdList);
			this.enumKey = enumKey;
			this.foodBorne = foodBorne;
			this.rawName = rawName;
			this.rawResult = rawResult;
			this.resultStatus = resultStatus;
		}

	}

	/** 营养补充或饮食要求的枚举条目，原文由证据段读取。 */
	@Getter
	public static final class AdviceItem<T extends Enum<T>> implements EvidenceItem {

		private final int fileIndex;

		private final int batchIndex;

		private final int sectionIndex;

		private final int sourceOrder;

		private final Integer itemNo;

		private final int itemIndex;

		private final int page;

		private final List<String> segmentIdList;

		private final T enumKey;

		AdviceItem(int fileIndex, int batchIndex, int sectionIndex, int sourceOrder, Integer itemNo, int itemIndex,
				int page, List<String> segmentIdList, T enumKey) {
			this.fileIndex = fileIndex;
			this.batchIndex = batchIndex;
			this.sectionIndex = sectionIndex;
			this.sourceOrder = sourceOrder;
			this.itemNo = itemNo;
			this.itemIndex = itemIndex;
			this.page = page;
			this.segmentIdList = immutableList(segmentIdList);
			this.enumKey = enumKey;
		}

	}

}
