package com.example.healthreport.dish;

import com.example.healthreport.llm.dishtag.DishTagOutput;
import com.example.healthreport.persistence.CtDishTagEntity;
import com.example.healthreport.persistence.CtDishTagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** REJECT 食材证据子集校验与 fail-safe 降 UNKNOWN。 */
class DishTagWriteServiceTest {

	private final CtDishTagService persistenceService = mock(CtDishTagService.class);

	private final DishTagWriteService service = new DishTagWriteService(persistenceService, new ObjectMapper());

	@Test
	void allUnmatchedIngredientEvidenceShouldDowngradeToUnknown() {
		DishTagOutput.Hit hit = hit("INGREDIENT", Collections.singletonList("不存在食材"));
		Dish dish = new Dish("company-a", 1L, "测试菜", Collections.singletonList(new DishIngredient("实际食材", null)));

		CtDishTagEntity entity = service.toEntity(hit, dish, "hash", "FISH", "model", "prompt", "rule",
				LocalDate.of(2026, 8, 26));

		assertThat(entity.getVerdict()).isEqualTo(TagState.UNKNOWN.name());
		assertThat(entity.getEvidenceType()).isNull();
		assertThat(entity.getMatchedIngredients()).isEqualTo("[]");
	}

	@Test
	void validIngredientEvidenceShouldBeWrittenOnce() {
		when(persistenceService.insertFromJob(any(CtDishTagEntity.class))).thenReturn(1);
		DishTagOutput.Hit hit = hit("INGREDIENT", Collections.singletonList("实际食材"));
		Dish dish = new Dish("company-a", 1L, "测试菜", Collections.singletonList(new DishIngredient("实际食材", null)));
		CtDishTagEntity entity = service.toEntity(hit, dish, "hash", "FISH", "model", "prompt", "rule",
				LocalDate.of(2026, 8, 26));

		service.write(entity, new HashSet<String>(Collections.singletonList("实际食材")));

		assertThat(entity.getVerdict()).isEqualTo(TagState.REJECT.name());
		verify(persistenceService).insertFromJob(entity);
	}

	private DishTagOutput.Hit hit(String evidenceType, java.util.List<String> matchedList) {
		DishTagOutput.Hit hit = new DishTagOutput.Hit();
		hit.setDishId(1L);
		hit.setVerdict("REJECT");
		hit.setEvidenceType(evidenceType);
		hit.setMatchedIngredients(matchedList);
		hit.setReason("明确依据");
		return hit;
	}

}
