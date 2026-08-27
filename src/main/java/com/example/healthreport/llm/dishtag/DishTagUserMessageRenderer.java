package com.example.healthreport.llm.dishtag;

import com.example.healthreport.dish.Dish;
import com.example.healthreport.dish.DishIngredient;

import java.math.BigDecimal;
import java.util.List;

/**
 * 把一批菜品渲染成提示词「User（每批填充）」小节约定的文本。
 *
 * <p>这是可穷举输入的确定性字符串拼接，按 {@code AGENTS.md} §3 属于 Java 的职责，
 * 不交给模型、也不放进任何外部编排平台的模板节点。</p>
 *
 * <p>渲染结果<b>不含调味料</b>——食材表里本来就一行都没有（设计方案 §8.1.1）。
 * 提示词正文已经把这一点讲明，这里不再重复注入说明文字，避免同一句话出现两处口径。</p>
 */
public final class DishTagUserMessageRenderer {

    private DishTagUserMessageRenderer() {
    }

    /** 渲染一批菜品；输出顺序严格照 {@code dishList} 的顺序，不重排。 */
    public static String render(DishTagInput input) {
        if (input == null) {
            throw new IllegalArgumentException("LLM-B 批次输入不能为空");
        }
        StringBuilder rendered = new StringBuilder(1024);
        rendered.append("【本批维度】\n");
        rendered.append("enumKey: ").append(input.getEnumKey()).append('\n');
        rendered.append("展示名: ").append(input.getEnumDisplayName()).append('\n');
        appendListLine(rendered, "需避免的食材", input.getAvoidFoodList());
        appendListLine(rendered, "易忽略的含该成分食物", input.getHiddenFoodList());
        appendListLine(rendered, "避免的菜式", input.getAvoidDishPatternList());
        appendListLine(rendered, "烹饪方式建议", input.getCookingTipList());

        List<Dish> dishList = input.getDishList();
        rendered.append("\n【本批菜品】共 ").append(dishList.size()).append(" 道\n");
        for (Dish dish : dishList) {
            rendered.append("- dishId=").append(dish.getDishId())
                    .append("  ").append(dish.getDishName()).append('\n');
            rendered.append("    ").append(renderIngredients(dish.getIngredientList())).append('\n');
        }
        return rendered.toString();
    }

    /** 空列表整行不输出：输出一个空标签会让模型以为「该维度确实没有需避免的食材」。 */
    private static void appendListLine(StringBuilder rendered, String label, List<String> valueList) {
        if (valueList == null || valueList.isEmpty()) {
            return;
        }
        rendered.append(label).append(": ").append(String.join("、", valueList)).append('\n');
    }

    /** 没有食材行时明确写出来，而不是留一行空白让模型自己猜。 */
    private static String renderIngredients(List<DishIngredient> ingredientList) {
        if (ingredientList == null || ingredientList.isEmpty()) {
            return "（无食材记录）";
        }
        StringBuilder rendered = new StringBuilder(64);
        for (DishIngredient ingredient : ingredientList) {
            if (rendered.length() > 0) {
                rendered.append(" / ");
            }
            rendered.append(ingredient.getName()).append(' ').append(weight(ingredient.getWeightG()));
        }
        return rendered.toString();
    }

    /** 去掉 BigDecimal 的尾随零，让同一个重量在不同批次里渲染成同一个字符串。 */
    private static String weight(BigDecimal weightG) {
        if (weightG == null) {
            return "重量未知";
        }
        return weightG.stripTrailingZeros().toPlainString() + "g";
    }
}
