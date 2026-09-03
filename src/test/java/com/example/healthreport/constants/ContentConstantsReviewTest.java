package com.example.healthreport.constants;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.healthreport.dish.Dish;
import com.example.healthreport.dish.DishIngredient;
import com.example.healthreport.safety.AllergenKeywordFallback;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 2026-08-27 内容审核快照的原子发布、拒绝项与专项医务裁决回归。 */
class ContentConstantsReviewTest {

	/** 当前发布候选不允许四个内容常量类残留任何 DRAFT。 */
	@Test
	void releaseSnapshotShouldBeFullyAdjudicated() {
		for (AllergenGroup group : AllergenGroups.ALL.values()) {
			for (AllergenWord word : group.getWordList()) {
				assertThat(word.getReviewStatus()).isNotEqualTo(ReviewStatus.DRAFT);
			}
		}
		for (AllergenExceptions.Rule rule : AllergenExceptions.ALL) {
			assertThat(rule.getReviewStatus()).isNotEqualTo(ReviewStatus.DRAFT);
		}
		for (NutritionRule rule : NutritionContents.ALL.values()) {
			assertThat(rule.getReviewStatus()).isNotEqualTo(ReviewStatus.DRAFT);
		}
		for (DietRequirementRule rule : DietRequirementContents.ALL.values()) {
			assertThat(rule.getReviewStatus()).isNotEqualTo(ReviewStatus.DRAFT);
			assertThat(rule.getPositiveReviewStatus()).isNotEqualTo(ReviewStatus.DRAFT);
		}
	}

	/** 正式枚举与食入性组必须一次性完成 11→13、16→18 的契约升级。 */
	@Test
	void molluskAndSesameShouldBeFormalFoodBorneGroups() {
		assertThat(AllergenGroups.foodBorneGroups()).hasSize(13);
		assertThat(AllergenGroups.ALL).hasSize(18);
		assertThat(AllergenGroups.FOOD_BORNE_KEYS.contains(AllergenKey.MOLLUSK)).isTrue();
		assertThat(AllergenGroups.FOOD_BORNE_KEYS.contains(AllergenKey.SESAME)).isTrue();
		Set<AllergenKey> expectedKeySet = EnumSet.allOf(AllergenKey.class);
		expectedKeySet.remove(AllergenKey.OTHER);
		assertThat(AllergenGroups.ALL.keySet()).containsExactlyElementsOf(expectedKeySet);
	}

	/** Java、两个 Schema 与两个提示词必须原子包含新增枚举，避免只补一条链路。 */
	@Test
	void formalEnumsShouldStayAlignedAcrossSchemasAndPrompts() throws Exception {
		Set<String> foodBorneKeySet = new HashSet<String>();
		for (AllergenGroup group : AllergenGroups.foodBorneGroups()) {
			foodBorneKeySet.add(group.getKey().name());
		}
		Set<String> dishTagKeySet = new HashSet<String>(foodBorneKeySet);
		for (DietRequirementKey key : DietRequirementKey.values()) {
			if (key != DietRequirementKey.OTHER) {
				dishTagKeySet.add(key.name());
			}
		}

		ObjectMapper objectMapper = new ObjectMapper();
		JsonNode dishTagSchema = objectMapper.readTree(Paths.get("schema/dish_tag_output.schema.json").toFile());
		assertThat(enumValueSet(dishTagSchema.at("/properties/enumKey/enum"))).isEqualTo(dishTagKeySet);

		Set<String> allergenKeySet = new HashSet<String>();
		for (AllergenKey key : AllergenKey.values()) {
			allergenKeySet.add(key.name());
		}
		JsonNode dietTagsSchema = objectMapper.readTree(Paths.get("schema/diet_tags.schema.json").toFile());
		// 新契约 enumKey 为开放串，枚举归属由 StructuralValidator 在 Java 侧校验（R21p）。
		assertThat(dietTagsSchema.at("/properties/reject").isMissingNode()).isFalse();

		String extractionPrompt = new String(Files.readAllBytes(Paths.get("prompt/diet-tags.md")),
				StandardCharsets.UTF_8);
		String dishTagPrompt = new String(Files.readAllBytes(Paths.get("prompt/dish_tag.md")), StandardCharsets.UTF_8);
		assertThat(dishTagPrompt).contains("MOLLUSK", "SESAME");
		for (String key : allergenKeySet) {
			assertThat(extractionPrompt).contains(key);
		}
		assertThat(dishTagPrompt).contains("22 个");
	}

	/** 配方不稳定的词只能进入模型提示，不能偷偷变成 Java 硬拒绝。 */
	@Test
	void possibleWordsShouldAlwaysRemainModelOnly() {
		for (AllergenGroup group : AllergenGroups.ALL.values()) {
			for (AllergenWord word : group.getWordList()) {
				if (word.getEvidenceLevel() == EvidenceLevel.POSSIBLE) {
					assertThat(word.getMatchMode()).isEqualTo(MatchMode.MODEL_ONLY);
					assertThat(word.isHardMatchable()).isFalse();
				}
			}
		}
	}

	/** 被拒的错组词必须保留为明确负例，避免以后悄悄恢复虾蟹硬拦截。 */
	@Test
	void rejectedAllergenWordsShouldMatchTheAuditedNegativeFixture() {
		Set<String> rejectedWordSet = new HashSet<String>();
		for (AllergenWord word : AllergenGroups.SHRIMP_CRAB.getWordList()) {
			if (word.getReviewStatus() == ReviewStatus.REJECTED) {
				rejectedWordSet.add(word.getMatchWord());
			}
		}
		assertThat(rejectedWordSet).containsExactlyInAnyOrder("蟹柳", "蟹棒", "海鲜酱");

		AllergenKeywordFallback fallback = new AllergenKeywordFallback();
		Dish crabStickDish = new Dish("company-a", 1L, "蟹棒沙拉",
				Collections.singletonList(new DishIngredient("蔬菜", null)));
		assertThat(fallback.matches(AllergenGroups.SHRIMP_CRAB, crabStickDish)).isFalse();
	}

	/** 无对应硬词的四条牛奶例外明确拒绝，生效例外必须能定位到本组硬词。 */
	@Test
	void rejectedExceptionRowsShouldRemainNegativeFixtures() {
		Set<String> rejectedPhraseSet = new HashSet<String>();
		for (AllergenExceptions.Rule rule : AllergenExceptions.ALL) {
			if (rule.getReviewStatus() == ReviewStatus.REJECTED) {
				rejectedPhraseSet.add(rule.getExceptionPhrase());
			}
			else {
				assertThat(reviewedHardWordSet(rule.getAllergenKey())).contains(rule.getMatchWord());
			}
		}
		assertThat(rejectedPhraseSet).containsExactlyInAnyOrder("奶白菜", "椰奶", "豆奶", "杏仁奶");
	}

	/** WHEAT 只收明确成分词；红烧、酱爆、卤不得成为确定性小麦证据。 */
	@Test
	void wheatShouldRejectExplicitSoySauceButNotCookingMethodWords() {
		Set<String> wheatWordSet = new HashSet<String>();
		for (AllergenWord word : AllergenGroups.WHEAT.getWordList()) {
			wheatWordSet.add(word.getMatchWord());
		}
		assertThat(wheatWordSet).contains("酱油", "豉油");
		assertThat(wheatWordSet).doesNotContain("红烧", "酱爆", "卤");

		AllergenKeywordFallback fallback = new AllergenKeywordFallback();
		assertThat(fallback.matches(AllergenGroups.WHEAT, dish(2L, "酱油炒饭"))).isTrue();
		assertThat(fallback.matches(AllergenGroups.WHEAT, dish(3L, "红烧肉"))).isFalse();
	}

	/** 软体动物硬词与素蚝油菜名例外不得互相覆盖食材明示证据。 */
	@Test
	void molluskHardMatchAndDishNameExceptionShouldKeepIngredientPriority() {
		AllergenKeywordFallback fallback = new AllergenKeywordFallback();
		assertThat(fallback.matches(AllergenGroups.MOLLUSK, dish(4L, "蚝油生菜"))).isTrue();
		assertThat(fallback.matches(AllergenGroups.MOLLUSK, dish(5L, "素蚝油生菜"))).isFalse();
		Dish explicitOyster = new Dish("company-a", 6L, "素蚝油生菜",
				Collections.singletonList(new DishIngredient("牡蛎", null)));
		assertThat(fallback.matches(AllergenGroups.MOLLUSK, explicitOyster)).isTrue();
	}

	/** 第一期只放开可从主料确证的两个维度，其余七个必须保持 REJECT-only。 */
	@Test
	void onlyPurineAndFiberShouldOpenDeterministicRecommendation() {
		Set<DietRequirementKey> openedKeySet = new HashSet<DietRequirementKey>();
		for (Map.Entry<DietRequirementKey, DietRequirementRule> entry : DietRequirementContents.ALL.entrySet()) {
			DietRequirementRule rule = entry.getValue();
			if (rule.positiveRecommendEnabled()) {
				openedKeySet.add(entry.getKey());
				assertThat(rule.getPositiveMatchPolicy()).isEqualTo(PositiveMatchPolicy.MAIN_INGREDIENT_INTERSECTION);
				assertThat(rule.getRecommendableFoodList()).isNotEmpty();
				assertThat(rule.getRecommendTagText()).isNotEmpty();
			}
			else {
				assertThat(rule.getPositiveMatchPolicy()).isEqualTo(PositiveMatchPolicy.NONE);
				assertThat(rule.getRecommendableFoodList()).isEmpty();
				assertThat(rule.getRecommendTagText()).isEmpty();
			}
		}
		assertThat(openedKeySet).containsExactlyInAnyOrder(DietRequirementKey.LOW_PURINE,
				DietRequirementKey.HIGH_FIBER);
	}

	/** 限酒靠「食材表里没有酒」推不出推荐：配料表不含调味料，缺证据不是安全证据。 */
	@Test
	void alcoholAndSeasoningDependentDimensionsShouldNeverCarryPositiveContent() {
		for (DietRequirementKey key : Arrays.asList(DietRequirementKey.LIMIT_ALCOHOL, DietRequirementKey.LIGHT_DIET,
				DietRequirementKey.LOW_FAT, DietRequirementKey.LOW_SODIUM, DietRequirementKey.LOW_ADDED_SUGAR,
				DietRequirementKey.LOW_CHOLESTEROL, DietRequirementKey.LOW_CALORIE)) {
			DietRequirementRule rule = DietRequirementContents.ALL.get(key);
			assertThat(rule.positiveRecommendEnabled()).isFalse();
			assertThat(rule.getPositiveReviewStatus()).isEqualTo(ReviewStatus.REJECTED);
		}
	}

	/** 高纤维的推荐食材必须与营养侧同一份已审核清单一致，白菜只展示不推荐。 */
	@Test
	void fiberRecommendationShouldReuseTheAuditedNutritionFoodListWithoutCabbage() {
		assertThat(DietRequirementContents.HIGH_FIBER.getRecommendableFoodList())
			.isEqualTo(NutritionContents.DIETARY_FIBER.getRecommendableFoodList());
		assertThat(DietRequirementContents.HIGH_FIBER.getRecommendableFoodList()).doesNotContain("白菜");
		assertThat(DietRequirementContents.HIGH_FIBER.getDisplayOnlyFoodList()).contains("白菜");
	}

	/** 同一维度的推荐食材不得同时出现在该维度的拒绝词里，否则规则自相矛盾。 */
	@Test
	void recommendableFoodShouldNeverOverlapAvoidWordsOfTheSameDimension() {
		for (DietRequirementRule rule : DietRequirementContents.ALL.values()) {
			for (String food : rule.getRecommendableFoodList()) {
				assertThat(rule.getAvoidFoodList()).doesNotContain(food);
				assertThat(rule.getAvoidDishPatternList()).doesNotContain(food);
			}
		}
	}

	/** 高风险补钾只展示不推荐，高纤维也不以模糊菜式生成自动拒绝。 */
	@Test
	void highRiskNutritionAndUnsupportedDietInferencesShouldStayNonAutomatic() {
		assertThat(NutritionContents.POTASSIUM.getRecommendableFoodList()).isEmpty();
		assertThat(NutritionContents.POTASSIUM.getReviewStatus()).isEqualTo(ReviewStatus.REVIEWED);
		assertThat(DietRequirementContents.HIGH_FIBER.getAvoidDishPatternList()).isEmpty();
	}

	private Set<String> reviewedHardWordSet(AllergenKey key) {
		Set<String> resultSet = new HashSet<String>();
		for (AllergenWord word : AllergenGroups.ALL.get(key).getWordList()) {
			if (word.isHardMatchable()) {
				resultSet.add(word.getMatchWord());
			}
		}
		return resultSet;
	}

	private Set<String> enumValueSet(JsonNode enumNode) {
		Set<String> resultSet = new HashSet<String>();
		for (JsonNode valueNode : enumNode) {
			resultSet.add(valueNode.asText());
		}
		return resultSet;
	}

	private Dish dish(long dishId, String dishName) {
		return new Dish("company-a", dishId, dishName, Collections.singletonList(new DishIngredient("示例食材", null)));
	}

}
