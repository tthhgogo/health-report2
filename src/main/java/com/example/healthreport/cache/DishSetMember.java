package com.example.healthreport.cache;

import lombok.Getter;

/** Redis 菜品集合成员解码结果，只承载菜品身份与名称。 */
@Getter
public final class DishSetMember {

	private final long dishId;

	private final String dishName;

	/** 创建已通过复合成员格式校验的菜品标识。 */
	public DishSetMember(long dishId, String dishName) {
		if (dishId <= 0L || dishName == null || dishName.length() == 0) {
			throw new IllegalArgumentException("菜品集合成员字段不合法");
		}
		this.dishId = dishId;
		this.dishName = dishName;
	}

}
