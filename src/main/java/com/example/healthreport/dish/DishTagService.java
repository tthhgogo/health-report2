package com.example.healthreport.dish;

import com.example.healthreport.cache.DishTagCache;
import com.example.healthreport.constants.AllergenGroup;
import com.example.healthreport.constants.AllergenGroups;
import com.example.healthreport.constants.AllergenWord;
import com.example.healthreport.constants.Bucket;
import com.example.healthreport.constants.DietRequirementContents;
import com.example.healthreport.constants.DietRequirementKey;
import com.example.healthreport.constants.DietRequirementRule;
import com.example.healthreport.constants.PromptVersions;
import com.example.healthreport.constants.ReviewStatus;
import com.example.healthreport.constants.TagRuleVersion;
import com.example.healthreport.infra.DishQueryService;
import com.example.healthreport.llm.dishtag.DishTagBatchRejectedException;
import com.example.healthreport.llm.dishtag.DishTagCallException;
import com.example.healthreport.llm.dishtag.DishTagClient;
import com.example.healthreport.llm.dishtag.DishTagProperties;
import com.example.healthreport.llm.dishtag.DishTagInput;
import com.example.healthreport.llm.dishtag.DishTagOutput;
import com.example.healthreport.persistence.CtDishTagEntity;
import com.example.healthreport.persistence.CtDishTagService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
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

/** 离线菜品打标编排：按数据库 diff、零重试调用、写真源并预热 Redis。 */
@Slf4j
@Service
public class DishTagService {

	/** 单次打标调用的菜品上限；与输出 Schema 的数组上限一致，超出会被契约校验判整批作废。 */
	private static final int BATCH_SIZE = 40;

	private final DishQueryService dishQueryService;

	private final TagHashCalculator tagHashCalculator;

	private final CtDishTagService persistenceService;

	private final DishTagWriteService writeService;

	private final DishTagClient llmBClient;

	private final DishTagProperties properties;

	private final DishTagCache dishTagCache;

	private final ObjectMapper objectMapper;

	public DishTagService(DishQueryService dishQueryService, TagHashCalculator tagHashCalculator,
			CtDishTagService persistenceService, DishTagWriteService writeService, DishTagClient llmBClient,
			DishTagProperties properties, DishTagCache dishTagCache, ObjectMapper objectMapper) {
		this.dishQueryService = dishQueryService;
		this.tagHashCalculator = tagHashCalculator;
		this.persistenceService = persistenceService;
		this.writeService = writeService;
		this.llmBClient = llmBClient;
		this.properties = properties;
		this.dishTagCache = dishTagCache;
		this.objectMapper = objectMapper;
	}

	/** 使用入口传入的业务日完成全部离线预热步骤。 */
	public void run(LocalDate bizDate) {
		if (bizDate == null) {
			throw new IllegalArgumentException("业务日不能为空");
		}
		long startMillis = System.currentTimeMillis();
		List<Dish> dishList = DishQueryService.assertValidResult(dishQueryService.queryOnShelfDishes(bizDate));
		Map<Long, String> hashByDishIdMap = hashes(dishList);
		List<Dimension> dimensionList = dimensions();
		Set<Long> dishIdSet = new LinkedHashSet<Long>(hashByDishIdMap.keySet());
		Set<String> hashSet = new LinkedHashSet<String>(hashByDishIdMap.values());
		Set<String> enumKeySet = new LinkedHashSet<String>(dimensionList.size());
		for (Dimension dimension : dimensionList) {
			enumKeySet.add(dimension.enumKey);
		}
		List<CtDishTagEntity> existingEntityList = persistenceService.findCandidates(dishIdSet, hashSet, enumKeySet);
		Map<String, CtDishTagEntity> entityByTripleMap = exactExisting(existingEntityList, hashByDishIdMap, enumKeySet);

		for (CtDishTagEntity entity : entityByTripleMap.values()) {
			persistenceService.refreshLastSeen(entity, bizDate);
			entity.setLastSeenDate(bizDate);
		}

		// 复用数是这个 Job 最该被盯住的一个数：它应该接近「菜品数 × 维度数」。
		// 突然掉到接近 0，说明 tagHash 的某个输入变了（modelVersion / promptVersion /
		// tagRuleVersion / 菜品食材），今晚会全量重打标——那是几千次模型调用，
		// 不记这个数就只能等账单或超时告警来告诉你（§9.5.1）。
		int reusedCount = entityByTripleMap.size();
		log.info("菜品打标开始，业务日={}，在架菜品数={}，维度数={}，复用已有标签数={}，待打标三元组数={}",
				bizDate, dishList.size(), dimensionList.size(), reusedCount,
				dishList.size() * dimensionList.size() - reusedCount);

		Map<Long, Dish> dishByIdMap = new HashMap<Long, Dish>(dishList.size());
		for (Dish dish : dishList) {
			dishByIdMap.put(dish.getDishId(), dish);
		}
		int taggedCount = 0;
		int failedBatchCount = 0;
		for (Dimension dimension : dimensionList) {
			List<Dish> missingDishList = new ArrayList<Dish>();
			for (Dish dish : dishList) {
				if (!entityByTripleMap
					.containsKey(triple(dish.getDishId(), hashByDishIdMap.get(dish.getDishId()), dimension.enumKey))) {
					missingDishList.add(dish);
				}
			}
			BatchStats stats = tagMissingBatches(bizDate, dimension, missingDishList, hashByDishIdMap,
					dishByIdMap, entityByTripleMap);
			taggedCount += stats.taggedCount;
			failedBatchCount += stats.failedBatchCount;
			cacheDimension(bizDate, dimension.enumKey, dishList, hashByDishIdMap, entityByTripleMap);
			// 逐维度记一条：某个维度整体打标失败时，只看汇总数看不出是哪一维塌了，
			// 而那一维在线就会全部回落到 UNKNOWN——用户侧表现为该过敏原下菜品推荐异常。
			// enumKey 是维度枚举名，离线批处理不关联任何用户，与 §9.2 的红线无关。
			log.info("菜品打标维度完成，业务日={}，维度={}，待打标菜品数={}，新增标签数={}，作废批次数={}",
					bizDate, dimension.enumKey, missingDishList.size(),
					stats.taggedCount, stats.failedBatchCount);
		}

		log.info("菜品打标完成，业务日={}，在架菜品数={}，维度数={}，复用标签数={}，新增标签数={}，"
						+ "作废批次数={}，耗时={}ms",
				bizDate, dishList.size(), dimensionList.size(), reusedCount, taggedCount,
				failedBatchCount, System.currentTimeMillis() - startMillis);
	}

	private BatchStats tagMissingBatches(LocalDate bizDate, Dimension dimension, List<Dish> missingDishList,
			Map<Long, String> hashByDishIdMap, Map<Long, Dish> dishByIdMap,
			Map<String, CtDishTagEntity> entityByTripleMap) {
		int taggedCount = 0;
		int failedBatchCount = 0;
		for (int fromIndex = 0; fromIndex < missingDishList.size(); fromIndex += BATCH_SIZE) {
			int toIndex = Math.min(fromIndex + BATCH_SIZE, missingDishList.size());
			List<Dish> batchDishList = new ArrayList<Dish>(missingDishList.subList(fromIndex, toIndex));
			try {
				log.info("LLM-B 批次打标开始，维度={}，批次菜品数={}",
						dimension.enumKey, batchDishList.size());
				DishTagOutput output = llmBClient.tag(dimension.input(batchDishList));
				List<CtDishTagEntity> newEntityList = entities(output, dimension.enumKey, bizDate, hashByDishIdMap,
						dishByIdMap);
				for (CtDishTagEntity entity : newEntityList) {
					Set<String> ingredientNameSet = ingredientNames(dishByIdMap.get(entity.getDishId()));
					writeService.write(entity, ingredientNameSet);
					entityByTripleMap.put(triple(entity.getDishId(), entity.getTagHash(), entity.getEnumKey()), entity);
				}
				taggedCount += newEntityList.size();
				// 落库行数少于批次菜品数说明模型漏回了菜——那些菜今天没有标签，
				// 在线会回落到 UNKNOWN。不是异常，但必须看得见。
				log.info("LLM-B 批次打标成功，维度={}，批次菜品数={}，落库标签数={}", dimension.enumKey,
						batchDishList.size(), newEntityList.size());
			}
			catch (DishTagBatchRejectedException | DishTagCallException exception) {
				// 整批作废且零重试；日志只记录安全计数，不记录维度、菜名或模型正文。
				failedBatchCount++;
				log.warn("LLM-B批次契约失败，已整批作废，批次菜品数={}", batchDishList.size());
			}
		}
		return new BatchStats(taggedCount, failedBatchCount);
	}

	/** 一个维度内全部批次的打标结果计数，只含数量，不含菜名或标签内容。 */
	private static final class BatchStats {

		private final int taggedCount;

		private final int failedBatchCount;

		private BatchStats(int taggedCount, int failedBatchCount) {
			this.taggedCount = taggedCount;
			this.failedBatchCount = failedBatchCount;
		}

	}

	private List<CtDishTagEntity> entities(DishTagOutput output, String enumKey, LocalDate bizDate,
			Map<Long, String> hashByDishIdMap, Map<Long, Dish> dishByIdMap) {
		List<CtDishTagEntity> entityList = new ArrayList<CtDishTagEntity>(
				output.getNeutralDishIds().size() + output.getUnknownDishIds().size() + output.getHitList().size());
		for (Long dishId : output.getNeutralDishIds()) {
			entityList.add(writeService.stateEntity(dishId, hashByDishIdMap.get(dishId), enumKey, TagState.NEUTRAL,
					properties.getModelVersionDishtag(), PromptVersions.DISH_TAG, TagRuleVersion.VALUE, bizDate));
		}
		for (Long dishId : output.getUnknownDishIds()) {
			entityList.add(writeService.stateEntity(dishId, hashByDishIdMap.get(dishId), enumKey, TagState.UNKNOWN,
					properties.getModelVersionDishtag(), PromptVersions.DISH_TAG, TagRuleVersion.VALUE, bizDate));
		}
		for (DishTagOutput.Hit hit : output.getHitList()) {
			entityList.add(writeService.toEntity(hit, dishByIdMap.get(hit.getDishId()),
					hashByDishIdMap.get(hit.getDishId()), enumKey, properties.getModelVersionDishtag(),
					PromptVersions.DISH_TAG, TagRuleVersion.VALUE, bizDate));
		}
		return entityList;
	}

	private void cacheDimension(LocalDate bizDate, String enumKey, List<Dish> dishList,
			Map<Long, String> hashByDishIdMap, Map<String, CtDishTagEntity> entityByTripleMap) {
		Map<String, TagValue> valueByFieldMap = new LinkedHashMap<String, TagValue>();
		for (Dish dish : dishList) {
			String tagHash = hashByDishIdMap.get(dish.getDishId());
			CtDishTagEntity entity = entityByTripleMap.get(triple(dish.getDishId(), tagHash, enumKey));
			if (entity != null) {
				valueByFieldMap.put(dishTagCache.field(dish.getDishId(), tagHash), toTagValue(entity));
			}
		}
		dishTagCache.putAll(bizDate, enumKey, valueByFieldMap);
	}

	/** 将数据库实体转换为在线只需的安全缓存值。 */
	public TagValue toTagValue(CtDishTagEntity entity) {
		List<String> matchedIngredientList = Collections.emptyList();
		if (entity.getMatchedIngredients() != null) {
			try {
				matchedIngredientList = objectMapper.readValue(entity.getMatchedIngredients(),
						new TypeReference<List<String>>() {
						});
			}
			catch (IOException | RuntimeException exception) {
				// 真源行证据损坏时保留 verdict，但不扩散损坏文本。
				matchedIngredientList = Collections.emptyList();
			}
		}
		return new TagValue(TagState.valueOf(entity.getVerdict()), matchedIngredientList);
	}

	private Map<Long, String> hashes(List<Dish> dishList) {
		Map<Long, String> resultMap = new LinkedHashMap<Long, String>(dishList.size());
		for (Dish dish : dishList) {
			if (resultMap.put(dish.getDishId(), tagHashCalculator.calculate(TagRuleVersion.VALUE,
					PromptVersions.DISH_TAG, properties.getModelVersionDishtag(), dish)) != null) {
				throw new IllegalArgumentException("在架菜品ID重复");
			}
		}
		return resultMap;
	}

	private Map<String, CtDishTagEntity> exactExisting(List<CtDishTagEntity> candidateEntityList,
			Map<Long, String> hashByDishIdMap, Set<String> enumKeySet) {
		Map<String, CtDishTagEntity> resultMap = new HashMap<String, CtDishTagEntity>();
		for (CtDishTagEntity entity : candidateEntityList) {
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
