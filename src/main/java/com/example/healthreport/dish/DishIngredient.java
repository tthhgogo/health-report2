package com.example.healthreport.dish;

import lombok.Getter;

import java.math.BigDecimal;

/** 菜品食材快照；重量统一为克，未知时为 {@code null}。 */
@Getter
public final class DishIngredient {

    private final String name;
    private final BigDecimal weightG;

    /** 创建一个只读食材项。 */
    public DishIngredient(String name, BigDecimal weightG) {
        if (name == null) {
            throw new IllegalArgumentException("食材名不能为空");
        }
        this.name = name;
        this.weightG = weightG;
    }
}
