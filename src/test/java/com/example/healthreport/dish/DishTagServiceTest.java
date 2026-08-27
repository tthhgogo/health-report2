package com.example.healthreport.dish;

import com.example.healthreport.cache.DishTagCache;
import com.example.healthreport.constants.AllergenGroup;
import com.example.healthreport.constants.AllergenGroups;
import com.example.healthreport.constants.DietRequirementContents;
import com.example.healthreport.infra.DishQueryService;
import com.example.healthreport.llm.dishtag.DishTagClient;
import com.example.healthreport.llm.dishtag.DishTagProperties;
import com.example.healthreport.llm.dishtag.DishTagInput;
import com.example.healthreport.llm.dishtag.DishTagOutput;
import com.example.healthreport.persistence.CtDishTagEntity;
import com.example.healthreport.persistence.CtDishTagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 离线 diff、命中刷新、缺失补标与业务日透传。 */
class DishTagServiceTest {

	@Test
	void existingCombinationsShouldOnlyRefreshAndNeverCallModel() {
		Fixture fixture = new Fixture();
		List<CtDishTagEntity> existingList = fixture.allExisting();
		when(fixture.persistence.findCandidates(any(), any(), any())).thenReturn(existingList);

		fixture.service.run(fixture.bizDate);

		verify(fixture.queryService).queryOnShelfDishes(fixture.bizDate);
		verify(fixture.persistence, times(22)).refreshLastSeen(any(CtDishTagEntity.class), eq(fixture.bizDate));
		verify(fixture.llmBClient, never()).tag(any(DishTagInput.class));
		verify(fixture.cache, times(22)).putAll(eq(fixture.bizDate), any(String.class), anyMap());
	}

	@Test
	void oneMissingCombinationShouldCallModelOnceAndWriteOnlyThatCombination() {
		Fixture fixture = new Fixture();
		List<CtDishTagEntity> existingList = fixture.allExisting();
		existingList.remove(existingList.size() - 9);
		when(fixture.persistence.findCandidates(any(), any(), any())).thenReturn(existingList);
		DishTagOutput output = new DishTagOutput();
		output.setEnumKey("LOW_FAT");
		output.setNeutralDishIds(Collections.singletonList(1L));
		output.setUnknownDishIds(Collections.<Long>emptyList());
		output.setHitList(Collections.<DishTagOutput.Hit>emptyList());
		when(fixture.llmBClient.tag(any(DishTagInput.class))).thenReturn(output);
		CtDishTagEntity newEntity = fixture.entity("LOW_FAT");
		when(fixture.writeService.stateEntity(eq(1L), eq("stable-hash"), eq("LOW_FAT"), eq(TagState.NEUTRAL),
				any(String.class), any(String.class), any(String.class), eq(fixture.bizDate)))
			.thenReturn(newEntity);

		fixture.service.run(fixture.bizDate);

		ArgumentCaptor<DishTagInput> inputCaptor = ArgumentCaptor.forClass(DishTagInput.class);
		verify(fixture.llmBClient).tag(inputCaptor.capture());
		assertThat(inputCaptor.getValue().getEnumKey()).isEqualTo("LOW_FAT");
		verify(fixture.writeService).write(eq(newEntity), any());
	}

	private static final class Fixture {

		private final LocalDate bizDate = LocalDate.of(2026, 8, 26);

		private final Dish dish = new Dish(1L, "测试菜", Collections.singletonList(new DishIngredient("食材", null)));

		private final DishQueryService queryService = mock(DishQueryService.class);

		private final TagHashCalculator hashCalculator = mock(TagHashCalculator.class);

		private final CtDishTagService persistence = mock(CtDishTagService.class);

		private final DishTagWriteService writeService = mock(DishTagWriteService.class);

		private final DishTagClient llmBClient = mock(DishTagClient.class);

		private final DishTagProperties properties = new DishTagProperties();

		private final DishTagCache cache = mock(DishTagCache.class);

		private final DishTagService service;

		private Fixture() {
			properties.setModelVersionDishtag("model-b");
			when(queryService.queryOnShelfDishes(bizDate)).thenReturn(Collections.singletonList(dish));
			when(hashCalculator.calculate(any(String.class), any(String.class), any(String.class), eq(dish)))
				.thenReturn("stable-hash");
			when(cache.field(1L, "stable-hash")).thenReturn("1:stable-hash");
			service = new DishTagService(queryService, hashCalculator, persistence, writeService, llmBClient,
					properties, cache, new ObjectMapper());
		}

		private List<CtDishTagEntity> allExisting() {
			List<CtDishTagEntity> resultList = new ArrayList<CtDishTagEntity>(22);
			for (AllergenGroup group : AllergenGroups.foodBorneGroups()) {
				resultList.add(entity(group.getKey().name()));
			}
			for (com.example.healthreport.constants.DietRequirementKey key : DietRequirementContents.ALL.keySet()) {
				resultList.add(entity(key.name()));
			}
			return resultList;
		}

		private CtDishTagEntity entity(String enumKey) {
			CtDishTagEntity entity = new CtDishTagEntity();
			entity.setDishId(1L);
			entity.setTagHash("stable-hash");
			entity.setEnumKey(enumKey);
			entity.setVerdict(TagState.NEUTRAL.name());
			entity.setMatchedIngredients("[]");
			return entity;
		}

	}

}
