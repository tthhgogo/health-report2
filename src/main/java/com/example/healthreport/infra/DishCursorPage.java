package com.example.healthreport.infra;

import com.example.healthreport.dish.Dish;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 指定企业当日在架菜品的 {@code dishes_id} Keyset 分页结果。 */
@Getter
public final class DishCursorPage {

	private final List<Dish> dishList;

	private final Long lastDishesId;

	/** 创建菜品游标页；企业、顺序与游标前进由查询边界统一校验。 */
	public DishCursorPage(List<Dish> dishList, Long lastDishesId) {
		if (dishList == null) {
			throw new IllegalArgumentException("菜品分页列表不能为空");
		}
		this.dishList = Collections.unmodifiableList(new ArrayList<Dish>(dishList));
		this.lastDishesId = lastDishesId;
	}

}
