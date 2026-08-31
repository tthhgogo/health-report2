package com.example.healthreport.cache;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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

	@Test
	@SuppressWarnings({ "rawtypes", "unchecked" })
	void readShouldUseOnePipelineForAllRequestedDirections() {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		RedisSerializer<String> serializer = new StringRedisSerializer();
		when(redisTemplate.getStringSerializer()).thenReturn(serializer);
		List<Object> pipelineResultList = new ArrayList<Object>();
		pipelineResultList.add(Collections.singleton("1\t菜品一"));
		pipelineResultList.add(Collections.emptySet());
		when(redisTemplate.executePipelined(any(RedisCallback.class), eq(serializer))).thenReturn(pipelineResultList);
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
		verify(redisTemplate, times(1)).executePipelined(any(RedisCallback.class), eq(serializer));
	}

	@Test
	@SuppressWarnings({ "rawtypes", "unchecked" })
	void pipelineFailureShouldDegradeModuleFourToEmptyMap() {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		RedisSerializer<String> serializer = new StringRedisSerializer();
		when(redisTemplate.getStringSerializer()).thenReturn(serializer);
		when(redisTemplate.executePipelined(any(RedisCallback.class), eq(serializer)))
			.thenThrow(new IllegalStateException("模拟Redis不可用"));
		DishRecommendSetCache cache = new DishRecommendSetCache(redisTemplate,
				new DishRecommendSetKeyFactory(new CompanyRedisKeyCodec()));
		DishTagSetRef nutrition = new DishTagSetRef(DishTagSetRef.Category.NUTRITION,
				DishTagSetRef.Direction.RECOMMEND, "IRON");

		Map<DishTagSetRef, Set<String>> resultMap = cache.read("company-a", LocalDate.of(2026, 8, 28),
				Collections.singletonList(nutrition));

		assertThat(resultMap).isEmpty();
	}

}
