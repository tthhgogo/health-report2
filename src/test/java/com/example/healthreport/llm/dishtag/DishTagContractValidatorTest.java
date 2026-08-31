package com.example.healthreport.llm.dishtag;

import com.example.healthreport.dish.Dish;
import com.example.healthreport.dish.DishIngredient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** R22：三列表精确覆盖、互斥与整批作废。 */
class DishTagContractValidatorTest {

	private final DishTagContractValidator validator = new DishTagContractValidator(new ObjectMapper());

	private final java.util.List<Dish> inputDishList = Arrays.asList(dish(1L), dish(2L), dish(3L));

	@Test
	void exactThreeWayCoverageShouldPass() {
		String json = "{\"enumKey\":\"LOW_FAT\",\"neutralDishIds\":[1],"
				+ "\"unknownDishIds\":[2],\"hitList\":[{\"dishId\":3,"
				+ "\"verdict\":\"REJECT\",\"evidenceType\":\"COOKING\","
				+ "\"matchedIngredients\":[],\"reason\":\"明确做法\"}]}";

		assertThat(validator.validate(json, "LOW_FAT", inputDishList).getHitList()).hasSize(1);
	}

	@Test
	void missingExtraOverlapAndDuplicateShouldRejectWholeBatch() {
		assertRejected("[1]", "[2]", "[]");
		assertRejected("[1,4]", "[2]", hit(3));
		assertRejected("[1]", "[1,2]", hit(3));
		assertRejected("[1,1]", "[2]", hit(3));
	}

	/**
	 * 批次输入自带重复菜品 ID 是编排层的 bug，不是模型的问题。 两者结果都是整批作废，但错误类型必须分开——否则排障时会去查模型和提示词， 而真正的缺陷在调用方。
	 */
	@Test
	void duplicateInputDishIdShouldSurfaceAsCallerBugNotModelRejection() {
		List<Dish> duplicatedInputList = Arrays.asList(dish(1L), dish(1L));
		String json = "{\"enumKey\":\"LOW_FAT\",\"neutralDishIds\":[1]," + "\"unknownDishIds\":[],\"hitList\":[]}";

		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> validator.validate(json, "LOW_FAT", duplicatedInputList));
		assertThat(exception).isNotInstanceOf(DishTagBatchRejectedException.class);
	}

	private void assertRejected(String neutralIds, String unknownIds, String hitList) {
		String json = "{\"enumKey\":\"LOW_FAT\",\"neutralDishIds\":" + neutralIds + ",\"unknownDishIds\":" + unknownIds
				+ ",\"hitList\":" + hitList + "}";
		assertThrows(DishTagBatchRejectedException.class, () -> validator.validate(json, "LOW_FAT", inputDishList));
	}

	private String hit(long dishId) {
		return "[{\"dishId\":" + dishId + ",\"verdict\":\"REJECT\","
				+ "\"evidenceType\":\"COOKING\",\"matchedIngredients\":[]," + "\"reason\":\"明确做法\"}]";
	}

	private Dish dish(long dishId) {
		return new Dish("company-a", dishId, "测试菜" + dishId, Collections.singletonList(new DishIngredient("食材", null)));
	}

}
