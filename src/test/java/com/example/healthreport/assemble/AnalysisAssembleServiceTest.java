package com.example.healthreport.assemble;

import com.example.healthreport.assemble.dietadvice.DietAdviceAssembler;
import com.example.healthreport.assemble.dishrecommend.DishNameSorter;
import com.example.healthreport.assemble.dishrecommend.DishRecommendAssembler;
import com.example.healthreport.assemble.dishrecommend.DishRecommendInputFactory;
import com.example.healthreport.assemble.indicator.IndicatorAssembler;
import com.example.healthreport.assemble.problem.ProblemAssembler;
import com.example.healthreport.cache.AnalysisModules;
import com.example.healthreport.cache.DishRecommendSetCache;
import com.example.healthreport.cache.DishSetMemberCodec;
import com.example.healthreport.llm.extraction.DietTagsResult;
import com.example.healthreport.llm.extraction.ExtractionOutcome;
import com.example.healthreport.llm.extraction.IndicatorStatus;
import com.example.healthreport.llm.extraction.IndicatorsResult;
import com.example.healthreport.llm.extraction.ProblemsResult;
import com.example.healthreport.render.PageImageSequence;
import com.example.healthreport.safety.HighRiskAdviceGate;
import com.example.healthreport.support.text.TextNormalizer;
import com.example.healthreport.task.DegradeAccumulator;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 组装阶段编排：四模块各一个对象，以及抑制时不读菜品集合。 */
class AnalysisAssembleServiceTest {

	private static final LocalDate BIZ_DATE = LocalDate.of(2026, 9, 3);

	@Test
	void shouldProduceOneResultPerModule() {
		DishRecommendSetCache setCache = mock(DishRecommendSetCache.class);
		when(setCache.read(any(String.class), any(LocalDate.class), any()))
				.thenReturn(Collections.emptyMap());

		AnalysisModules modules = newService(setCache).assemble(outcome(), images(), 1, "company-a",
				BIZ_DATE, new DegradeAccumulator());

		assertThat(modules.getHealthIndicators()).isInstanceOf(IndicatorAssembler.Result.class);
		assertThat(modules.getHealthProblems()).isInstanceOf(ProblemAssembler.Result.class);
		assertThat(modules.getDietAdvice()).isInstanceOf(DietAdviceAssembler.Result.class);
		assertThat(modules.getDishRecommendations()).isInstanceOf(DishRecommendAssembler.Result.class);
	}

	@Test
	void suppressedDishRecommendShouldNotReadAnyTagSet() {
		DishRecommendSetCache setCache = mock(DishRecommendSetCache.class);
		DegradeAccumulator accumulator = new DegradeAccumulator();
		accumulator.recordDietTagDropped();
		assertThat(accumulator.suppressDishRecommend()).isTrue();

		AnalysisModules modules = newService(setCache).assemble(outcome(), images(), 1, "company-a",
				BIZ_DATE, accumulator);

		// 离线标签同样不读：抑制后这条读取链路上的任何一次调用都是白花的。
		verify(setCache, never()).read(any(String.class), any(LocalDate.class), any());
		// 抑制 = 这个模块不存在 = 字段 null；模块一二三不受影响。
		assertThat(modules.getDishRecommendations()).isNull();
		assertThat(modules.getHealthIndicators()).isNotNull();
		assertThat(modules.getDietAdvice()).isNotNull();
	}

	private AnalysisAssembleService newService(DishRecommendSetCache setCache) {
		HighRiskAdviceGate highRiskAdviceGate = new HighRiskAdviceGate(new TextNormalizer());
		return new AnalysisAssembleService(new IndicatorAssembler(), new ProblemAssembler(),
				new DietAdviceAssembler(highRiskAdviceGate),
				new DishRecommendInputFactory(setCache, new DishSetMemberCodec(), highRiskAdviceGate),
				new DishRecommendAssembler(new DishNameSorter()));
	}

	private ExtractionOutcome outcome() {
		IndicatorsResult indicators = new IndicatorsResult("OK", null,
				new IndicatorsResult.Overview(10, 2, "REPORT"),
				Collections.singletonList(new IndicatorsResult.Section("血脂检查", 1,
						Collections.singletonList(new IndicatorsResult.Indicator(
								"甘油三酯", "2.8", "mmol/L", "0.56~1.70", false, IndicatorStatus.HIGH)))));
		ProblemsResult problems = new ProblemsResult("OK",
				Collections.singletonList(new ProblemsResult.Problem(
						"INDICATOR", 1, "血脂检查", null, "甘油三酯", "甘油三酯 偏高",
						"甘油三酯 2.8 mmol/L 0.56~1.70 偏高")));
		DietTagsResult dietTags = new DietTagsResult("OK",
				Collections.<DietTagsResult.DietTag>emptyList(),
				Arrays.asList(new DietTagsResult.DietTag("ALLERGEN", "SHRIMP_CRAB", 1,
						"过敏原筛查", null, "虾蟹类 阳性(+)", "虾蟹类 阳性(+) 参考值：阴性")));
		return new ExtractionOutcome(indicators, problems, dietTags);
	}

	private PageImageSequence images() {
		return new PageImageSequence.Builder()
				.addPage(0, 1, new byte[]{1})
				.build();
	}

}
