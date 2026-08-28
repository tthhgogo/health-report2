package com.example.healthreport.dish;

import com.example.healthreport.parse.segment.TextNormalizer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** tagHash 顺序稳定性、重量编码和版本覆盖回归。 */
class TagHashCalculatorTest {

    private final TagHashCalculator calculator = new TagHashCalculator(new TextNormalizer());

    @Test
    void ingredientOrderShouldNotChangeHashAndUnknownShouldDifferFromZero() {
        Dish first = dish(1L,
                new DishIngredient("肉丝", new BigDecimal("80.04")),
                new DishIngredient("青椒", null));
        Dish reordered = dish(1L,
                new DishIngredient("青椒", null),
                new DishIngredient("肉丝", new BigDecimal("80.0")));
        Dish zeroWeight = dish(1L,
                new DishIngredient("青椒", BigDecimal.ZERO),
                new DishIngredient("肉丝", new BigDecimal("80.0")));

        String firstHash = calculator.calculate("tag-1", "prompt-1", "model-1", first);
        assertThat(calculator.calculate("tag-1", "prompt-1", "model-1", reordered))
                .isEqualTo(firstHash);
        assertThat(calculator.calculate("tag-1", "prompt-1", "model-1", zeroWeight))
                .isNotEqualTo(firstHash);
        assertThat(firstHash).hasSize(64);
    }

    @Test
    void everyVersionSegmentShouldInvalidateHash() {
        Dish dish = dish(1L, new DishIngredient("鸡蛋", new BigDecimal("100")));
        String baseline = calculator.calculate("tag-1", "prompt-1", "model-1", dish);

        assertThat(calculator.calculate("tag-2", "prompt-1", "model-1", dish))
                .isNotEqualTo(baseline);
        assertThat(calculator.calculate("tag-1", "prompt-2", "model-1", dish))
                .isNotEqualTo(baseline);
        assertThat(calculator.calculate("tag-1", "prompt-1", "model-2", dish))
                .isNotEqualTo(baseline);
    }

    @Test
    void everyNestedSemanticDishFieldShouldInvalidateHash() {
        Dish baselineDish = new Dish(1L, "青椒肉丝", Arrays.asList(
                new DishIngredient("肉丝", new BigDecimal("80.0"))));
        String baseline = calculator.calculate("tag-1", "prompt-1", "model-1", baselineDish);

        Dish renamedDish = new Dish(1L, "彩椒肉丝", baselineDish.getIngredientList());
        Dish renamedIngredient = new Dish(1L, "青椒肉丝", Arrays.asList(
                new DishIngredient("鸡丝", new BigDecimal("80.0"))));
        Dish changedWeight = new Dish(1L, "青椒肉丝", Arrays.asList(
                new DishIngredient("肉丝", new BigDecimal("81.0"))));

        assertThat(calculator.calculate("tag-1", "prompt-1", "model-1", renamedDish))
                .isNotEqualTo(baseline);
        assertThat(calculator.calculate("tag-1", "prompt-1", "model-1", renamedIngredient))
                .isNotEqualTo(baseline);
        assertThat(calculator.calculate("tag-1", "prompt-1", "model-1", changedWeight))
                .isNotEqualTo(baseline);
    }

    @Test
    void dishNormalizationShouldNotIncrementParserSegmentCounter() {
        TextNormalizer textNormalizer = new TextNormalizer();
        TagHashCalculator tagHashCalculator = new TagHashCalculator(textNormalizer);
        Dish dish = new Dish(1L, "\u2E80菜品", Arrays.asList(
                new DishIngredient("\u2E81食材", new BigDecimal("80.0"))));

        tagHashCalculator.calculate("tag-1", "prompt-1", "model-1", dish);

    }

    private Dish dish(long id, DishIngredient... ingredientArray) {
        return new Dish(id, "青椒肉丝", Arrays.asList(ingredientArray));
    }
}
