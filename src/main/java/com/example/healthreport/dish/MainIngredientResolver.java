package com.example.healthreport.dish;

import com.example.healthreport.constants.IngredientAliasWords;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 按重量双规则确定性推导主料，不做调味料分类。 */
@Service
public class MainIngredientResolver {

    /** 占总重量至少 25% 的食材视为主料。【待校准】该阈值尚未用真实菜品数据验证过。 */
    private static final BigDecimal MAIN_RATIO = new BigDecimal("0.25");

    /** 重量前两名还需占比至少 15% 才算主料。【待校准】该阈值尚未用真实菜品数据验证过。 */
    private static final BigDecimal TOP_N_MIN_RATIO = new BigDecimal("0.15");

    /** 规则二只看重量前两名：再往后的配料占比过低，计入主料会让推荐失真。 */
    private static final int TOP_N = 2;

    /** 推导标准化主料名；全部重量未知或非正数时返回空集。 */
    public Set<String> resolve(Dish dish) {
        if (dish == null) {
            throw new IllegalArgumentException("菜品不能为空");
        }
        List<DishIngredient> weightedIngredientList = new ArrayList<DishIngredient>();
        BigDecimal totalWeight = BigDecimal.ZERO;
        for (DishIngredient ingredient : dish.getIngredientList()) {
            if (ingredient.getWeightG() != null && ingredient.getWeightG().signum() > 0) {
                weightedIngredientList.add(ingredient);
                totalWeight = totalWeight.add(ingredient.getWeightG());
            }
        }
        if (weightedIngredientList.isEmpty()) {
            return Collections.emptySet();
        }
        Collections.sort(weightedIngredientList, new Comparator<DishIngredient>() {
            @Override
            public int compare(DishIngredient left, DishIngredient right) {
                int weightResult = right.getWeightG().compareTo(left.getWeightG());
                if (weightResult != 0) {
                    return weightResult;
                }
                return left.getName().compareTo(right.getName());
            }
        });

        Set<String> mainIngredientSet = new LinkedHashSet<String>();
        for (int index = 0; index < weightedIngredientList.size(); index++) {
            DishIngredient ingredient = weightedIngredientList.get(index);
            BigDecimal ratio = ingredient.getWeightG().divide(totalWeight, 8,
                    RoundingMode.HALF_UP);
            if (ratio.compareTo(MAIN_RATIO) >= 0
                    || (index < TOP_N && ratio.compareTo(TOP_N_MIN_RATIO) >= 0)) {
                mainIngredientSet.add(IngredientAliasWords.canonical(ingredient.getName()));
            }
        }
        if (mainIngredientSet.isEmpty()) {
            mainIngredientSet.add(IngredientAliasWords.canonical(
                    weightedIngredientList.get(0).getName()));
        }
        return Collections.unmodifiableSet(mainIngredientSet);
    }
}
