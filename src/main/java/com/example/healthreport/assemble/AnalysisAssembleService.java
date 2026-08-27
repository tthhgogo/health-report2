package com.example.healthreport.assemble;

import com.example.healthreport.assemble.dietadvice.DietAdviceAssembler;
import com.example.healthreport.assemble.dietadvice.DietAdviceInputFactory;
import com.example.healthreport.assemble.dishrecommend.DishRecommendAssembler;
import com.example.healthreport.assemble.dishrecommend.DishRecommendInputFactory;
import com.example.healthreport.assemble.indicator.IndicatorAssembler;
import com.example.healthreport.assemble.problem.ProblemAssembler;
import com.example.healthreport.cache.AnalysisModules;
import com.example.healthreport.dish.Dish;
import com.example.healthreport.infra.DishQueryService;
import com.example.healthreport.llm.extraction.ValidatedExtractionOutput;
import com.example.healthreport.task.DegradeAccumulator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * 组装阶段编排：四个展示模块，纯 Java，<b>不调用任何模型</b>。
 *
 * <p>模块四只读离线打标结果（{@code ct_dish_tag} / Redis），在线链路对 LLM-B 的调用次数
 * 必须为 0——这一条由 {@link DishRecommendInputFactory} 的依赖结构保证，本类不做补充调用。</p>
 */
@Slf4j
@Service
public class AnalysisAssembleService {

    private final IndicatorAssembler indicatorAssembler;
    private final ProblemAssembler problemAssembler;
    private final DietAdviceInputFactory dietAdviceInputFactory;
    private final DietAdviceAssembler dietAdviceAssembler;
    private final DishRecommendInputFactory dishRecommendInputFactory;
    private final DishRecommendAssembler dishRecommendAssembler;
    private final DishQueryService dishQueryService;
    private final Clock clock;

    @Autowired
    public AnalysisAssembleService(IndicatorAssembler indicatorAssembler,
                                   ProblemAssembler problemAssembler,
                                   DietAdviceInputFactory dietAdviceInputFactory,
                                   DietAdviceAssembler dietAdviceAssembler,
                                   DishRecommendInputFactory dishRecommendInputFactory,
                                   DishRecommendAssembler dishRecommendAssembler,
                                   DishQueryService dishQueryService) {
        this(indicatorAssembler, problemAssembler, dietAdviceInputFactory, dietAdviceAssembler,
                dishRecommendInputFactory, dishRecommendAssembler, dishQueryService,
                Clock.systemDefaultZone());
    }

    /** 可注入时钟的构造器，仅用于确定性测试。 */
    AnalysisAssembleService(IndicatorAssembler indicatorAssembler,
                            ProblemAssembler problemAssembler,
                            DietAdviceInputFactory dietAdviceInputFactory,
                            DietAdviceAssembler dietAdviceAssembler,
                            DishRecommendInputFactory dishRecommendInputFactory,
                            DishRecommendAssembler dishRecommendAssembler,
                            DishQueryService dishQueryService,
                            Clock clock) {
        this.indicatorAssembler = indicatorAssembler;
        this.problemAssembler = problemAssembler;
        this.dietAdviceInputFactory = dietAdviceInputFactory;
        this.dietAdviceAssembler = dietAdviceAssembler;
        this.dishRecommendInputFactory = dishRecommendInputFactory;
        this.dishRecommendAssembler = dishRecommendAssembler;
        this.dishQueryService = dishQueryService;
        this.clock = clock;
    }

    /**
     * 组装四个模块。
     *
     * @param fileCount 本次实际参与抽取的文件数，决定多文件场景下的来源标注
     * @return 四个模块各自一个 Result 元素；降级裁剪由
     *         {@link com.example.healthreport.cache.AnalysisResult#create} 统一执行，本类不重复
     */
    public AnalysisModules assemble(ValidatedExtractionOutput output, int fileCount,
                                    DegradeAccumulator degradeAccumulator) {
        if (output == null || degradeAccumulator == null || fileCount < 1) {
            throw new IllegalArgumentException("组装阶段参数不能为空");
        }
        long startMillis = System.currentTimeMillis();
        IndicatorAssembler.Result indicatorResult = indicatorAssembler.assemble(output, fileCount);
        ProblemAssembler.Result problemResult = problemAssembler.assemble(output, fileCount);
        DietAdviceAssembler.Result dietAdviceResult = dietAdviceAssembler.assemble(
                dietAdviceInputFactory.create(output, fileCount));
        DishRecommendAssembler.Result dishRecommendResult = assembleDishRecommend(
                output, degradeAccumulator);

        // 与抽取阶段同一条口径：只记「哪些模块出来了」，不记模块里有几条内容。
        // 模块四有没有出来是用户直接能看见的差异，出问题时第一个要确认的就是它。
        log.info("组装阶段完成，文件数={}，饮食建议已输出={}，菜品推荐已输出={}，耗时={}ms",
                fileCount, dietAdviceResult != null, dishRecommendResult != null,
                System.currentTimeMillis() - startMillis);

        // 模块四被抑制时 DishRecommendAssembler 返回 null，直接落成字段 null。
        return new AnalysisModules(indicatorResult, problemResult,
                dietAdviceResult, dishRecommendResult);
    }

    /**
     * 组装模块四。
     * <p>抑制时<b>不查当日菜品</b>：查回来的结果本来就会被丢掉，
     * 而这一次查询是跨系统只读调用，省掉它既省一次往返，也让降级路径不依赖食堂库可用。</p>
     */
    private DishRecommendAssembler.Result assembleDishRecommend(
            ValidatedExtractionOutput output, DegradeAccumulator degradeAccumulator) {
        // suppressDietAdvice 会连模块四一起清空（§4.1.1），所以它同样算作抑制。
        boolean suppressed = degradeAccumulator.suppressDietAdvice()
                || degradeAccumulator.suppressDishRecommend();
        LocalDate bizDate = LocalDate.now(clock);
        List<Dish> dishList = suppressed
                ? Collections.<Dish>emptyList()
                : DishQueryService.assertValidResult(dishQueryService.queryOnShelfDishes(bizDate));
        // 菜品是食堂公开数据，数量可安全记录；「当日 0 道在架菜」是推荐为空的常见根因，
        // 不记这个数就分不清是被降级抑制了还是食堂根本没上架。
        log.info("模块四输入就绪，业务日={}，已抑制={}，当日在架菜品数={}",
                bizDate, suppressed, dishList.size());
        return dishRecommendAssembler.assemble(
                dishRecommendInputFactory.create(bizDate, dishList, output, suppressed));
    }
}
