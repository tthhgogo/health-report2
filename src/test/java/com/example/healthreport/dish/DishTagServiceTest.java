package com.example.healthreport.dish;

import com.example.healthreport.cache.DishRecommendSetCache;
import com.example.healthreport.cache.DishSetMemberCodec;
import com.example.healthreport.cache.DishTagSetCatalog;
import com.example.healthreport.constants.AllergenGroup;
import com.example.healthreport.constants.AllergenGroups;
import com.example.healthreport.constants.DietRequirementContents;
import com.example.healthreport.infra.CompanyCursorPage;
import com.example.healthreport.infra.DishCursorPage;
import com.example.healthreport.infra.DishQueryService;
import com.example.healthreport.llm.dishtag.DishTagClient;
import com.example.healthreport.llm.dishtag.DishTagInput;
import com.example.healthreport.llm.dishtag.DishTagProperties;
import com.example.healthreport.persistence.CtDishTagEntity;
import com.example.healthreport.persistence.CtDishTagService;
import com.example.healthreport.safety.AllergenKeywordFallback;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 离线企业与 {@code dishes_id} 游标分页、复用和 33 SET 发布回归。 */
class DishTagServiceTest {

	@Test
	void shouldPassLastDishesIdToNextPageAndPublishThirtyThreeSetsOnce() {
		LocalDate bizDate = LocalDate.of(2026, 8, 28);
		Dish first = dish(1L, "白菜");
		Dish second = dish(2L, "冬瓜");
		DishQueryService queryService = mock(DishQueryService.class);
		when(queryService.queryPreheatCompanyPage(bizDate, null, 1))
			.thenReturn(new CompanyCursorPage(Collections.singletonList("company-a"), "company-a"));
		when(queryService.queryPreheatCompanyPage(bizDate, "company-a", 1))
			.thenReturn(new CompanyCursorPage(Collections.<String>emptyList(), null));
		when(queryService.queryOnShelfDishPage("company-a", bizDate, null, 1))
			.thenReturn(new DishCursorPage(Collections.singletonList(first), 1L));
		when(queryService.queryOnShelfDishPage("company-a", bizDate, 1L, 1))
			.thenReturn(new DishCursorPage(Collections.singletonList(second), 2L));
		when(queryService.queryOnShelfDishPage("company-a", bizDate, 2L, 1))
			.thenReturn(new DishCursorPage(Collections.<Dish>emptyList(), null));
		when(queryService.countOnShelfDishes("company-a", bizDate)).thenReturn(2L);
		when(queryService.queryIngredientListMap(eq("company-a"), anyList()))
			.thenAnswer(invocation -> ingredientMap(invocation.getArgument(1)));

		TagHashCalculator hashCalculator = mock(TagHashCalculator.class);
		when(hashCalculator.calculate(anyString(), anyString(), anyString(), any(Dish.class)))
			.thenAnswer(invocation -> "hash-" + ((Dish) invocation.getArgument(3)).getDishId());
		CtDishTagService persistence = mock(CtDishTagService.class);
		when(persistence.findCandidates(eq("company-a"), anySet(), anySet(), anySet()))
			.thenAnswer(invocation -> existing((java.util.Set<Long>) invocation.getArgument(1)));
		DishTagWriteService writeService = mock(DishTagWriteService.class);
		DishTagClient client = mock(DishTagClient.class);
		DishTagProperties properties = new DishTagProperties();
		properties.setModelVersionDishtag("model-b");
		NutritionMatcher nutritionMatcher = mock(NutritionMatcher.class);
		when(nutritionMatcher.match(any(Dish.class), any(com.example.healthreport.constants.NutritionKey.class)))
			.thenReturn(TagValue.of(TagState.NEUTRAL));
		DietPositiveMatcher dietMatcher = mock(DietPositiveMatcher.class);
		when(dietMatcher.match(any(Dish.class), any(com.example.healthreport.constants.DietRequirementKey.class),
				any(TagState.class)))
			.thenReturn(TagValue.of(TagState.NEUTRAL));
		AllergenKeywordFallback fallback = mock(AllergenKeywordFallback.class);
		DishRecommendSetCache setCache = mock(DishRecommendSetCache.class);
		DishTagSetCatalog catalog = new DishTagSetCatalog();
		DishTagService service = new DishTagService(queryService, hashCalculator, persistence, writeService, client,
				properties, nutritionMatcher, dietMatcher, fallback, setCache, catalog, new DishSetMemberCodec(), 1, 500);

		service.run(bizDate);

		verify(queryService).queryOnShelfDishPage("company-a", bizDate, null, 1);
		verify(queryService).queryOnShelfDishPage("company-a", bizDate, 1L, 1);
		verify(queryService).queryOnShelfDishPage("company-a", bizDate, 2L, 1);
		verify(queryService, times(2)).queryIngredientListMap(eq("company-a"), anyList());
		verify(client, never()).tag(any(DishTagInput.class));
		verify(setCache).publish(eq("company-a"), eq(bizDate), anyString(), eq(catalog.publishRefs()));
		assertThat(catalog.publishRefs()).hasSize(33);
	}

	/**
	 * 在架菜品超过单企业上限时：只打标前 N 道，照常发布，不因为「处理数 != 在架数」整企业作废。
	 *
	 * <p>被闸掉的菜品当天既不进推荐集合、也不进过敏拒绝集合——对模块四直接不存在。</p>
	 */
	@Test
	@SuppressWarnings("unchecked")
	void shouldStopAtPerCompanyCapAndStillPublish() {
		LocalDate bizDate = LocalDate.of(2026, 8, 28);
		DishQueryService queryService = mock(DishQueryService.class);
		when(queryService.queryPreheatCompanyPage(bizDate, null, 1))
			.thenReturn(new CompanyCursorPage(Collections.singletonList("company-a"), "company-a"));
		when(queryService.queryPreheatCompanyPage(bizDate, "company-a", 1))
			.thenReturn(new CompanyCursorPage(Collections.<String>emptyList(), null));
		// 在架 5 道，上限 2 道：只应查前两页，第三页永远不该被请求。
		when(queryService.countOnShelfDishes("company-a", bizDate)).thenReturn(5L);
		when(queryService.queryOnShelfDishPage("company-a", bizDate, null, 1))
			.thenReturn(new DishCursorPage(Collections.singletonList(dish(1L, "白菜")), 1L));
		when(queryService.queryOnShelfDishPage("company-a", bizDate, 1L, 1))
			.thenReturn(new DishCursorPage(Collections.singletonList(dish(2L, "冬瓜")), 2L));
		when(queryService.queryIngredientListMap(eq("company-a"), anyList()))
			.thenAnswer(invocation -> ingredientMap(invocation.getArgument(1)));

		TagHashCalculator hashCalculator = mock(TagHashCalculator.class);
		when(hashCalculator.calculate(anyString(), anyString(), anyString(), any(Dish.class)))
			.thenAnswer(invocation -> "hash-" + ((Dish) invocation.getArgument(3)).getDishId());
		CtDishTagService persistence = mock(CtDishTagService.class);
		when(persistence.findCandidates(eq("company-a"), anySet(), anySet(), anySet()))
			.thenAnswer(invocation -> existing((java.util.Set<Long>) invocation.getArgument(1)));
		DishTagProperties properties = new DishTagProperties();
		properties.setModelVersionDishtag("model-b");
		NutritionMatcher nutritionMatcher = mock(NutritionMatcher.class);
		when(nutritionMatcher.match(any(Dish.class), any(com.example.healthreport.constants.NutritionKey.class)))
			.thenReturn(TagValue.of(TagState.NEUTRAL));
		DietPositiveMatcher dietMatcher = mock(DietPositiveMatcher.class);
		when(dietMatcher.match(any(Dish.class), any(com.example.healthreport.constants.DietRequirementKey.class),
				any(TagState.class)))
			.thenReturn(TagValue.of(TagState.NEUTRAL));
		DishRecommendSetCache setCache = mock(DishRecommendSetCache.class);
		DishTagSetCatalog catalog = new DishTagSetCatalog();
		DishTagService service = new DishTagService(queryService, hashCalculator, persistence,
				mock(DishTagWriteService.class), mock(DishTagClient.class), properties, nutritionMatcher,
				dietMatcher, mock(AllergenKeywordFallback.class), setCache, catalog,
				new DishSetMemberCodec(), 1, 2);

		service.run(bizDate);

		verify(queryService).queryOnShelfDishPage("company-a", bizDate, null, 1);
		verify(queryService).queryOnShelfDishPage("company-a", bizDate, 1L, 1);
		// 到达上限即停：第三页不查，也不因为 2 != 5 抛异常。
		verify(queryService, never()).queryOnShelfDishPage("company-a", bizDate, 2L, 1);
		verify(setCache).publish(eq("company-a"), eq(bizDate), anyString(), eq(catalog.publishRefs()));
		verify(setCache, never()).discard(anyString(), any(LocalDate.class), anyString(), anyList());
	}

	/**
	 * 计数说 1 道、分页却返回 2 道：必须被完整性校验发现，不得被产能闸掩盖。
	 *
	 * <p>产能闸若由「处理数达到上限」反推，这里会被当成触发闸、期望值随之变成 2，
	 * 恰好等于处理数而放行——那正是这条校验本该发现的 count/page 自相矛盾。</p>
	 */
	@Test
	@SuppressWarnings("unchecked")
	void countAndPageDisagreementMustNotBeMaskedByTheCap() {
		LocalDate bizDate = LocalDate.of(2026, 8, 28);
		DishQueryService queryService = mock(DishQueryService.class);
		when(queryService.queryPreheatCompanyPage(bizDate, null, 1))
			.thenReturn(new CompanyCursorPage(Collections.singletonList("company-a"), "company-a"));
		when(queryService.queryPreheatCompanyPage(bizDate, "company-a", 1))
			.thenReturn(new CompanyCursorPage(Collections.<String>emptyList(), null));
		// 计数接口说只有 1 道，分页接口却给出 2 道，上限设为 2。
		when(queryService.countOnShelfDishes("company-a", bizDate)).thenReturn(1L);
		when(queryService.queryOnShelfDishPage("company-a", bizDate, null, 1))
			.thenReturn(new DishCursorPage(Collections.singletonList(dish(1L, "白菜")), 1L));
		when(queryService.queryOnShelfDishPage("company-a", bizDate, 1L, 1))
			.thenReturn(new DishCursorPage(Collections.singletonList(dish(2L, "冬瓜")), 2L));
		when(queryService.queryIngredientListMap(eq("company-a"), anyList()))
			.thenAnswer(invocation -> ingredientMap(invocation.getArgument(1)));

		TagHashCalculator hashCalculator = mock(TagHashCalculator.class);
		when(hashCalculator.calculate(anyString(), anyString(), anyString(), any(Dish.class)))
			.thenAnswer(invocation -> "hash-" + ((Dish) invocation.getArgument(3)).getDishId());
		CtDishTagService persistence = mock(CtDishTagService.class);
		when(persistence.findCandidates(eq("company-a"), anySet(), anySet(), anySet()))
			.thenAnswer(invocation -> existing((java.util.Set<Long>) invocation.getArgument(1)));
		DishTagProperties properties = new DishTagProperties();
		properties.setModelVersionDishtag("model-b");
		NutritionMatcher nutritionMatcher = mock(NutritionMatcher.class);
		when(nutritionMatcher.match(any(Dish.class), any(com.example.healthreport.constants.NutritionKey.class)))
			.thenReturn(TagValue.of(TagState.NEUTRAL));
		DietPositiveMatcher dietMatcher = mock(DietPositiveMatcher.class);
		when(dietMatcher.match(any(Dish.class), any(com.example.healthreport.constants.DietRequirementKey.class),
				any(TagState.class)))
			.thenReturn(TagValue.of(TagState.NEUTRAL));
		DishRecommendSetCache setCache = mock(DishRecommendSetCache.class);
		DishTagService service = new DishTagService(queryService, hashCalculator, persistence,
				mock(DishTagWriteService.class), mock(DishTagClient.class), properties, nutritionMatcher,
				dietMatcher, mock(AllergenKeywordFallback.class), setCache, new DishTagSetCatalog(),
				new DishSetMemberCodec(), 1, 2);

		service.run(bizDate);

		verify(setCache, never()).publish(anyString(), any(LocalDate.class), anyString(), anyList());
	}

	@Test
	void oneCompanyFailureAndDiscardFailureShouldNotBlockNextCompany() {
		LocalDate bizDate = LocalDate.of(2026, 8, 28);
		DishQueryService queryService = mock(DishQueryService.class);
		when(queryService.queryPreheatCompanyPage(bizDate, null, 2))
			.thenReturn(new CompanyCursorPage(java.util.Arrays.asList("company-a", "company-b"), "company-b"));
		when(queryService.queryPreheatCompanyPage(bizDate, "company-b", 2))
			.thenReturn(new CompanyCursorPage(Collections.<String>emptyList(), null));
		when(queryService.queryOnShelfDishPage(anyString(), eq(bizDate), any(), eq(2)))
			.thenReturn(new DishCursorPage(Collections.<Dish>emptyList(), null));

		DishRecommendSetCache setCache = mock(DishRecommendSetCache.class);
		doAnswer(invocation -> {
			if ("company-a".equals(invocation.getArgument(0))) {
				throw new IllegalStateException("publish-key-must-not-be-logged");
			}
			return null;
		}).when(setCache).publish(anyString(), eq(bizDate), anyString(), anyList());
		doThrow(new IllegalStateException("discard-key-must-not-be-logged")).when(setCache)
			.discard(eq("company-a"), eq(bizDate), anyString(), anyList());

		DishTagSetCatalog catalog = new DishTagSetCatalog();
		DishTagService service = new DishTagService(queryService, mock(TagHashCalculator.class),
				mock(CtDishTagService.class), mock(DishTagWriteService.class), mock(DishTagClient.class),
				new DishTagProperties(), mock(NutritionMatcher.class), mock(DietPositiveMatcher.class),
				mock(AllergenKeywordFallback.class), setCache, catalog, new DishSetMemberCodec(), 2, 500);

		service.run(bizDate);

		verify(setCache).publish(eq("company-b"), eq(bizDate), anyString(), eq(catalog.publishRefs()));
	}

	private static Dish dish(long id, String name) {
		return new Dish("company-a", id, name, Collections.<DishIngredient>emptyList());
	}

	private static Map<Long, List<DishIngredient>> ingredientMap(List<Long> dishIdList) {
		Map<Long, List<DishIngredient>> resultMap = new LinkedHashMap<Long, List<DishIngredient>>();
		for (Long dishId : dishIdList) {
			resultMap.put(dishId, Collections.singletonList(new DishIngredient("食材", null)));
		}
		return resultMap;
	}

	private static List<CtDishTagEntity> existing(java.util.Set<Long> dishIdSet) {
		List<CtDishTagEntity> resultList = new ArrayList<CtDishTagEntity>(dishIdSet.size() * 22);
		for (Long dishId : dishIdSet) {
			for (AllergenGroup group : AllergenGroups.foodBorneGroups()) {
				resultList.add(entity(dishId, group.getKey().name()));
			}
			for (com.example.healthreport.constants.DietRequirementKey key : DietRequirementContents.ALL.keySet()) {
				resultList.add(entity(dishId, key.name()));
			}
		}
		return resultList;
	}

	private static CtDishTagEntity entity(long dishId, String enumKey) {
		CtDishTagEntity entity = new CtDishTagEntity();
		entity.setCompanyId("company-a");
		entity.setDishId(dishId);
		entity.setTagHash("hash-" + dishId);
		entity.setEnumKey(enumKey);
		entity.setVerdict(TagState.NEUTRAL.name());
		entity.setMatchedIngredients("[]");
		return entity;
	}

}
