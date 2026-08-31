package com.example.healthreport.cache;

import lombok.Getter;

/** 一个固定标签维度与方向的 Redis SET 引用。 */
@Getter
public final class DishTagSetRef {

	/** Redis Key 中的标签大类。 */
	@Getter
	public enum Category {

		/** 食入性过敏原。 */
		ALLERGEN("allergen"),

		/** 饮食注意。 */
		DIET("diet"),

		/** 营养补充。 */
		NUTRITION("nutrition");

		private final String keySegment;

		Category(String keySegment) {
			this.keySegment = keySegment;
		}

	}

	/** Redis Key 中的执行方向。 */
	@Getter
	public enum Direction {

		/** 推荐方向。 */
		RECOMMEND("recommend"),

		/** 不推荐方向。 */
		REJECT("reject");

		private final String keySegment;

		Direction(String keySegment) {
			this.keySegment = keySegment;
		}

	}

	private final Category category;

	private final Direction direction;

	private final String enumKey;

	/** 创建正式枚举对应的方向集合引用。 */
	public DishTagSetRef(Category category, Direction direction, String enumKey) {
		if (category == null || direction == null || enumKey == null || enumKey.length() == 0) {
			throw new IllegalArgumentException("标签集合引用字段不能为空");
		}
		this.category = category;
		this.direction = direction;
		this.enumKey = enumKey;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof DishTagSetRef)) {
			return false;
		}
		DishTagSetRef that = (DishTagSetRef) other;
		return category == that.category && direction == that.direction && enumKey.equals(that.enumKey);
	}

	@Override
	public int hashCode() {
		int result = category.hashCode();
		result = 31 * result + direction.hashCode();
		return 31 * result + enumKey.hashCode();
	}

}
