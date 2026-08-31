package com.example.healthreport.cache;

import com.example.healthreport.constants.AllergenGroup;
import com.example.healthreport.constants.AllergenGroups;
import com.example.healthreport.constants.DietRequirementKey;
import com.example.healthreport.constants.NutritionKey;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 凌晨企业快照固定发布的 33 个标签方向清单。 */
@Component
public class DishTagSetCatalog {

	/** 固定方向数量：13 过敏拒绝 + 2 饮食推荐 + 9 饮食拒绝 + 9 营养推荐。 */
	private static final int EXPECTED_DIRECTION_COUNT = 33;

	private final List<DishTagSetRef> publishRefList;

	public DishTagSetCatalog() {
		List<DishTagSetRef> resultList = new ArrayList<DishTagSetRef>(EXPECTED_DIRECTION_COUNT);
		for (AllergenGroup group : AllergenGroups.foodBorneGroups()) {
			resultList.add(new DishTagSetRef(DishTagSetRef.Category.ALLERGEN, DishTagSetRef.Direction.REJECT,
					group.getKey().name()));
		}
		resultList.add(new DishTagSetRef(DishTagSetRef.Category.DIET, DishTagSetRef.Direction.RECOMMEND,
				DietRequirementKey.LOW_PURINE.name()));
		resultList.add(new DishTagSetRef(DishTagSetRef.Category.DIET, DishTagSetRef.Direction.RECOMMEND,
				DietRequirementKey.HIGH_FIBER.name()));
		for (DietRequirementKey key : DietRequirementKey.values()) {
			if (key != DietRequirementKey.OTHER) {
				resultList
					.add(new DishTagSetRef(DishTagSetRef.Category.DIET, DishTagSetRef.Direction.REJECT, key.name()));
			}
		}
		for (NutritionKey key : NutritionKey.values()) {
			if (key != NutritionKey.OTHER) {
				resultList.add(new DishTagSetRef(DishTagSetRef.Category.NUTRITION, DishTagSetRef.Direction.RECOMMEND,
						key.name()));
			}
		}
		if (resultList.size() != EXPECTED_DIRECTION_COUNT) {
			throw new IllegalStateException("菜品标签发布方向数量不是33");
		}
		publishRefList = Collections.unmodifiableList(resultList);
	}

	/** 返回不可变的固定 33 方向清单。 */
	public List<DishTagSetRef> publishRefs() {
		return publishRefList;
	}

	/** 查找固定清单中的方向引用，避免构建阶段产生未登记 Key。 */
	public DishTagSetRef ref(DishTagSetRef.Category category, DishTagSetRef.Direction direction, String enumKey) {
		for (DishTagSetRef setRef : publishRefList) {
			if (setRef.getCategory() == category && setRef.getDirection() == direction
					&& setRef.getEnumKey().equals(enumKey)) {
				return setRef;
			}
		}
		throw new IllegalArgumentException("标签方向未登记在33集合清单中");
	}

}
