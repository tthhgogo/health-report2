package com.example.healthreport.assemble;

import com.example.healthreport.assemble.dietadvice.DietAdviceAssembler;
import com.example.healthreport.assemble.dietadvice.DietAdviceCounters;
import com.example.healthreport.assemble.dietadvice.DietAdviceInputFactory;
import com.example.healthreport.assemble.dishrecommend.DishRecommendAssembler;
import com.example.healthreport.assemble.dishrecommend.DishRecommendInputFactory;
import com.example.healthreport.assemble.indicator.IndicatorAssembler;
import com.example.healthreport.assemble.problem.ProblemAssembler;
import com.example.healthreport.assemble.sort.DisplayOrder;
import com.example.healthreport.cache.AnalysisModules;
import com.example.healthreport.dish.Dish;
import com.example.healthreport.dish.DishTagReadService;
import com.example.healthreport.dish.MainIngredientResolver;
import com.example.healthreport.dish.NutritionMatcher;
import com.example.healthreport.dish.TagStateResolver;
import com.example.healthreport.infra.DishQueryService;
import com.example.healthreport.llm.extraction.ValidatedExtractionOutput;
import com.example.healthreport.llm.extraction.ValidatedExtractionOutputTestFactory;
import com.example.healthreport.safety.AllergenKeywordFallback;
import com.example.healthreport.safety.HighRiskAdviceGate;
import com.example.healthreport.task.DegradeAccumulator;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 组装阶段编排：四模块各一个元素，以及抑制时不查食堂。 */
class AnalysisAssembleServiceTest {

    private static final LocalDate BIZ_DATE = LocalDate.of(2026, 8, 27);

    @Test
    void shouldProduceOneResultPerModule() {
        DishQueryService dishQueryService = mock(DishQueryService.class);
        when(dishQueryService.queryOnShelfDishes(BIZ_DATE))
                .thenReturn(Collections.<Dish>emptyList());

        AnalysisModules modules = newService(dishQueryService)
                .assemble(output(), 1, new DegradeAccumulator());

        assertThat(modules.getHealthIndicators()).isInstanceOf(IndicatorAssembler.Result.class);
        assertThat(modules.getHealthProblems()).isInstanceOf(ProblemAssembler.Result.class);
        assertThat(modules.getDietAdvice()).isInstanceOf(DietAdviceAssembler.Result.class);
        assertThat(modules.getDishRecommendations())
                .isInstanceOf(DishRecommendAssembler.Result.class);
        verify(dishQueryService).queryOnShelfDishes(BIZ_DATE);
    }

    @Test
    void suppressedDishRecommendShouldNotQueryTheCanteenNorReadAnyTag() {
        DishQueryService dishQueryService = mock(DishQueryService.class);
        DishTagReadService dishTagReadService = mock(DishTagReadService.class);
        DegradeAccumulator accumulator = new DegradeAccumulator();
        accumulator.recordAllergenSuspectMiss();
        assertThat(accumulator.suppressDishRecommend()).isTrue();

        AnalysisModules modules = newService(dishQueryService, dishTagReadService)
                .assemble(output(), 1, accumulator);

        // 离线标签同样不读：抑制后这条读取链路上的任何一次调用都是白花的。
        verify(dishTagReadService, never()).read(any(LocalDate.class), any(), any());

        // 查回来也会被丢掉；省掉这次跨系统只读调用，降级路径才不依赖食堂库可用。
        verify(dishQueryService, never()).queryOnShelfDishes(any(LocalDate.class));
        // 抑制 = 这个模块不存在 = 字段 null；模块一二不受影响。
        assertThat(modules.getDishRecommendations()).isNull();
        assertThat(modules.getHealthIndicators()).isNotNull();
    }

    private AnalysisAssembleService newService(DishQueryService dishQueryService) {
        return newService(dishQueryService, mock(DishTagReadService.class));
    }

    private AnalysisAssembleService newService(DishQueryService dishQueryService,
                                               DishTagReadService dishTagReadService) {
        DisplayOrder displayOrder = new DisplayOrder();
        HighRiskAdviceGate highRiskAdviceGate = new HighRiskAdviceGate();
        return new AnalysisAssembleService(
                new IndicatorAssembler(displayOrder),
                new ProblemAssembler(displayOrder),
                new DietAdviceInputFactory(displayOrder),
                new DietAdviceAssembler(highRiskAdviceGate, new DietAdviceCounters()),
                new DishRecommendInputFactory(dishTagReadService,
                        new NutritionMatcher(new MainIngredientResolver()), new AllergenKeywordFallback(), highRiskAdviceGate),
                new DishRecommendAssembler(new TagStateResolver(), displayOrder),
                dishQueryService,
                Clock.fixed(BIZ_DATE.atStartOfDay(ZoneId.systemDefault()).toInstant(),
                        ZoneId.systemDefault()));
    }

    private ValidatedExtractionOutput output() {
        return ValidatedExtractionOutputTestFactory.withUnreferencedSourceText("体检结论");
    }
}
