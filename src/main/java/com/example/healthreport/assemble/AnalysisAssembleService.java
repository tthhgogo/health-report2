package com.example.healthreport.assemble;

import com.example.healthreport.assemble.dietadvice.DietAdviceAssembler;
import com.example.healthreport.assemble.dietadvice.DietAdviceInputFactory;
import com.example.healthreport.assemble.dishrecommend.DishRecommendAssembler;
import com.example.healthreport.assemble.dishrecommend.DishRecommendInputFactory;
import com.example.healthreport.assemble.indicator.IndicatorAssembler;
import com.example.healthreport.assemble.problem.ProblemAssembler;
import com.example.healthreport.cache.AnalysisModules;
import com.example.healthreport.llm.extraction.ValidatedExtractionOutput;
import com.example.healthreport.task.DegradeAccumulator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 组装阶段编排：四个展示模块，纯 Java，<b>不调用任何模型</b>。
 *
 * <p>
 * 模块四只读企业当天已发布的 Redis 方向集合，在线链路对 LLM-B 的调用次数 必须为 0——这一条由
 * {@link DishRecommendInputFactory} 的依赖结构保证，本类不做补充调用。
 * </p>
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

	public AnalysisAssembleService(IndicatorAssembler indicatorAssembler, ProblemAssembler problemAssembler,
			DietAdviceInputFactory dietAdviceInputFactory, DietAdviceAssembler dietAdviceAssembler,
			DishRecommendInputFactory dishRecommendInputFactory, DishRecommendAssembler dishRecommendAssembler) {
		this.indicatorAssembler = indicatorAssembler;
		this.problemAssembler = problemAssembler;
		this.dietAdviceInputFactory = dietAdviceInputFactory;
		this.dietAdviceAssembler = dietAdviceAssembler;
		this.dishRecommendInputFactory = dishRecommendInputFactory;
		this.dishRecommendAssembler = dishRecommendAssembler;
	}

	/**
	 * 组装四个模块。
	 * @param fileCount 本次实际参与抽取的文件数，决定多文件场景下的来源标注
	 * @return 四个模块各自一个 Result 元素；降级裁剪由
	 * {@link com.example.healthreport.cache.AnalysisResult#create} 统一执行，本类不重复
	 */
	public AnalysisModules assemble(ValidatedExtractionOutput output, int fileCount, String companyId,
			LocalDate bizDate, DegradeAccumulator degradeAccumulator) {
		if (output == null || degradeAccumulator == null || fileCount < 1 || companyId == null
				|| companyId.length() == 0 || bizDate == null) {
			throw new IllegalArgumentException("组装阶段参数不能为空");
		}
		long startMillis = System.currentTimeMillis();
		IndicatorAssembler.Result indicatorResult = indicatorAssembler.assemble(output, fileCount);
		ProblemAssembler.Result problemResult = problemAssembler.assemble(output, fileCount);
		DietAdviceAssembler.Result dietAdviceResult = dietAdviceAssembler
			.assemble(dietAdviceInputFactory.create(output, fileCount));
		DishRecommendAssembler.Result dishRecommendResult = assembleDishRecommend(companyId, bizDate, output,
				degradeAccumulator);

		// 与抽取阶段同一条口径：只记「哪些模块出来了」，不记模块里有几条内容。
		// 模块四有没有出来是用户直接能看见的差异，出问题时第一个要确认的就是它。
		log.info("组装阶段完成，文件数={}，饮食建议已输出={}，菜品推荐已输出={}，耗时={}ms", fileCount, dietAdviceResult != null,
				dishRecommendResult != null, System.currentTimeMillis() - startMillis);

		// 模块四被抑制时 DishRecommendAssembler 返回 null，直接落成字段 null。
		return new AnalysisModules(indicatorResult, problemResult, dietAdviceResult, dishRecommendResult);
	}

	/**
	 * 组装模块四；抑制时输入工厂不会访问 Redis。
	 */
	private DishRecommendAssembler.Result assembleDishRecommend(String companyId, LocalDate bizDate,
			ValidatedExtractionOutput output, DegradeAccumulator degradeAccumulator) {
		// suppressDietAdvice 会连模块四一起清空（§4.1.1），所以它同样算作抑制。
		boolean suppressed = degradeAccumulator.suppressDietAdvice() || degradeAccumulator.suppressDishRecommend();
		log.info("模块四输入就绪，业务日={}，已抑制={}", bizDate, suppressed);
		return dishRecommendAssembler
			.assemble(dishRecommendInputFactory.create(companyId, bizDate, output, suppressed));
	}

}
