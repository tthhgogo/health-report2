package com.example.healthreport.assemble;

import com.example.healthreport.assemble.dietadvice.DietAdviceAssembler;
import com.example.healthreport.assemble.dishrecommend.DishRecommendAssembler;
import com.example.healthreport.assemble.dishrecommend.DishRecommendInputFactory;
import com.example.healthreport.assemble.indicator.IndicatorAssembler;
import com.example.healthreport.assemble.problem.ProblemAssembler;
import com.example.healthreport.cache.AnalysisModules;
import com.example.healthreport.llm.extraction.ExtractionOutcome;
import com.example.healthreport.render.PageImageSequence;
import com.example.healthreport.task.DegradeAccumulator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 组装阶段编排：四个展示模块，纯 Java，<b>不调用任何模型</b>。
 *
 * <p>模块四只读企业当天已发布的 Redis 方向集合，在线链路对菜品打标模型的调用次数
 * 必须为 0——这一条由 {@link DishRecommendInputFactory} 的依赖结构保证，本类不做补充调用。</p>
 */
@Slf4j
@Service
public class AnalysisAssembleService {

	private final IndicatorAssembler indicatorAssembler;

	private final ProblemAssembler problemAssembler;

	private final DietAdviceAssembler dietAdviceAssembler;

	private final DishRecommendInputFactory dishRecommendInputFactory;

	private final DishRecommendAssembler dishRecommendAssembler;

	public AnalysisAssembleService(IndicatorAssembler indicatorAssembler, ProblemAssembler problemAssembler,
			DietAdviceAssembler dietAdviceAssembler, DishRecommendInputFactory dishRecommendInputFactory,
			DishRecommendAssembler dishRecommendAssembler) {
		this.indicatorAssembler = indicatorAssembler;
		this.problemAssembler = problemAssembler;
		this.dietAdviceAssembler = dietAdviceAssembler;
		this.dishRecommendInputFactory = dishRecommendInputFactory;
		this.dishRecommendAssembler = dishRecommendAssembler;
	}

	/**
	 * 组装四个模块。
	 * @param images 全局图序列，供多文件来源标注按 page 查表定位文件
	 * @param fileCount 本次参与分析的文件数，决定多文件场景下的来源前缀
	 */
	public AnalysisModules assemble(ExtractionOutcome outcome, PageImageSequence images, int fileCount,
			String companyId, LocalDate bizDate, DegradeAccumulator degradeAccumulator) {
		if (outcome == null || images == null || degradeAccumulator == null || fileCount < 1
				|| companyId == null || companyId.length() == 0 || bizDate == null) {
			throw new IllegalArgumentException("组装阶段参数不能为空");
		}
		long startMillis = System.currentTimeMillis();
		IndicatorAssembler.Result indicatorResult = indicatorAssembler
			.assemble(outcome.getIndicators(), images, fileCount);
		ProblemAssembler.Result problemResult = problemAssembler
			.assemble(outcome.getProblems(), images, fileCount, indicatorResult);
		DietAdviceAssembler.Result dietAdviceResult = dietAdviceAssembler.assemble(outcome.getDietTags());

		boolean suppressed = degradeAccumulator.suppressDishRecommend();
		DishRecommendAssembler.Result dishRecommendResult = dishRecommendAssembler
			.assemble(dishRecommendInputFactory.create(companyId, bizDate, outcome.getDietTags(), suppressed));

		// 只记「哪些模块出来了」，不记模块里有几条内容——内容量与 taskId 拼起来就是健康画像。
		log.info("组装阶段完成，文件数={}，菜品推荐已输出={}，耗时={}ms", fileCount, dishRecommendResult != null,
				System.currentTimeMillis() - startMillis);

		// 模块四被抑制时 DishRecommendAssembler 返回 null，直接落成字段 null。
		return new AnalysisModules(indicatorResult, problemResult, dietAdviceResult, dishRecommendResult);
	}

}
