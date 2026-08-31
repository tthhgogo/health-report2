package com.example.healthreport.infra;

import com.example.healthreport.dish.Dish;
import com.example.healthreport.dish.DishIngredient;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** 凌晨任务查询当日在架菜品与食材的只读占位边界。 */
public interface DishQueryService {

	/** TODO 按企业标识做 Keyset 分页，枚举指定业务日有在架菜品的企业。 */
	default CompanyCursorPage queryPreheatCompanyPage(LocalDate bizDate, String lastCompanyId, int pageSize) {
		throw new UnsupportedOperationException("DishQueryService尚未实现");
	}

	/** TODO 按 {@code company_id + bizDate + dishes_id} 做 Keyset 分页。 */
	default DishCursorPage queryOnShelfDishPage(String companyId, LocalDate bizDate, Long lastDishesId, int pageSize) {
		throw new UnsupportedOperationException("DishQueryService尚未实现");
	}

	/** TODO 一次批量查询当前页全部菜品食材，禁止逐菜查询。 */
	default Map<Long, List<DishIngredient>> queryIngredientListMap(String companyId, List<Long> dishIdList) {
		throw new UnsupportedOperationException("DishQueryService尚未实现");
	}

	/** TODO 按与分页完全相同的条件统计企业当日在架菜品数。 */
	default long countOnShelfDishes(String companyId, LocalDate bizDate) {
		throw new UnsupportedOperationException("DishQueryService尚未实现");
	}

	/** 校验企业页非空、严格递增且返回游标等于最后一个企业标识。 */
	static CompanyCursorPage assertValidCompanyPage(CompanyCursorPage page, String inputLastCompanyId, int pageSize) {
		if (page == null || pageSize < 1 || page.getCompanyIdList().contains(null)) {
			throw new IllegalStateException("DishQueryService 返回的企业分页不合法");
		}
		List<String> companyIdList = page.getCompanyIdList();
		if (companyIdList.size() > pageSize) {
			throw new IllegalStateException("企业分页数量超过页容量");
		}
		if (companyIdList.isEmpty()) {
			if (page.getLastCompanyId() != null) {
				throw new IllegalStateException("空企业页游标必须为null");
			}
			return page;
		}
		String previous = inputLastCompanyId;
		for (String companyId : companyIdList) {
			if (companyId.length() == 0 || previous != null && companyId.compareTo(previous) <= 0) {
				throw new IllegalStateException("企业分页游标没有严格前进");
			}
			previous = companyId;
		}
		if (!companyIdList.get(companyIdList.size() - 1).equals(page.getLastCompanyId())) {
			throw new IllegalStateException("企业分页返回游标不是最后一条company_id");
		}
		return page;
	}

	/** 校验菜品页企业归属、严格递增及最后一条 {@code dishes_id} 游标。 */
	static DishCursorPage assertValidDishPage(DishCursorPage page, String companyId, Long inputLastDishesId,
			int pageSize) {
		if (page == null || companyId == null || pageSize < 1 || page.getDishList().contains(null)) {
			throw new IllegalStateException("DishQueryService 返回的菜品分页不合法");
		}
		List<Dish> dishList = page.getDishList();
		if (dishList.size() > pageSize) {
			throw new IllegalStateException("菜品分页数量超过页容量");
		}
		if (dishList.isEmpty()) {
			if (page.getLastDishesId() != null) {
				throw new IllegalStateException("空菜品页游标必须为null");
			}
			return page;
		}
		long previousDishId = inputLastDishesId == null ? 0L : inputLastDishesId.longValue();
		for (Dish dish : dishList) {
			if (!companyId.equals(dish.getCompanyId()) || dish.getDishId() <= previousDishId) {
				throw new IllegalStateException("菜品分页企业归属错误或游标没有严格前进");
			}
			previousDishId = dish.getDishId();
		}
		if (page.getLastDishesId() == null || page.getLastDishesId().longValue() != previousDishId) {
			throw new IllegalStateException("菜品分页返回游标不是最后一条dishes_id");
		}
		return page;
	}

	/** 校验批量食材查询精确覆盖当前页，缺失菜品用空列表表达。 */
	static Map<Long, List<DishIngredient>> assertValidIngredientMap(Map<Long, List<DishIngredient>> ingredientListMap,
			List<Long> dishIdList) {
		if (ingredientListMap == null || dishIdList == null
				|| !ingredientListMap.keySet().equals(new java.util.LinkedHashSet<Long>(dishIdList))) {
			throw new IllegalStateException("DishQueryService 返回的食材批量结果未精确覆盖当前页");
		}
		for (List<DishIngredient> ingredientList : ingredientListMap.values()) {
			if (ingredientList == null || ingredientList.contains(null)) {
				throw new IllegalStateException("DishQueryService 返回的食材列表不合法");
			}
		}
		return Collections.unmodifiableMap(ingredientListMap);
	}

}
