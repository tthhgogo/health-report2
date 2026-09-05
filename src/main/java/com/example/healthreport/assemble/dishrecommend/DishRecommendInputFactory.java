package com.example.healthreport.assemble.dishrecommend;

import com.example.healthreport.cache.DishRecommendSetCache;
import com.example.healthreport.cache.DishSetMember;
import com.example.healthreport.cache.DishSetMemberCodec;
import com.example.healthreport.cache.DishTagSetRef;
import com.example.healthreport.constants.AllergenGroup;
import com.example.healthreport.constants.AllergenGroups;
import com.example.healthreport.constants.AllergenKey;
import com.example.healthreport.constants.DietRequirementContents;
import com.example.healthreport.constants.DietRequirementKey;
import com.example.healthreport.constants.NutritionKey;
import com.example.healthreport.llm.extraction.DietAdviceResult;
import com.example.healthreport.safety.HighRiskAdviceGate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 从报告生效维度和企业当日 Redis 方向集合建立模块四输入。 */
@Slf4j
@Service
public class DishRecommendInputFactory {

	/** 多段报告原文保持段边界时使用的展示换行符，不改变段内原文。 */
	private static final String RAW_TEXT_SEPARATOR = "\n";

	private final DishRecommendSetCache setCache;

	private final DishSetMemberCodec memberCodec;

	private final HighRiskAdviceGate highRiskAdviceGate;

	public DishRecommendInputFactory(DishRecommendSetCache setCache, DishSetMemberCodec memberCodec,
			HighRiskAdviceGate highRiskAdviceGate) {
		this.setCache = setCache;
		this.memberCodec = memberCodec;
		this.highRiskAdviceGate = highRiskAdviceGate;
	}

	/**
	 * 读取当前企业当天与报告相关的方向集合，先并集、再做正向减拒绝差集。 在线不查询菜品库、食材表或标签表，也不运行主料与关键词匹配。
	 */
	public DishRecommendInput create(String companyId, LocalDate bizDate, DietAdviceResult dietTags,
			boolean suppressDishRecommend) {
		if (companyId == null || companyId.length() == 0 || bizDate == null || dietTags == null) {
			throw new IllegalArgumentException("模块四输入工厂参数不能为空");
		}
		if (suppressDishRecommend) {
			return new DishRecommendInput(true, false, Collections.<DishRecommendInput.Candidate>emptyList());
		}

		AdviceDimensions dimensions = dimensions(dietTags);
		List<SetDescriptor> descriptorList = descriptors(dimensions);
		if (descriptorList.isEmpty()) {
			return new DishRecommendInput(false, dimensions.formalAdvicePresent,
					Collections.<DishRecommendInput.Candidate>emptyList());
		}
		List<DishTagSetRef> setRefList = new ArrayList<DishTagSetRef>(descriptorList.size());
		for (SetDescriptor descriptor : descriptorList) {
			setRefList.add(descriptor.setRef);
		}
		Map<DishTagSetRef, Set<String>> memberByRefMap = setCache.read(companyId, bizDate, setRefList);
		Set<String> positiveMemberSet = union(descriptorList, memberByRefMap, false);
		Set<String> rejectMemberSet = union(descriptorList, memberByRefMap, true);
		positiveMemberSet.removeAll(rejectMemberSet);

		Set<String> candidateMemberSet = new LinkedHashSet<String>(positiveMemberSet);
		candidateMemberSet.addAll(rejectMemberSet);
		List<DishRecommendInput.Candidate> candidateList = new ArrayList<DishRecommendInput.Candidate>(
				candidateMemberSet.size());
		int malformedMemberCount = 0;
		for (String member : candidateMemberSet) {
			DishSetMember dish;
			try {
				dish = memberCodec.decode(member);
			}
			catch (IllegalArgumentException exception) {
				// 人工改 Key 或编码器版本不一致不应废掉已完成的模块一至三，也不得记录成员原文。
				malformedMemberCount++;
				continue;
			}
			candidateList.add(new DishRecommendInput.Candidate(dish.getDishId(), dish.getDishName(),
					matches(member, positiveMemberSet, rejectMemberSet, descriptorList, memberByRefMap)));
		}
		if (malformedMemberCount > 0) {
			log.warn("企业菜品标签集合存在畸形成员，模块四已跳过，数量={}", malformedMemberCount);
		}
		return new DishRecommendInput(false, dimensions.formalAdvicePresent, candidateList);
	}

	private Set<String> union(List<SetDescriptor> descriptorList, Map<DishTagSetRef, Set<String>> memberByRefMap,
			boolean reject) {
		Set<String> resultSet = new LinkedHashSet<String>();
		for (SetDescriptor descriptor : descriptorList) {
			Set<String> memberSet = memberByRefMap.get(descriptor.setRef);
			if (descriptor.reject == reject && memberSet != null) {
				resultSet.addAll(memberSet);
			}
		}
		return resultSet;
	}

	private List<DishRecommendInput.Match> matches(String member, Set<String> positiveMemberSet,
			Set<String> rejectMemberSet, List<SetDescriptor> descriptorList,
			Map<DishTagSetRef, Set<String>> memberByRefMap) {
		List<DishRecommendInput.Match> matchList = new ArrayList<DishRecommendInput.Match>();
		boolean rejected = rejectMemberSet.contains(member);
		for (SetDescriptor descriptor : descriptorList) {
			Set<String> dimensionMemberSet = memberByRefMap.get(descriptor.setRef);
			if (dimensionMemberSet == null || !dimensionMemberSet.contains(member)) {
				continue;
			}
			if (!descriptor.reject && (rejected || !positiveMemberSet.contains(member))) {
				// 任一拒绝方向命中后，不恢复任何正向标签或推荐理由。
				continue;
			}
			matchList.add(new DishRecommendInput.Match(descriptor.reject, descriptor.tagText,
					descriptor.rawText));
		}
		return matchList;
	}

	/**
	 * 从第三次调用的已校验标签收集生效维度（设计方案 §8.10）。
	 * <p>只有已通过 Schema/枚举/方向校验且存在正式方向集合的标签才拼 Redis Key：
	 * 五个非食入性过敏原与 OTHER 在映射前过滤，只保留模块三展示；
	 * 营养补充与饮食注意条目还要过高危表述安全闸（只扫 quote，命中按 OTHER 路径处理）。</p>
	 */
	private AdviceDimensions dimensions(DietAdviceResult dietTags) {
		AdviceDimensions dimensions = new AdviceDimensions();
		for (DietAdviceResult.DietTag tag : dietTags.getReject()) {
			if ("OTHER".equals(tag.getEnumKey())) {
				// OTHER 没有稳定的离线集合；模块三展示原文，模块四不临时查库匹配。
				continue;
			}
			if ("ALLERGEN".equals(tag.getDimension())) {
				AllergenKey allergenKey = AllergenKey.valueOf(tag.getEnumKey());
				dimensions.formalAdvicePresent = true;
				if (AllergenGroups.FOOD_BORNE_KEYS.contains(allergenKey)) {
					// 负向只需要维度 Key：不推荐菜只输出标签，不携带报告原文理由。
					dimensions.allergenRejectKeySet.add(allergenKey);
				}
			}
			else if ("DIET".equals(tag.getDimension())) {
				dimensions.formalAdvicePresent = true;
				if (!highRiskAdviceGate.shouldSuppress(tag.getQuote())) {
					dimensions.dietRejectKeySet.add(DietRequirementKey.valueOf(tag.getEnumKey()));
				}
			}
		}
		for (DietAdviceResult.DietTag tag : dietTags.getRecommend()) {
			if ("OTHER".equals(tag.getEnumKey())) {
				continue;
			}
			if ("NUTRITION".equals(tag.getDimension())) {
				dimensions.formalAdvicePresent = true;
				if (!highRiskAdviceGate.shouldSuppress(tag.getQuote())) {
					addRawText(dimensions.nutritionRawTextByKeyMap, NutritionKey.valueOf(tag.getEnumKey()),
							Collections.singletonList(tag.getRawText()));
				}
			}
			else if ("DIET".equals(tag.getDimension())) {
				dimensions.formalAdvicePresent = true;
				if (!highRiskAdviceGate.shouldSuppress(tag.getQuote())) {
					addRawText(dimensions.dietRecommendRawTextByKeyMap,
							DietRequirementKey.valueOf(tag.getEnumKey()),
							Collections.singletonList(tag.getRawText()));
				}
			}
		}
		return dimensions;
	}

	private List<SetDescriptor> descriptors(AdviceDimensions dimensions) {
		List<SetDescriptor> resultList = new ArrayList<SetDescriptor>();
		for (AllergenKey allergenKey : dimensions.allergenRejectKeySet) {
			AllergenGroup group = AllergenGroups.ALL.get(allergenKey);
			resultList.add(new SetDescriptor(
					new DishTagSetRef(DishTagSetRef.Category.ALLERGEN, DishTagSetRef.Direction.REJECT,
							allergenKey.name()),
					true, group.getDisplayName() + "过敏", null));
		}
		for (DietRequirementKey dietKey : dimensions.dietRejectKeySet) {
			resultList.add(new SetDescriptor(
					new DishTagSetRef(DishTagSetRef.Category.DIET, DishTagSetRef.Direction.REJECT,
							dietKey.name()),
					true, dietRejectTagText(dietKey), null));
		}
		for (Map.Entry<DietRequirementKey, LinkedHashSet<String>> entry : dimensions.dietRecommendRawTextByKeyMap
			.entrySet()) {
			// 方向校验已保证只有 LOW_PURINE、HIGH_FIBER 能进 recommend；这里再按常量政策核对一次。
			if (!positiveDiet(entry.getKey())) {
				continue;
			}
			resultList.add(new SetDescriptor(
					new DishTagSetRef(DishTagSetRef.Category.DIET, DishTagSetRef.Direction.RECOMMEND,
							entry.getKey().name()),
					false, DietRequirementContents.ALL.get(entry.getKey()).getRecommendTagText(),
					joinRawText(entry.getValue())));
		}
		for (Map.Entry<NutritionKey, LinkedHashSet<String>> entry : dimensions.nutritionRawTextByKeyMap.entrySet()) {
			resultList.add(new SetDescriptor(
					new DishTagSetRef(DishTagSetRef.Category.NUTRITION, DishTagSetRef.Direction.RECOMMEND,
							entry.getKey().name()),
					false, nutritionTagText(entry.getKey()), joinRawText(entry.getValue())));
		}
		return resultList;
	}

	private boolean positiveDiet(DietRequirementKey key) {
		return key == DietRequirementKey.LOW_PURINE || key == DietRequirementKey.HIGH_FIBER;
	}

	private <K> void addRawText(Map<K, LinkedHashSet<String>> rawTextByKeyMap, K key, List<String> rawTextList) {
		LinkedHashSet<String> rawTextSet = rawTextByKeyMap.get(key);
		if (rawTextSet == null) {
			rawTextSet = new LinkedHashSet<String>();
			rawTextByKeyMap.put(key, rawTextSet);
		}
		rawTextSet.addAll(rawTextList);
	}

	private String joinRawText(Set<String> rawTextSet) {
		StringBuilder builder = new StringBuilder();
		for (String rawText : rawTextSet) {
			if (builder.length() > 0) {
				builder.append(RAW_TEXT_SEPARATOR);
			}
			builder.append(rawText);
		}
		return builder.toString();
	}

	private String nutritionTagText(NutritionKey key) {
		switch (key) {
			case IRON:
				return "补铁";
			case CALCIUM:
				return "补钙";
			case PROTEIN:
				return "高蛋白";
			case VITAMIN_D:
				return "补维生素D";
			case VITAMIN_B12:
				return "补维生素B12";
			case FOLATE:
				return "补叶酸";
			case DIETARY_FIBER:
				return "高纤维";
			case ZINC:
				return "补锌";
			case POTASSIUM:
				return "补钾";
			default:
				throw new IllegalArgumentException("非正式营养维度");
		}
	}

	private String dietRejectTagText(DietRequirementKey key) {
		switch (key) {
			case LOW_FAT:
				return "高脂";
			case LOW_SODIUM:
				return "高盐";
			case LOW_ADDED_SUGAR:
				return "高糖";
			case LOW_PURINE:
				return "高嘌呤";
			case LOW_CHOLESTEROL:
				return "高胆固醇";
			case LOW_CALORIE:
				return "高热量";
			case HIGH_FIBER:
				return "低纤维";
			case LIMIT_ALCOHOL:
				return "含酒精";
			case LIGHT_DIET:
				return "不清淡";
			default:
				throw new IllegalArgumentException("非正式饮食注意维度");
		}
	}

	/** 报告中生效维度及其去重后的原文证据。 */
	private static final class AdviceDimensions {

		private final Set<AllergenKey> allergenRejectKeySet = new LinkedHashSet<AllergenKey>();

		private final Set<DietRequirementKey> dietRejectKeySet = new LinkedHashSet<DietRequirementKey>();

		private final Map<NutritionKey, LinkedHashSet<String>> nutritionRawTextByKeyMap = new LinkedHashMap<NutritionKey, LinkedHashSet<String>>();

		private final Map<DietRequirementKey, LinkedHashSet<String>> dietRecommendRawTextByKeyMap = new LinkedHashMap<DietRequirementKey, LinkedHashSet<String>>();

		private boolean formalAdvicePresent;

	}

	/** 一个报告标签维度到 Redis 方向集合与展示语义的映射。 */
	private static final class SetDescriptor {

		private final DishTagSetRef setRef;

		private final boolean reject;

		private final String tagText;

		private final String rawText;

		private SetDescriptor(DishTagSetRef setRef, boolean reject, String tagText, String rawText) {
			this.setRef = setRef;
			this.reject = reject;
			this.tagText = tagText;
			this.rawText = rawText;
		}

	}

}
