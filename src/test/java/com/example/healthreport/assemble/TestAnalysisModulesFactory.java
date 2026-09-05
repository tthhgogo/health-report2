package com.example.healthreport.assemble;

import com.example.healthreport.assemble.dietadvice.DietAdviceAssembler;
import com.example.healthreport.assemble.dishrecommend.DishNameSorter;
import com.example.healthreport.assemble.dishrecommend.DishRecommendAssembler;
import com.example.healthreport.assemble.dishrecommend.DishRecommendInput;
import com.example.healthreport.assemble.indicator.IndicatorAssembler;
import com.example.healthreport.assemble.problem.ProblemAssembler;
import com.example.healthreport.cache.AnalysisModules;
import com.example.healthreport.llm.extraction.DietTagsResult;
import com.example.healthreport.llm.extraction.IndicatorStatus;
import com.example.healthreport.llm.extraction.IndicatorsResult;
import com.example.healthreport.llm.extraction.ProblemsResult;
import com.example.healthreport.render.PageImageSequence;
import com.example.healthreport.safety.HighRiskAdviceGate;
import com.example.healthreport.support.text.TextNormalizer;

import java.util.Collections;

/**
 * 供跨包测试构造真实组装产物的公共夹具：四个 Result 的构造器都是包私有，
 * 外部测试只能走各组装器的 public {@code assemble()}。
 */
public final class TestAnalysisModulesFactory {

    private TestAnalysisModulesFactory() {
    }

    /** 四个模块全部非空的最小真实产物。 */
    public static AnalysisModules populated() {
        PageImageSequence images = new PageImageSequence.Builder()
                .addPage(0, 1, new byte[]{1}).build();
        IndicatorsResult indicators = new IndicatorsResult("OK",
                Collections.<IndicatorsResult.Patient>emptyList(),
                new IndicatorsResult.Overview(10, 2, "REPORT"),
                Collections.singletonList(new IndicatorsResult.Section("血脂检查", 1,
                        Collections.singletonList(new IndicatorsResult.Indicator(
                                "甘油三酯", "2.8", "mmol/L", "0.56~1.70", false, IndicatorStatus.HIGH)))));
        IndicatorAssembler.Result moduleOne = new IndicatorAssembler().assemble(indicators, images, 1);
        ProblemAssembler.Result moduleTwo = new ProblemAssembler().assemble(
                new ProblemsResult("OK", Collections.<ProblemsResult.Problem>emptyList()),
                images, 1, moduleOne);
        DietAdviceAssembler.Result moduleThree = new DietAdviceAssembler(
                new HighRiskAdviceGate(new TextNormalizer()))
                .assemble(new DietTagsResult("OK", Collections.<DietTagsResult.DietTag>emptyList(),
                        Collections.<DietTagsResult.DietTag>emptyList()));
        DishRecommendAssembler.Result moduleFour = new DishRecommendAssembler(new DishNameSorter())
                .assemble(new DishRecommendInput(false, false,
                        Collections.<DishRecommendInput.Candidate>emptyList()));
        return new AnalysisModules(moduleOne, moduleTwo, moduleThree, moduleFour);
    }

}
