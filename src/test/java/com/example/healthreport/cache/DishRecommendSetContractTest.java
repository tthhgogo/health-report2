package com.example.healthreport.cache;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 企业方向 SET Key 与复合成员格式契约测试。 */
class DishRecommendSetContractTest {

	@Test
	void shouldEncodeCompanyAndKeepAllDirectionsInSameHashSlot() {
		DishRecommendSetKeyFactory factory = new DishRecommendSetKeyFactory(new CompanyRedisKeyCodec());
		LocalDate bizDate = LocalDate.of(2026, 8, 28);
		DishTagSetRef allergen = new DishTagSetRef(DishTagSetRef.Category.ALLERGEN, DishTagSetRef.Direction.REJECT,
				"SHRIMP_CRAB");
		DishTagSetRef nutrition = new DishTagSetRef(DishTagSetRef.Category.NUTRITION, DishTagSetRef.Direction.RECOMMEND,
				"IRON");

		String allergenKey = factory.formalKey("企业:A", bizDate, allergen);
		String nutritionKey = factory.formalKey("企业:A", bizDate, nutrition);

		assertThat(allergenKey).doesNotContain("企业:A").contains("{5LyB5LiaOkE:2026-08-28}");
		assertThat(nutritionKey).contains("{5LyB5LiaOkE:2026-08-28}");
		assertThat(allergenKey).doesNotContain(":active").doesNotEndWith(":all");
	}

	@Test
	void shouldRoundTripDishIdAndNameAndRejectControlCharacters() {
		DishSetMemberCodec codec = new DishSetMemberCodec();

		DishSetMember member = codec.decode(codec.encode(1001L, "菠菜猪肝汤"));

		assertThat(member.getDishId()).isEqualTo(1001L);
		assertThat(member.getDishName()).isEqualTo("菠菜猪肝汤");
		assertThatThrownBy(() -> codec.encode(1001L, "坏\t菜名")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> codec.decode("1001\t菜名\n注入")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void catalogShouldContainExactlyThirtyThreeDirections() {
		DishTagSetCatalog catalog = new DishTagSetCatalog();

		assertThat(catalog.publishRefs()).hasSize(33);
		assertThat(catalog.publishRefs())
			.filteredOn(ref -> ref.getCategory() == DishTagSetRef.Category.DIET
					&& ref.getDirection() == DishTagSetRef.Direction.RECOMMEND)
			.extracting(DishTagSetRef::getEnumKey)
			.containsExactly("LOW_PURINE", "HIGH_FIBER");
	}

	/**
	 * 一次 EVAL 取回全部方向，且 Key 由脚本参数下发。
	 * <p>
	 * <b>不得改回 pipeline</b>：Redis Cluster + Jedis 下 {@code openPipeline} 抛
	 * {@code UnsupportedOperationException}，模块四会每次静默空态（2026-09-05 生产复现）。
	 * </p>
	 */
	@Test
	@SuppressWarnings({ "rawtypes", "unchecked" })
	void readShouldUseOneScriptRoundTripForAllRequestedDirections() {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		List<Object> scriptResultList = new ArrayList<Object>();
		scriptResultList.add(Collections.singletonList("1\t菜品一"));
		scriptResultList.add(Collections.emptyList());
		ArgumentCaptor<List> keyListCaptor = ArgumentCaptor.forClass(List.class);
		when(redisTemplate.execute(any(RedisScript.class), anyList())).thenReturn(scriptResultList);
		DishRecommendSetCache cache = new DishRecommendSetCache(redisTemplate,
				new DishRecommendSetKeyFactory(new CompanyRedisKeyCodec()));
		DishTagSetRef allergen = new DishTagSetRef(DishTagSetRef.Category.ALLERGEN,
				DishTagSetRef.Direction.REJECT, "SHRIMP_CRAB");
		DishTagSetRef nutrition = new DishTagSetRef(DishTagSetRef.Category.NUTRITION,
				DishTagSetRef.Direction.RECOMMEND, "IRON");
		List<DishTagSetRef> refList = Arrays.asList(allergen, nutrition);

		Map<DishTagSetRef, Set<String>> resultMap = cache.read("company-a", LocalDate.of(2026, 8, 28), refList);

		assertThat(resultMap.get(allergen)).containsExactly("1\t菜品一");
		assertThat(resultMap.get(nutrition)).isEmpty();
		verify(redisTemplate, times(1)).execute(any(RedisScript.class), keyListCaptor.capture());
		verify(redisTemplate, never()).executePipelined(any(RedisCallback.class));
		// 同一次调用里的 Key 必须共享同一个 hash tag，否则集群下 EVAL 会因跨 slot 直接失败。
		List<String> capturedKeyList = keyListCaptor.getValue();
		assertThat(capturedKeyList).hasSize(2);
		assertThat(capturedKeyList.get(1)).contains(hashTagOf(capturedKeyList.get(0)));
	}

	@Test
	@SuppressWarnings({ "rawtypes", "unchecked" })
	void scriptFailureShouldDegradeModuleFourToEmptyMap() {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		when(redisTemplate.execute(any(RedisScript.class), anyList()))
			.thenThrow(new UnsupportedOperationException("模拟集群不支持"));
		DishRecommendSetCache cache = new DishRecommendSetCache(redisTemplate,
				new DishRecommendSetKeyFactory(new CompanyRedisKeyCodec()));
		DishTagSetRef nutrition = new DishTagSetRef(DishTagSetRef.Category.NUTRITION,
				DishTagSetRef.Direction.RECOMMEND, "IRON");

		Map<DishTagSetRef, Set<String>> resultMap = cache.read("company-a", LocalDate.of(2026, 8, 28),
				Collections.singletonList(nutrition));

		assertThat(resultMap).isEmpty();
	}

	/** 取 Key 中 {@code {...}} 包裹的 hash tag 片段。 */
	private String hashTagOf(String key) {
		return key.substring(key.indexOf('{'), key.indexOf('}') + 1);
	}

}
