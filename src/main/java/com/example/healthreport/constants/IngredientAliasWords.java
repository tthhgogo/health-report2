package com.example.healthreport.constants;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 菜品食材工程别名表。
 * <p>只做保守的标准名映射；未命中保持原名，不进行模糊或语义推断。</p>
 */
public final class IngredientAliasWords {

    /** 别名到内容常量标准名的不可变映射。 */
    public static final Map<String, String> ALL;

    static {
        Map<String, String> aliasMap = new LinkedHashMap<String, String>();
        aliasMap.put("鲜猪肝", "猪肝");
        aliasMap.put("猪瘦肉", "瘦猪肉");
        aliasMap.put("牛瘦肉", "瘦牛肉");
        aliasMap.put("嫩豆腐", "南豆腐");
        aliasMap.put("老豆腐", "北豆腐");
        aliasMap.put("蛋", "鸡蛋");
        ALL = Collections.unmodifiableMap(aliasMap);
    }

    private IngredientAliasWords() {
    }

    /** 返回标准名；没有登记时原样返回。 */
    public static String canonical(String ingredientName) {
        String canonicalName = ALL.get(ingredientName);
        return canonicalName == null ? ingredientName : canonicalName;
    }
}
