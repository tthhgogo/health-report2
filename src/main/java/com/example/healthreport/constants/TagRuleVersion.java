package com.example.healthreport.constants;

/**
 * 影响打标的内容版本。
 * <p>改动以下任何一处都必须 bump 本值，并触发全量重打标：</p>
 * <ul>
 *   <li>{@link AllergenGroups} 的任何词条</li>
 *   <li>{@link AllergenExceptions} 的任何例外</li>
 *   <li>{@link NutritionContents} 的 recommendableFoodList</li>
 *   <li>{@link DietRequirementContents} 的 avoidFoodList / avoidDishPatternList</li>
 * </ul>
 * <p><b>忘记 bump 的后果是静默的</b>：菜和食材都没变，凌晨预热的 diff 会认为标签已存在而跳过，
 * 新规则永远不生效，且不报错、不告警。建议在 CI 加校验——本包有 diff 但本值未变则构建失败。</p>
 * <p>本值进 {@code tagPolicyVersion = sha256(modelVersion|promptVersion|tagRuleVersion)}。</p>
 */
public final class TagRuleVersion {

    /** 当前版本。全部内容仍为 DRAFT，未进入生产。 */
    public static final String VALUE = "tag-0.1.0-DRAFT";

    private TagRuleVersion() {
    }
}
