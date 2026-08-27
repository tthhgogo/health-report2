package com.example.healthreport.dish;

import com.example.healthreport.infra.DishQueryService;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DishQueryService 返回值契约与 Dish 自身的完整性不变式。 */
class DishQueryContractTest {

    @Test
    void emptyResultIsLegalBecauseNoDishOnShelfIsANormalDay() {
        // 空列表必须放行：当日无在架菜品是正常业务状态，不是错误。
        assertThat(DishQueryService.assertValidResult(Collections.<Dish>emptyList())).isEmpty();
    }

    @Test
    void nullResultMustFailWithAnActionableMessage() {
        assertThatThrownBy(() -> DishQueryService.assertValidResult(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("空列表");
    }

    @Test
    void nullElementInResultMustFailAtTheBoundary() {
        // 漏进下游只会在某个遍历它的地方炸出没有上下文的 NPE。
        assertThatThrownBy(() -> DishQueryService.assertValidResult(
                Arrays.asList(dish(), null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void dishMustRejectNullIngredientElementNotJustNullList() {
        // 构造器原来只拦住了 null 列表；null 元素会一路漏到每个遍历食材的地方。
        assertThatThrownBy(() -> new Dish(1L, "番茄炒蛋",
                Arrays.asList(new DishIngredient("番茄", null), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    void dishWithoutIngredientRowsIsStillLegal() {
        // 食材表可能一行都没有（§8.1.1 调味料本就不入表），这不是错误。
        assertThat(new Dish(1L, "白灼虾", Collections.<DishIngredient>emptyList())
                .getIngredientList()).isEmpty();
    }

    private Dish dish() {
        return new Dish(1L, "测试菜",
                Collections.singletonList(new DishIngredient("食材", null)));
    }
}
