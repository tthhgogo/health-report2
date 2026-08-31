package com.example.healthreport.dish;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;

import static org.assertj.core.api.Assertions.assertThat;

/** 主料双规则、无重量边界与营养确定性交集回归。 */
class MainIngredientAndNutritionTest {

	private final MainIngredientResolver resolver = new MainIngredientResolver();

	private final NutritionMatcher matcher = new NutritionMatcher(resolver);

	@Test
	void normalMainIngredientsShouldBeSelectedButTinySecondIngredientShouldNot() {
		Dish pepperPork = dish(1L, "青椒肉丝", ingredient("青椒", "200"), ingredient("瘦猪肉", "80"));
		Dish tomatoEgg = dish(2L, "番茄炒蛋", ingredient("番茄", "250"), ingredient("鸡蛋", "100"));
		Dish greensLiver = dish(3L, "青菜猪肝", ingredient("青菜", "180"), ingredient("猪肝", "5"));

		assertThat(resolver.resolve(pepperPork)).contains("青椒", "瘦猪肉");
		assertThat(resolver.resolve(tomatoEgg)).contains("番茄", "鸡蛋");
		assertThat(resolver.resolve(greensLiver)).containsExactly("青菜");
		assertThat(matcher.match(greensLiver, new LinkedHashSet<String>(Collections.singletonList("猪肝"))).getState())
			.isEqualTo(TagState.NEUTRAL);
	}

	@Test
	void allUnknownWeightsShouldProduceEmptyMainSetAndNeutralNutrition() {
		Dish dish = new Dish("company-a", 4L, "无重量菜",
				Arrays.asList(new DishIngredient("鲜猪肝", null), new DishIngredient("菠菜", null)));

		assertThat(resolver.resolve(dish)).isEmpty();
		assertThat(matcher.match(dish, new LinkedHashSet<String>(Collections.singletonList("猪肝"))).getState())
			.isEqualTo(TagState.NEUTRAL);
	}

	@Test
	void aliasShouldMatchOnlyWhenItIsActuallyMainIngredient() {
		Dish dish = dish(5L, "鲜猪肝菜", ingredient("鲜猪肝", "60"), ingredient("青菜", "40"));
		TagValue value = matcher.match(dish, new LinkedHashSet<String>(Collections.singletonList("猪肝")));

		assertThat(value.getState()).isEqualTo(TagState.RECOMMEND);
		assertThat(value.getMatchedIngredientList()).containsExactly("猪肝");
	}

	private Dish dish(long id, String name, DishIngredient... ingredientArray) {
		return new Dish("company-a", id, name, Arrays.asList(ingredientArray));
	}

	private DishIngredient ingredient(String name, String weight) {
		return new DishIngredient(name, new BigDecimal(weight));
	}

}
