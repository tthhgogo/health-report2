package com.example.healthreport.dish;

import com.example.healthreport.constants.DietRequirementKey;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 饮食注意确定性正向匹配的安全门槛、政策门槛与主料口径回归。 */
class DietPositiveMatcherTest {

	private final DietPositiveMatcher matcher = new DietPositiveMatcher(
			new NutritionMatcher(new MainIngredientResolver()));

	@Test
	void neutralSafetyStateWithWhitelistedMainIngredientShouldRecommend() {
		TagValue value = matcher.match(dish("冬瓜炖蛋", "冬瓜", "200", "鸡蛋", "100"), DietRequirementKey.LOW_PURINE,
				TagState.NEUTRAL);

		assertThat(value.getState()).isEqualTo(TagState.RECOMMEND);
		assertThat(value.getMatchedIngredientList()).containsExactly("冬瓜", "鸡蛋");
	}

	@Test
	void everyNonNeutralSafetyStateShouldRefuseToRecommend() {
		Dish dish = dish("冬瓜炖蛋", "冬瓜", "200", "鸡蛋", "100");
		for (TagState state : Arrays.asList(TagState.REJECT, TagState.UNKNOWN,
				TagState.RECOMMEND)) {
			assertThat(matcher.match(dish, DietRequirementKey.LOW_PURINE, state).getState())
				.isEqualTo(TagState.NEUTRAL);
		}
	}

	@Test
	void dimensionWithoutPositivePolicyShouldStayNeutralEvenOnFullMatch() {
		// 低脂维度的展示食材里就有北豆腐，命中了也不能推荐：用油量看不见。
		TagValue value = matcher.match(dish("豆腐煲", "北豆腐", "300"), DietRequirementKey.LOW_FAT, TagState.NEUTRAL);

		assertThat(value.getState()).isEqualTo(TagState.NEUTRAL);
		assertThat(value.getMatchedIngredientList()).isEmpty();
	}

	@Test
	void displayOnlyFoodAndNonMainIngredientShouldNotTriggerRecommendation() {
		// 白菜只在高纤维的展示清单里，不在推荐清单里；配菜份量的燕麦也算不上主料。
		assertThat(matcher.match(dish("清炒白菜", "白菜", "300"), DietRequirementKey.HIGH_FIBER, TagState.NEUTRAL).getState())
			.isEqualTo(TagState.NEUTRAL);
		assertThat(
				matcher.match(dish("白米饭配燕麦", "白米", "300", "燕麦", "10"), DietRequirementKey.HIGH_FIBER, TagState.NEUTRAL)
					.getState())
			.isEqualTo(TagState.NEUTRAL);
	}

	@Test
	void nullArgumentShouldFailFastInsteadOfSilentlyNeutral() {
		assertThatThrownBy(() -> matcher.match(null, DietRequirementKey.LOW_PURINE, TagState.NEUTRAL))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> matcher.match(dish("冬瓜汤", "冬瓜", "200"), null, TagState.NEUTRAL))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> matcher.match(dish("冬瓜汤", "冬瓜", "200"), DietRequirementKey.LOW_PURINE, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private Dish dish(String dishName, String... nameAndWeightArray) {
		List<DishIngredient> ingredientList = new ArrayList<DishIngredient>(nameAndWeightArray.length / 2);
		for (int index = 0; index < nameAndWeightArray.length; index += 2) {
			ingredientList
				.add(new DishIngredient(nameAndWeightArray[index], new BigDecimal(nameAndWeightArray[index + 1])));
		}
		return new Dish("company-a", 1L, dishName, Collections.unmodifiableList(ingredientList));
	}

}
