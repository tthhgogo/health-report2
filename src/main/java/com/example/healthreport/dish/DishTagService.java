package com.example.healthreport.dish;

import com.example.healthreport.cache.DishRecommendSetCache;
import com.example.healthreport.cache.DishSetMemberCodec;
import com.example.healthreport.cache.DishTagSetCatalog;
import com.example.healthreport.cache.DishTagSetRef;
import com.example.healthreport.constants.AllergenGroup;
import com.example.healthreport.constants.AllergenGroups;
import com.example.healthreport.constants.AllergenWord;
import com.example.healthreport.constants.Bucket;
import com.example.healthreport.constants.DietRequirementContents;
import com.example.healthreport.constants.DietRequirementKey;
import com.example.healthreport.constants.DietRequirementRule;
import com.example.healthreport.constants.NutritionKey;
import com.example.healthreport.constants.PromptVersions;
import com.example.healthreport.constants.ReviewStatus;
import com.example.healthreport.constants.TagRuleVersion;
import com.example.healthreport.infra.CompanyCursorPage;
import com.example.healthreport.infra.DishCursorPage;
import com.example.healthreport.infra.DishQueryService;
import com.example.healthreport.llm.dishtag.DishTagBatchRejectedException;
import com.example.healthreport.llm.dishtag.DishTagCallException;
import com.example.healthreport.llm.dishtag.DishTagClient;
import com.example.healthreport.llm.dishtag.DishTagInput;
import com.example.healthreport.llm.dishtag.DishTagOutput;
import com.example.healthreport.llm.dishtag.DishTagProperties;
import com.example.healthreport.persistence.CtDishTagEntity;
import com.example.healthreport.persistence.CtDishTagService;
import com.example.healthreport.safety.AllergenKeywordFallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 按企业与菜品游标分页构建、校验并原子发布每日 33 个方向 SET。 */
@Slf4j
@Service
public class DishTagService {

	/** 单次 LLM-B 调用的菜品上限；与输出 Schema 数组上限一致。 */
	private static final int MODEL_BATCH_SIZE = 40;

	/** 企业和菜品查询默认页容量；设计方案规定 500，仍需按真实菜单压测校准。 */
	private static final int DEFAULT_QUERY_PAGE_SIZE = 500;

	private final DishQueryService dishQueryService;

	private final TagHashCalculator tagHashCalculator;

	private final CtDishTagService persistenceService;

	private final DishTagWriteService writeService;

	private final DishTagClient llmBClient;

	private final DishTagProperties properties;

	private final NutritionMatcher nutritionMatcher;

	private final DietPositiveMatcher dietPositiveMatcher;

	private final AllergenKeywordFallback allergenKeywordFallback;

	private final DishRecommendSetCache setCache;

	private final DishTagSetCatalog setCatalog;

	private final DishSetMemberCodec memberCodec;

	private final int queryPageSize;

	public DishTagService(DishQueryService dishQueryService, TagHashCalculator tagHashCalculator,
			CtDishTagService persistenceService, DishTagWriteService writeService, DishTagClient llmBClient,
			DishTagProperties properties, NutritionMatcher nutritionMatcher, DietPositiveMatcher dietPositiveMatcher,
			AllergenKeywordFallback allergenKeywordFallback, DishRecommendSetCache setCache,
			DishTagSetCatalog setCatalog, DishSetMemberCodec memberCodec,
			@Value("${dish.tag-query-page-size:500}") int queryPageSize) {
		this.dishQueryService = dishQueryService;
		this.tagHashCalculator = tagHashCalculator;
		this.persistenceService = persistenceService;
		this.writeService = writeService;
		this.llmBClient = llmBClient;
		this.properties = properties;
		this.nutritionMatcher = nutritionMatcher;
		this.dietPositiveMatcher = dietPositiveMatcher;
		this.allergenKeywordFallback = allergenKeywordFallback;
		this.setCache = setCache;
		this.setCatalog = setCatalog;
		this.memberCodec = memberCodec;
		this.queryPageSize = queryPageSize > 0 ? queryPageSize : DEFAULT_QUERY_PAGE_SIZE;
	}

	/** 使用调度入口传入的统一业务日，逐企业独立构建和发布。 */
	public void run(LocalDate bizDate) {
		if (bizDate == null) {
			throw new IllegalArgumentException("业务日不能为空");
		}
		long startMillis = System.currentTimeMillis();
		int companyTotal = 0;
		int publishedCompanyTotal = 0;
		String lastCompanyId = null;
		while (true) {
			CompanyCursorPage companyPage = DishQueryService.assertValidCompanyPage(
					dishQueryService.queryPreheatCompanyPage(bizDate, lastCompanyId, queryPageSize), lastCompanyId,
					queryPageSize);
			for (String companyId : companyPage.getCompanyIdList()) {
				companyTotal++;
				if (buildCompany(companyId, bizDate)) {
					publishedCompanyTotal++;
				}
			}
			if (companyPage.getCompanyIdList().size() < queryPageSize) {
				break;
			}
			lastCompanyId = companyPage.getLastCompanyId();
		}
		log.info("菜品打标完成，业务日={}，企业数={}，发布企业数={}，耗时={}ms", bizDate, companyTotal, publishedCompanyTotal,
				System.currentTimeMillis() - startMillis);
	}

	private boolean buildCompany(String companyId, LocalDate bizDate) {
		String buildId = UUID.randomUUID().toString().replace("-", "");
		try {
			long countBefore = dishQueryService.countOnShelfDishes(companyId, bizDate);
			if (countBefore < 0L) {
				throw new IllegalStateException("在架菜品计数不能为负数");
			}
			Set<Long> processedDishIdSet = new LinkedHashSet<Long>();
			Long lastDishesId = null;
			while (true) {
				DishCursorPage dishPage = DishQueryService.assertValidDishPage(
						dishQueryService.queryOnShelfDishPage(companyId, bizDate, lastDishesId, queryPageSize),
						companyId, lastDishesId, queryPageSize);
				if (dishPage.getDishList().isEmpty()) {
					break;
				}
				List<Dish> enrichedDishList = enrichPage(companyId, dishPage.getDishList());
				for (Dish dish : enrichedDishList) {
					if (!processedDishIdSet.add(dish.getDishId())) {
						throw new IllegalStateException("企业分页出现重复菜品ID");
					}
				}
				processPage(companyId, bizDate, buildId, enrichedDishList);
				if (dishPage.getDishList().size() < queryPageSize) {
					break;
				}
				lastDishesId = dishPage.getLastDishesId();
			}
			long countAfter = dishQueryService.countOnShelfDishes(companyId, bizDate);
			if (countBefore != countAfter || countBefore != processedDishIdSet.size()) {
				throw new IllegalStateException("企业菜品分页数量与前后计数不一致");
			}
			setCache.publish(companyId, bizDate, buildId, setCatalog.publishRefs());
			log.info("企业菜品标签快照发布完成，业务日={}，菜品数={}，方向集合数={}", bizDate, processedDishIdSet.size(),
					setCatalog.publishRefs().size());
			return true;
		}
		catch (RuntimeException exception) {
			discardSafely(companyId, bizDate, buildId);
			// 企业标识与可能含 Key 的异常正文均不进入日志；保留堆栈便于定位确定性失败点。
			log.error("企业菜品标签快照构建失败，其他企业继续构建", sanitizedException("企业快照构建异常类型:", exception));
			return false;
		}
	}

	/** 清理失败不得打断企业隔离循环；staging 仍会由六小时 TTL 回收。 */
	private void discardSafely(String companyId, LocalDate bizDate, String buildId) {
		try {
			setCache.discard(companyId, bizDate, buildId, setCatalog.publishRefs());
		}
		catch (RuntimeException discardException) {
			log.error("企业菜品标签构建集合清理失败，等待TTL回收", sanitizedException("构建集合清理异常类型:", discardException));
		}
	}

	/** 不携带外部异常正文，仅保留类型和堆栈的可记录异常。 */
	private RuntimeException sanitizedException(String prefix, RuntimeException exception) {
		RuntimeException sanitizedException = new IllegalStateException(prefix + exception.getClass().getName());
		sanitizedException.setStackTrace(exception.getStackTrace());
		return sanitizedException;
	}

	private List<Dish> enrichPage(String companyId, List<Dish> baseDishList) {
		List<Long> dishIdList = new ArrayList<Long>(baseDishList.size());
		for (Dish dish : baseDishList) {
			dishIdList.add(dish.getDishId());
		}
		Map<Long, List<DishIngredient>> ingredientListMap = DishQueryService
			.assertValidIngredientMap(dishQueryService.queryIngredientListMap(companyId, dishIdList), dishIdList);
		List<Dish> resultList = new ArrayList<Dish>(baseDishList.size());
		for (Dish dish : baseDishList) {
			if (!companyId.equals(dish.getCompanyId())) {
				throw new IllegalStateException("菜品企业归属不一致");
			}
			resultList.add(
					new Dish(companyId, dish.getDishId(), dish.getDishName(), ingredientListMap.get(dish.getDishId())));
		}
		return resultList;
	}

	private void processPage(String companyId, LocalDate bizDate, String buildId, List<Dish> dishList) {
		Map<Long, String> hashByDishIdMap = hashes(dishList);
		List<Dimension> dimensionList = dimensions();
		Set<Long> dishIdSet = new LinkedHashSet<Long>(hashByDishIdMap.keySet());
		Set<String> hashSet = new LinkedHashSet<String>(hashByDishIdMap.values());
		Set<String> enumKeySet = new LinkedHashSet<String>(dimensionList.size());
		for (Dimension dimension : dimensionList) {
			enumKeySet.add(dimension.enumKey);
		}
		Map<String, CtDishTagEntity> entityByTripleMap = exactExisting(companyId,
				persistenceService.findCandidates(companyId, dishIdSet, hashSet, enumKeySet), hashByDishIdMap,
				enumKeySet);
		for (CtDishTagEntity entity : entityByTripleMap.values()) {
			persistenceService.refreshLastSeen(entity, bizDate);
			entity.setLastSeenDate(bizDate);
		}

		Map<Long, Dish> dishByIdMap = new HashMap<Long, Dish>(dishList.size());
		for (Dish dish : dishList) {
			dishByIdMap.put(dish.getDishId(), dish);
		}
		for (Dimension dimension : dimensionList) {
			List<Dish> missingDishList = new ArrayList<Dish>();
			for (Dish dish : dishList) {
				if (!entityByTripleMap
					.containsKey(triple(dish.getDishId(), hashByDishIdMap.get(dish.getDishId()), dimension.enumKey))) {
					missingDishList.add(dish);
				}
			}
			tagMissingBatches(bizDate, dimension, missingDishList, hashByDishIdMap, dishByIdMap, entityByTripleMap);
		}
		assertComplete(dishList, dimensionList, hashByDishIdMap, entityByTripleMap);
		appendDirections(companyId, bizDate, buildId, dishList, hashByDishIdMap, entityByTripleMap, dimensionList);
	}

	private void tagMissingBatches(LocalDate bizDate, Dimension dimension, List<Dish> missingDishList,
			Map<Long, String> hashByDishIdMap, Map<Long, Dish> dishByIdMap,
			Map<String, CtDishTagEntity> entityByTripleMap) {
		for (int fromIndex = 0; fromIndex < missingDishList.size(); fromIndex += MODEL_BATCH_SIZE) {
			int toIndex = Math.min(fromIndex + MODEL_BATCH_SIZE, missingDishList.size());
			List<Dish> batchDishList = new ArrayList<Dish>(missingDishList.subList(fromIndex, toIndex));
			try {
				DishTagOutput output = llmBClient.tag(dimension.input(batchDishList));
				for (CtDishTagEntity entity : entities(output, dimension.enumKey, bizDate, hashByDishIdMap,
						dishByIdMap)) {
					writeService.write(entity, ingredientNames(dishByIdMap.get(entity.getDishId())));
					entityByTripleMap.put(triple(entity.getDishId(), entity.getTagHash(), entity.getEnumKey()), entity);
				}
			}
			catch (DishTagBatchRejectedException | DishTagCallException exception) {
				log.warn("LLM-B批次失败，企业快照将不发布，批次菜品数={}", batchDishList.size());
				throw new IllegalStateException("LLM-B批次打标失败");
			}
		}
	}

	private List<CtDishTagEntity> entities(DishTagOutput output, String enumKey, LocalDate bizDate,
			Map<Long, String> hashByDishIdMap, Map<Long, Dish> dishByIdMap) {
		List<CtDishTagEntity> entityList = new ArrayList<CtDishTagEntity>(
				output.getNeutralDishIds().size() + output.getUnknownDishIds().size() + output.getHitList().size());
		for (Long dishId : output.getNeutralDishIds()) {
			Dish dish = dishByIdMap.get(dishId);
			entityList.add(writeService.stateEntity(dish.getCompanyId(), dishId, hashByDishIdMap.get(dishId), enumKey,
					TagState.NEUTRAL, properties.getModelVersionDishtag(), PromptVersions.DISH_TAG,
					TagRuleVersion.VALUE, bizDate));
		}
		for (Long dishId : output.getUnknownDishIds()) {
			Dish dish = dishByIdMap.get(dishId);
			entityList.add(writeService.stateEntity(dish.getCompanyId(), dishId, hashByDishIdMap.get(dishId), enumKey,
					TagState.UNKNOWN, properties.getModelVersionDishtag(), PromptVersions.DISH_TAG,
					TagRuleVersion.VALUE, bizDate));
		}
		for (DishTagOutput.Hit hit : output.getHitList()) {
			entityList.add(writeService.toEntity(hit, dishByIdMap.get(hit.getDishId()),
					hashByDishIdMap.get(hit.getDishId()), enumKey, properties.getModelVersionDishtag(),
					PromptVersions.DISH_TAG, TagRuleVersion.VALUE, bizDate));
		}
		return entityList;
	}

	private void appendDirections(String companyId, LocalDate bizDate, String buildId, List<Dish> dishList,
			Map<Long, String> hashByDishIdMap, Map<String, CtDishTagEntity> entityByTripleMap,
			List<Dimension> dimensionList) {
		Map<DishTagSetRef, Set<String>> memberByRefMap = new LinkedHashMap<DishTagSetRef, Set<String>>();
		for (Dish dish : dishList) {
			String member = memberCodec.encode(dish.getDishId(), dish.getDishName());
			Map<String, TagState> stateByEnumKeyMap = finalSafetyStates(dish, hashByDishIdMap.get(dish.getDishId()),
					entityByTripleMap, dimensionList);
			for (AllergenGroup group : AllergenGroups.foodBorneGroups()) {
				if (stateByEnumKeyMap.get(group.getKey().name()) == TagState.REJECT) {
					addMember(memberByRefMap, setCatalog.ref(DishTagSetRef.Category.ALLERGEN,
							DishTagSetRef.Direction.REJECT, group.getKey().name()), member);
				}
			}
			for (DietRequirementKey key : DietRequirementKey.values()) {
				if (key != DietRequirementKey.OTHER && stateByEnumKeyMap.get(key.name()) == TagState.REJECT) {
					addMember(memberByRefMap,
							setCatalog.ref(DishTagSetRef.Category.DIET, DishTagSetRef.Direction.REJECT, key.name()),
							member);
				}
			}
			if (!stateByEnumKeyMap.containsValue(TagState.UNKNOWN)) {
				appendPositiveDirections(memberByRefMap, member, dish, stateByEnumKeyMap);
			}
		}
		for (Map.Entry<DishTagSetRef, Set<String>> entry : memberByRefMap.entrySet()) {
			setCache.append(companyId, bizDate, buildId, entry.getKey(), entry.getValue());
		}
	}

	private Map<String, TagState> finalSafetyStates(Dish dish, String tagHash,
			Map<String, CtDishTagEntity> entityByTripleMap, List<Dimension> dimensionList) {
		Map<String, TagState> resultMap = new LinkedHashMap<String, TagState>();
		for (Dimension dimension : dimensionList) {
			CtDishTagEntity entity = entityByTripleMap.get(triple(dish.getDishId(), tagHash, dimension.enumKey));
			resultMap.put(dimension.enumKey, TagState.valueOf(entity.getVerdict()));
		}
		for (AllergenGroup group : AllergenGroups.foodBorneGroups()) {
			if (allergenKeywordFallback.matches(group, dish)) {
				resultMap.put(group.getKey().name(), TagState.REJECT);
			}
		}
		return resultMap;
	}

	private void appendPositiveDirections(Map<DishTagSetRef, Set<String>> memberByRefMap, String member, Dish dish,
			Map<String, TagState> stateByEnumKeyMap) {
		for (NutritionKey key : NutritionKey.values()) {
			if (key != NutritionKey.OTHER && nutritionMatcher.match(dish, key).getState() == TagState.RECOMMEND) {
				addMember(memberByRefMap,
						setCatalog.ref(DishTagSetRef.Category.NUTRITION, DishTagSetRef.Direction.RECOMMEND, key.name()),
						member);
			}
		}
		DietRequirementKey[] positiveKeyArray = new DietRequirementKey[] { DietRequirementKey.LOW_PURINE,
				DietRequirementKey.HIGH_FIBER };
		for (DietRequirementKey key : positiveKeyArray) {
			TagState safetyState = stateByEnumKeyMap.get(key.name());
			if (dietPositiveMatcher.match(dish, key, safetyState).getState() == TagState.RECOMMEND) {
				addMember(memberByRefMap,
						setCatalog.ref(DishTagSetRef.Category.DIET, DishTagSetRef.Direction.RECOMMEND, key.name()),
						member);
			}
		}
	}

	private void addMember(Map<DishTagSetRef, Set<String>> memberByRefMap, DishTagSetRef setRef, String member) {
		Set<String> memberSet = memberByRefMap.get(setRef);
		if (memberSet == null) {
			memberSet = new LinkedHashSet<String>();
			memberByRefMap.put(setRef, memberSet);
		}
		memberSet.add(member);
	}

	private void assertComplete(List<Dish> dishList, List<Dimension> dimensionList, Map<Long, String> hashByDishIdMap,
			Map<String, CtDishTagEntity> entityByTripleMap) {
		for (Dish dish : dishList) {
			for (Dimension dimension : dimensionList) {
				if (!entityByTripleMap
					.containsKey(triple(dish.getDishId(), hashByDishIdMap.get(dish.getDishId()), dimension.enumKey))) {
					throw new IllegalStateException("当前页标签维度覆盖不完整");
				}
			}
		}
	}

	private Map<Long, String> hashes(List<Dish> dishList) {
		Map<Long, String> resultMap = new LinkedHashMap<Long, String>(dishList.size());
		for (Dish dish : dishList) {
			if (resultMap.put(dish.getDishId(), tagHashCalculator.calculate(TagRuleVersion.VALUE,
					PromptVersions.DISH_TAG, properties.getModelVersionDishtag(), dish)) != null) {
				throw new IllegalArgumentException("当前企业分页菜品ID重复");
			}
		}
		return resultMap;
	}

	private Map<String, CtDishTagEntity> exactExisting(String companyId, List<CtDishTagEntity> candidateEntityList,
			Map<Long, String> hashByDishIdMap, Set<String> enumKeySet) {
		Map<String, CtDishTagEntity> resultMap = new HashMap<String, CtDishTagEntity>();
		for (CtDishTagEntity entity : candidateEntityList) {
			if (!companyId.equals(entity.getCompanyId())) {
				throw new IllegalStateException("菜品标签数据库返回了其他企业数据");
			}
			if (enumKeySet.contains(entity.getEnumKey())
					&& entity.getTagHash().equals(hashByDishIdMap.get(entity.getDishId()))) {
				resultMap.put(triple(entity.getDishId(), entity.getTagHash(), entity.getEnumKey()), entity);
			}
		}
		return resultMap;
	}

	private List<Dimension> dimensions() {
		List<Dimension> resultList = new ArrayList<Dimension>(22);
		for (AllergenGroup group : AllergenGroups.foodBorneGroups()) {
			List<String> avoidFoodList = new ArrayList<String>();
			List<String> hiddenFoodList = new ArrayList<String>();
			for (AllergenWord word : group.getWordList()) {
				if (word.getReviewStatus() == ReviewStatus.REVIEWED) {
					if (word.getBucket() == Bucket.AVOID) {
						avoidFoodList.add(word.getDisplayName());
					}
					else {
						hiddenFoodList.add(word.getDisplayName());
					}
				}
			}
			resultList.add(new Dimension(group.getKey().name(), group.getDisplayName(), avoidFoodList, hiddenFoodList,
					Collections.<String>emptyList(), Collections.<String>emptyList()));
		}
		for (Map.Entry<DietRequirementKey, DietRequirementRule> entry : DietRequirementContents.ALL.entrySet()) {
			DietRequirementRule rule = entry.getValue();
			boolean reviewed = rule.getReviewStatus() == ReviewStatus.REVIEWED;
			resultList.add(new Dimension(entry.getKey().name(), dietDisplayName(entry.getKey()),
					reviewed ? rule.getAvoidFoodList() : Collections.<String>emptyList(),
					Collections.<String>emptyList(),
					reviewed ? rule.getAvoidDishPatternList() : Collections.<String>emptyList(),
					reviewed ? rule.getCookingTipList() : Collections.<String>emptyList()));
		}
		return resultList;
	}

	private String dietDisplayName(DietRequirementKey key) {
		switch (key) {
			case LOW_FAT:
				return "低脂";
			case LOW_SODIUM:
				return "低盐";
			case LOW_ADDED_SUGAR:
				return "限制添加糖";
			case LOW_PURINE:
				return "低嘌呤";
			case LOW_CHOLESTEROL:
				return "低胆固醇";
			case LOW_CALORIE:
				return "控制体重";
			case HIGH_FIBER:
				return "高纤维";
			case LIMIT_ALCOHOL:
				return "限酒";
			case LIGHT_DIET:
				return "清淡饮食";
			default:
				throw new IllegalArgumentException("非正式饮食注意维度");
		}
	}

	private Set<String> ingredientNames(Dish dish) {
		Set<String> resultSet = new HashSet<String>(dish.getIngredientList().size());
		for (DishIngredient ingredient : dish.getIngredientList()) {
			resultSet.add(ingredient.getName());
		}
		return resultSet;
	}

	private String triple(long dishId, String tagHash, String enumKey) {
		return dishId + "|" + tagHash + "|" + enumKey;
	}

	/** 一个由版本覆盖全部内容字段的 LLM-B 维度。 */
	private static final class Dimension {

		private final String enumKey;

		private final String displayName;

		private final List<String> avoidFoodList;

		private final List<String> hiddenFoodList;

		private final List<String> avoidDishPatternList;

		private final List<String> cookingTipList;

		private Dimension(String enumKey, String displayName, List<String> avoidFoodList, List<String> hiddenFoodList,
				List<String> avoidDishPatternList, List<String> cookingTipList) {
			this.enumKey = enumKey;
			this.displayName = displayName;
			this.avoidFoodList = avoidFoodList;
			this.hiddenFoodList = hiddenFoodList;
			this.avoidDishPatternList = avoidDishPatternList;
			this.cookingTipList = cookingTipList;
		}

		private DishTagInput input(List<Dish> dishList) {
			return new DishTagInput(enumKey, displayName, avoidFoodList, hiddenFoodList, avoidDishPatternList,
					cookingTipList, dishList);
		}

	}

}
