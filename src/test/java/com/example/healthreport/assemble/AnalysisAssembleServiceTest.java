package com.example.healthreport.assemble;

import com.example.healthreport.assemble.dietadvice.DietAdviceAssembler;
import com.example.healthreport.assemble.dietadvice.DietAdviceInputFactory;
import com.example.healthreport.assemble.dishrecommend.DishRecommendAssembler;
import com.example.healthreport.assemble.dishrecommend.DishRecommendInputFactory;
import com.example.healthreport.assemble.indicator.IndicatorAssembler;
import com.example.healthreport.assemble.problem.ProblemAssembler;
import com.example.healthreport.assemble.sort.DisplayOrder;
import com.example.healthreport.cache.AnalysisModules;
import com.example.healthreport.cache.DishRecommendSetCache;
import com.example.healthreport.cache.DishSetMemberCodec;
import com.example.healthreport.parse.segment.TextNormalizer;
import com.example.healthreport.dish.TagStateResolver;
import com.example.healthreport.llm.extraction.ValidatedExtractionOutput;
import com.example.healthreport.llm.extraction.ValidatedExtractionOutputTestFactory;
import com.example.healthreport.safety.HighRiskAdviceGate;
import com.example.healthreport.safety.StructuredAdmission;
import com.example.healthreport.task.DegradeAccumulator;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 组装阶段编排：四模块各一个元素，以及抑制时不读菜品集合。 */
class AnalysisAssembleServiceTest {

	private static final LocalDate BIZ_DATE = LocalDate.of(2026, 8, 27);

	@Test
	void shouldProduceOneResultPerModule() {
		DishRecommendSetCache setCache = mock(DishRecommendSetCache.class);
		when(setCache.read(any(String.class), any(LocalDate.class), any())).thenReturn(Collections.emptyMap());

		AnalysisModules modules = newService(setCache).assemble(output(), 1, "company-a", BIZ_DATE,
				new DegradeAccumulator());

		assertThat(modules.getHealthIndicators()).isInstanceOf(IndicatorAssembler.Result.class);
		assertThat(modules.getHealthProblems()).isInstanceOf(ProblemAssembler.Result.class);
		assertThat(modules.getDietAdvice()).isInstanceOf(DietAdviceAssembler.Result.class);
		assertThat(modules.getDishRecommendations()).isInstanceOf(DishRecommendAssembler.Result.class);
		verify(setCache, never()).read(any(String.class), any(LocalDate.class), any());
	}

	@Test
	void suppressedDishRecommendShouldNotReadAnyTagSet() {
		DishRecommendSetCache setCache = mock(DishRecommendSetCache.class);
		DegradeAccumulator accumulator = new DegradeAccumulator();
		accumulator.recordAllergenSuspectMiss();
		assertThat(accumulator.suppressDishRecommend()).isTrue();

		AnalysisModules modules = newService(setCache).assemble(output(), 1, "company-a", BIZ_DATE, accumulator);

		// 离线标签同样不读：抑制后这条读取链路上的任何一次调用都是白花的。
		verify(setCache, never()).read(any(String.class), any(LocalDate.class), any());
		// 抑制 = 这个模块不存在 = 字段 null；模块一二不受影响。
		assertThat(modules.getDishRecommendations()).isNull();
		assertThat(modules.getHealthIndicators()).isNotNull();
	}

	private AnalysisAssembleService newService(DishRecommendSetCache setCache) {
		DisplayOrder displayOrder = new DisplayOrder();
		HighRiskAdviceGate highRiskAdviceGate = new HighRiskAdviceGate(new TextNormalizer());
		return new AnalysisAssembleService(new IndicatorAssembler(displayOrder), new ProblemAssembler(displayOrder),
				new DietAdviceInputFactory(displayOrder),
				new DietAdviceAssembler(new StructuredAdmission(highRiskAdviceGate)),
				new DishRecommendInputFactory(setCache, new DishSetMemberCodec(),
						new StructuredAdmission(highRiskAdviceGate)),
				new DishRecommendAssembler(new TagStateResolver(), displayOrder));
	}

	private ValidatedExtractionOutput output() {
		return ValidatedExtractionOutputTestFactory.withUnreferencedSourceText("体检结论");
	}

}
