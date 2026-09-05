package com.example.healthreport.cache;

import com.example.healthreport.assemble.dietadvice.DietAdviceAssembler;
import com.example.healthreport.assemble.dishrecommend.DishRecommendAssembler;
import com.example.healthreport.assemble.indicator.IndicatorAssembler;
import com.example.healthreport.assemble.problem.ProblemAssembler;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;

/**
 * 四个展示模块的结果容器。
 *
 * <p><b>每个模块是一个对象，不是列表</b>：四个组装器各产出恰好一个 {@code Result}，
 * 用长度恒为 1 的数组包起来在契约上表达不出「只能有一个」，将来有人往里塞第二个元素
 * 也不会有任何东西拦住。模块被抑制或未产出时字段为 {@code null}，
 * 与 {@code AnalysisResult} 上的 {@code suppressDishRecommend} 布尔位对齐。</p>
 *
 * <p><b>强类型契约</b>：四个字段即各组装器的 {@code Result}，Jackson 往返由
 * {@code AnalysisResultJacksonContractTest} 守护。读侧保持严格反序列化——同一
 * {@code ResultSchemaVersion} 的 key 内出现未知字段说明有人改了结构忘 bump，
 * 必须报错显形而不是静默丢字段。改任何模块 Result 的结构必须 bump
 * {@code ResultSchemaVersion}。cache 与 assemble 的包互依是本裁决接受的代价。</p>
 */
@ApiModel(value = "AnalysisModules", description = "四个展示模块的结果容器；每个模块是一个对象，被抑制或未产出时为 null")
@Getter
public class AnalysisModules {

    @ApiModelProperty(value = "模块一：健康指标")
    private final IndicatorAssembler.Result healthIndicators;

    @ApiModelProperty(value = "模块二：健康问题")
    private final ProblemAssembler.Result healthProblems;

    @ApiModelProperty(value = "模块三：饮食建议")
    private final DietAdviceAssembler.Result dietAdvice;

    @ApiModelProperty(value = "模块四：食堂菜品推荐；被抑制时为 null")
    private final DishRecommendAssembler.Result dishRecommendations;

    /** 创建可由 Jackson 反序列化的四模块容器。 */
    @JsonCreator
    public AnalysisModules(@JsonProperty("healthIndicators") IndicatorAssembler.Result healthIndicators,
                           @JsonProperty("healthProblems") ProblemAssembler.Result healthProblems,
                           @JsonProperty("dietAdvice") DietAdviceAssembler.Result dietAdvice,
                           @JsonProperty("dishRecommendations") DishRecommendAssembler.Result dishRecommendations) {
        this.healthIndicators = healthIndicators;
        this.healthProblems = healthProblems;
        this.dietAdvice = dietAdvice;
        this.dishRecommendations = dishRecommendations;
    }

    /** 创建不伪造任何分析内容的占位模块结构。 */
    public static AnalysisModules empty() {
        return new AnalysisModules(null, null, null, null);
    }

    /** 饮食标签发生剔除时清空模块四，模块三保留其余已校验条目。 */
    public AnalysisModules withoutDishRecommend() {
        return new AnalysisModules(healthIndicators, healthProblems, dietAdvice, null);
    }
}
