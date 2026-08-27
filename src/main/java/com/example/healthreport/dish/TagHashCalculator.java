package com.example.healthreport.dish;

import com.example.healthreport.parse.segment.TextNormalizer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 计算菜品打标输入的稳定哈希，用于判断某道菜是否需要重新打标。
 * 食材先按名称字典序排序再入哈希——不排序的话，外部接口返回顺序一变
 * 就会触发全量重打，白白消耗模型调用。
 */
@Component
public class TagHashCalculator {

    /** SHA-256 输出的十六进制字符数。 */
    private static final int SHA256_HEX_LENGTH = 64;

    private final TextNormalizer textNormalizer;

    public TagHashCalculator(TextNormalizer textNormalizer) {
        if (textNormalizer == null) {
            throw new IllegalArgumentException("文本规范化器不能为空");
        }
        this.textNormalizer = textNormalizer;
    }

    /**
     * 计算版本号驱动的标签哈希；食材顺序不会影响结果，未知重量编码为 {@code null}。
     */
    public String calculate(String tagRuleVersion, String promptVersion, String modelVersion,
                            Dish dish) {
        if (tagRuleVersion == null || promptVersion == null || modelVersion == null
                || dish == null) {
            throw new IllegalArgumentException("哈希输入不能为空");
        }
        List<NormalizedIngredient> normalizedIngredientList = new ArrayList<NormalizedIngredient>(
                dish.getIngredientList().size());
        for (DishIngredient ingredient : dish.getIngredientList()) {
            normalizedIngredientList.add(new NormalizedIngredient(
                    normalize(ingredient.getName()), weightText(ingredient.getWeightG())));
        }
        Collections.sort(normalizedIngredientList, new Comparator<NormalizedIngredient>() {
            @Override
            public int compare(NormalizedIngredient left, NormalizedIngredient right) {
                int nameResult = left.name.compareTo(right.name);
                if (nameResult != 0) {
                    return nameResult;
                }
                return left.weight.compareTo(right.weight);
            }
        });

        StringBuilder inputBuilder = new StringBuilder();
        inputBuilder.append(tagRuleVersion).append('|').append(promptVersion).append('|')
                .append(modelVersion).append('|').append(normalize(dish.getDishName())).append('|');
        for (int index = 0; index < normalizedIngredientList.size(); index++) {
            if (index > 0) {
                inputBuilder.append(',');
            }
            NormalizedIngredient ingredient = normalizedIngredientList.get(index);
            inputBuilder.append(ingredient.name).append(':').append(ingredient.weight);
        }
        return sha256(inputBuilder.toString());
    }

    private String normalize(String value) {
        return textNormalizer.normalize(value).getNormalizedText();
    }

    private String weightText(BigDecimal weightG) {
        if (weightG == null) {
            return "null";
        }
        return weightG.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexBuilder = new StringBuilder(SHA256_HEX_LENGTH);
            for (byte current : bytes) {
                hexBuilder.append(String.format("%02x", current & 0xff));
            }
            return hexBuilder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少SHA-256", exception);
        }
    }

    /** 排序和串行化使用的规范化食材。 */
    private static final class NormalizedIngredient {
        private final String name;
        private final String weight;

        private NormalizedIngredient(String name, String weight) {
            this.name = name;
            this.weight = weight;
        }
    }
}
