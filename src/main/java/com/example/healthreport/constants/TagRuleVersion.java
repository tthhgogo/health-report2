package com.example.healthreport.constants;

/**
 * 影响打标的内容版本。
 * <p>
 * 改动以下任何一处都必须 bump 本值，并触发全量重打标：
 * </p>
 * <ul>
 * <li>{@link AllergenGroups} 的任何词条</li>
 * <li>{@link AllergenExceptions} 的任何例外</li>
 * <li>{@link NutritionContents} 的 recommendableFoodList</li>
 * <li>{@link DietRequirementContents} 的 avoidFoodList / avoidDishPatternList / cookingTipList
 * ——前两项与 cookingTipList 都会随维度批次进入 LLM-B 提示词</li>
 * <li>{@link DietRequirementContents} 的 positiveMatchPolicy / recommendableFoodList /
 * recommendTagText / positiveReviewStatus ——它们不进提示词，只驱动 Java 正向匹配，
 * 但与 {@link NutritionContents} 的 recommendableFoodList 同理：能改变用户可见结论的内容一律版本钉住</li>
 * </ul>
 * <p>
 * <b>忘记 bump 的后果是静默的</b>：菜和食材都没变，凌晨预热的 diff 会认为标签已存在而跳过， 新规则永远不生效，且不报错、不告警。建议在 CI
 * 加校验——本包有 diff 但本值未变则构建失败。
 * </p>
 * <p>
 * 本值进打标输入哈希 {@code tagHash = sha256(tagRuleVersion|promptVersion|modelVersion|菜名|食材串)}。
 * <b>不存在单独的 tagPolicyVersion</b>——判定方与被判定方合在同一个哈希里， 唯一键是
 * {@code (dish_id, tag_hash, enum_key)}。
 * </p>
 */
public final class TagRuleVersion {

	/** 2026-09-04 PROTEIN 裁减瘦牛肉、黄豆、牛奶以满足 3~8 展示上限（待医务审核确认）后的版本。 */
	public static final String VALUE = "tag-1.2.0";

	private TagRuleVersion() {
	}

}
