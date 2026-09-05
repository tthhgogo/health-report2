package com.example.healthreport.dish;

import com.example.healthreport.persistence.CtDishTagEntity;
import com.example.healthreport.persistence.CtDishTagService;
import com.example.healthreport.llm.dishtag.DishTagOutput;
import com.example.healthreport.support.SystemActor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 菜品标签写入前证据一致性规则的唯一执行点。
 */
@Service
public class DishTagWriteService {

	private final CtDishTagService dishTagService;

	private final ObjectMapper objectMapper;

	public DishTagWriteService(CtDishTagService dishTagService, ObjectMapper objectMapper) {
		this.dishTagService = dishTagService;
		this.objectMapper = objectMapper;
	}

	/**
	 * B2 与 B3 唯一执行点：REJECT 的 evidence_type 必填；INGREDIENT 证据必须非空且属于菜品食材。 DDL 无兜底。
	 */
	public void write(CtDishTagEntity dishTagEntity, Set<String> dishIngredientNameSet) {
		if (dishTagEntity == null || dishIngredientNameSet == null) {
			throw new IllegalArgumentException("标签和食材集合不能为空");
		}
		validateEntity(dishTagEntity, dishIngredientNameSet);
		int insertedRows = dishTagService.insertFromJob(dishTagEntity);
		if (insertedRows != 1) {
			throw new IllegalStateException("菜品标签写入行数异常");
		}
	}

	/**
	 * 将一个模型结论转成可落库实体；不成立的食材证据降为 UNKNOWN，绝不降为 NEUTRAL。
	 */
	public CtDishTagEntity toEntity(DishTagOutput.Hit hit, Dish dish, String tagHash, String enumKey,
			String modelVersion, String promptVersion, String tagRuleVersion, LocalDate bizDate) {
		Set<String> ingredientNameSet = new HashSet<String>(dish.getIngredientList().size());
		for (DishIngredient ingredient : dish.getIngredientList()) {
			ingredientNameSet.add(ingredient.getName());
		}
		List<String> validMatchedIngredientList = new ArrayList<String>();
		for (String matchedIngredient : hit.getMatchedIngredients()) {
			if (ingredientNameSet.contains(matchedIngredient)) {
				validMatchedIngredientList.add(matchedIngredient);
			}
		}
		boolean suppliedIngredientEvidence = !hit.getMatchedIngredients().isEmpty();
		boolean invalidIngredientEvidence = "INGREDIENT".equals(hit.getEvidenceType())
				&& validMatchedIngredientList.isEmpty();
		boolean allSuppliedEvidenceInvalid = suppliedIngredientEvidence && validMatchedIngredientList.isEmpty();

		CtDishTagEntity entity = baseEntity(dish.getDishId(), tagHash, enumKey, modelVersion, promptVersion,
				tagRuleVersion, bizDate);
		entity.setCompanyId(dish.getCompanyId());
		if (invalidIngredientEvidence || allSuppliedEvidenceInvalid) {
			entity.setVerdict(TagState.UNKNOWN.name());
			entity.setEvidenceType(null);
			entity.setMatchedIngredients(toJson(Collections.<String>emptyList()));
			entity.setReason(null);
		}
		else {
			entity.setVerdict(TagState.REJECT.name());
			entity.setEvidenceType(hit.getEvidenceType());
			entity.setMatchedIngredients(toJson(validMatchedIngredientList));
			entity.setReason(hit.getReason());
		}
		return entity;
	}

	/** 创建不携带证据的 NEUTRAL 或 UNKNOWN 标签实体。 */
	public CtDishTagEntity stateEntity(String companyId, long dishId, String tagHash, String enumKey, TagState state,
			String modelVersion, String promptVersion, String tagRuleVersion, LocalDate bizDate) {
		if (state != TagState.NEUTRAL && state != TagState.UNKNOWN) {
			throw new IllegalArgumentException("离线无证据标签只允许NEUTRAL或UNKNOWN");
		}
		CtDishTagEntity entity = baseEntity(dishId, tagHash, enumKey, modelVersion, promptVersion, tagRuleVersion,
				bizDate);
		entity.setCompanyId(companyId);
		entity.setVerdict(state.name());
		entity.setMatchedIngredients(toJson(Collections.<String>emptyList()));
		return entity;
	}

	private CtDishTagEntity baseEntity(long dishId, String tagHash, String enumKey, String modelVersion,
			String promptVersion, String tagRuleVersion, LocalDate bizDate) {
		CtDishTagEntity entity = new CtDishTagEntity();
		entity.setDishId(dishId);
		entity.setTagHash(tagHash);
		entity.setEnumKey(enumKey);
		entity.setModelVersion(modelVersion);
		entity.setPromptVersion(promptVersion);
		entity.setTagRuleVersion(tagRuleVersion);
		entity.setLastSeenDate(bizDate);
		entity.setCreateBy(SystemActor.DISH_TAG_JOB);
		entity.setUpdateBy(SystemActor.DISH_TAG_JOB);
		return entity;
	}

	private void validateEntity(CtDishTagEntity entity, Set<String> dishIngredientNameSet) {
		if (entity.getCompanyId() == null || entity.getCompanyId().length() == 0) {
			throw new IllegalArgumentException("菜品标签企业ID不能为空");
		}
		if (TagState.REJECT.name().equals(entity.getVerdict())) {
			if (entity.getEvidenceType() == null) {
				throw new IllegalArgumentException("REJECT证据类型不能为空");
			}
			if ("INGREDIENT".equals(entity.getEvidenceType())) {
				try {
					List<?> matchedList = objectMapper.readValue(entity.getMatchedIngredients(), List.class);
					if (matchedList.isEmpty() || !dishIngredientNameSet.containsAll(matchedList)) {
						throw new IllegalArgumentException("INGREDIENT证据必须属于菜品食材");
					}
				}
				catch (JsonProcessingException exception) {
					throw new IllegalArgumentException("命中食材JSON不合法", exception);
				}
			}
		}
		// matched_ingredients VARCHAR(512) / reason VARCHAR(256)：schema 的 maxItems/maxLength
		// 已把正常值挡在远低于列宽处，这里只防 JSON 转义把长度翻倍的极端食材名——超限必须
		// 显式失败，不能等 insert 报 SQL 异常。
		if (entity.getMatchedIngredients() != null && entity.getMatchedIngredients().length() > 512) {
			throw new IllegalArgumentException("命中食材JSON超过数据库列宽512");
		}
		if (entity.getReason() != null && entity.getReason().length() > 256) {
			throw new IllegalArgumentException("判定理由超过数据库列宽256");
		}
		// DDL 没有 CHECK 兜底，本方法是写库前业务规则的唯一执行点。
	}

	private String toJson(List<String> matchedIngredientList) {
		try {
			return objectMapper.writeValueAsString(matchedIngredientList);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("命中食材序列化失败", exception);
		}
	}

}
