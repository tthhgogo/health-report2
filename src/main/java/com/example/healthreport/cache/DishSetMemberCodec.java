package com.example.healthreport.cache;

import org.springframework.stereotype.Component;

/** {@code dishesId + 制表符 + dishName} 的唯一编解码点。 */
@Component
public class DishSetMemberCodec {

	/** Redis 复合成员字段分隔符；菜名禁止包含该字符。 */
	private static final char FIELD_SEPARATOR = '\t';

	/** 编码公开菜品身份；拒绝会破坏单行成员格式的名称。 */
	public String encode(long dishId, String dishName) {
		assertDishName(dishName);
		if (dishId <= 0L) {
			throw new IllegalArgumentException("菜品ID必须为正数");
		}
		return dishId + String.valueOf(FIELD_SEPARATOR) + dishName;
	}

	/** 解码并校验 Redis 复合成员；损坏成员不得进入展示层。 */
	public DishSetMember decode(String member) {
		if (member == null) {
			throw new IllegalArgumentException("菜品集合成员不能为空");
		}
		int separatorIndex = member.indexOf(FIELD_SEPARATOR);
		if (separatorIndex <= 0 || separatorIndex != member.lastIndexOf(FIELD_SEPARATOR)
				|| separatorIndex == member.length() - 1) {
			throw new IllegalArgumentException("菜品集合成员格式不合法");
		}
		long dishId;
		try {
			dishId = Long.parseLong(member.substring(0, separatorIndex));
		}
		catch (NumberFormatException exception) {
			throw new IllegalArgumentException("菜品集合成员ID不合法", exception);
		}
		String dishName = member.substring(separatorIndex + 1);
		assertDishName(dishName);
		return new DishSetMember(dishId, dishName);
	}

	private void assertDishName(String dishName) {
		if (dishName == null || dishName.length() == 0 || dishName.indexOf(FIELD_SEPARATOR) >= 0
				|| dishName.indexOf('\r') >= 0 || dishName.indexOf('\n') >= 0) {
			throw new IllegalArgumentException("菜品名称不能包含制表符或换行符");
		}
	}

}
