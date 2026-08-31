package com.example.healthreport.dish;

import com.example.healthreport.infra.DishQueryService;
import com.example.healthreport.infra.DishCursorPage;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DishQueryService 返回值契约与 Dish 自身的完整性不变式。 */
class DishQueryContractTest {

	@Test
	void emptyPageIsLegalAndMustHaveNullCursor() {
		DishCursorPage page = DishQueryService
			.assertValidDishPage(new DishCursorPage(Collections.<Dish>emptyList(), null), "company-a", null, 500);
		assertThat(page.getDishList()).isEmpty();
	}

	@Test
	void nullResultMustFailWithAnActionableMessage() {
		assertThatThrownBy(() -> DishQueryService.assertValidDishPage(null, "company-a", null, 500))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void nullElementInResultMustFailAtTheBoundary() {
		// 漏进下游只会在某个遍历它的地方炸出没有上下文的 NPE。
		assertThatThrownBy(() -> DishQueryService
			.assertValidDishPage(new DishCursorPage(Arrays.asList(dish(), null), 1L), "company-a", null, 500))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void dishMustRejectNullIngredientElementNotJustNullList() {
		// 构造器原来只拦住了 null 列表；null 元素会一路漏到每个遍历食材的地方。
		assertThatThrownBy(() -> new Dish("company-a", 1L, "番茄炒蛋", Arrays.asList(new DishIngredient("番茄", null), null)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("null");
	}

	@Test
	void dishWithoutIngredientRowsIsStillLegal() {
		// 食材表可能一行都没有（§8.1.1 调味料本就不入表），这不是错误。
		assertThat(new Dish("company-a", 1L, "白灼虾", Collections.<DishIngredient>emptyList()).getIngredientList())
			.isEmpty();
	}

	private Dish dish() {
		return new Dish("company-a", 1L, "测试菜", Collections.singletonList(new DishIngredient("食材", null)));
	}

}
