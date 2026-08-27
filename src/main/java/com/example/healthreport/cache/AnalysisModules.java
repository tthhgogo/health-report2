package com.example.healthreport.cache;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/**
 * 四个展示模块的结果容器。
 *
 * <p><b>每个模块是一个对象，不是列表</b>：四个组装器各产出恰好一个 {@code Result}，
 * 用长度恒为 1 的数组包起来在契约上表达不出「只能有一个」，将来有人往里塞第二个元素
 * 也不会有任何东西拦住。模块被抑制或未产出时字段为 {@code null}，
 * 与 {@code AnalysisResult} 上已有的 {@code suppressDietAdvice} /
 * {@code suppressDishRecommend} 布尔位对齐。</p>
 *
 * <p><b>已知缺口</b>：字段类型仍是 {@code Object}，类型系统对「模块 DTO 里混进姓名、性别或
 * 完整 OCR 原文」提供不了任何约束，R48 目前的唯一防线是
 * {@code TaskResultCacheTest#serializedAssembledModulesShouldExcludeIdentityAndCompleteSourcePayloadFields}
 * ——它用四个组装器的真实产物序列化后做哨兵串断言，是有效的，但属于测试级而非结构级保障。</p>
 *
 * <p>换成各模块正式 {@code Result} 类型是正确方向，但四个 Result 及其十余个嵌套 DTO
 * 都是私有构造器且没有 Jackson creator：<b>改成强类型后能写不能读</b>，结果接口会在
 * 反序列化时抛 {@code InvalidDefinitionException}。补齐 creator 是一次跨四个模块的
 * 改动，需要单独排期与评审，不能顺手改一半——半途而废比现状更糟。</p>
 */
@Getter
public class AnalysisModules {

    private final Object healthIndicators;
    private final Object healthProblems;
    private final Object dietAdvice;
    private final Object dishRecommendations;

    /** 创建可由 Jackson 反序列化的四模块容器。 */
    @JsonCreator
    public AnalysisModules(@JsonProperty("healthIndicators") Object healthIndicators,
                           @JsonProperty("healthProblems") Object healthProblems,
                           @JsonProperty("dietAdvice") Object dietAdvice,
                           @JsonProperty("dishRecommendations") Object dishRecommendations) {
        this.healthIndicators = healthIndicators;
        this.healthProblems = healthProblems;
        this.dietAdvice = dietAdvice;
        this.dishRecommendations = dishRecommendations;
    }

    /** 创建不伪造任何分析内容的占位模块结构。 */
    public static AnalysisModules empty() {
        return new AnalysisModules(null, null, null, null);
    }

    /** 页数或批次不完整时清空模块三、四，保留已验证的模块一、二。 */
    public AnalysisModules withoutDietAdviceAndDishRecommend() {
        return new AnalysisModules(healthIndicators, healthProblems, null, null);
    }

    /** 仅过敏抽取可疑时清空模块四。 */
    public AnalysisModules withoutDishRecommend() {
        return new AnalysisModules(healthIndicators, healthProblems, dietAdvice, null);
    }
}
