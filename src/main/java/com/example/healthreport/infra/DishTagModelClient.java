package com.example.healthreport.infra;

/**
 * LLM-B 直连模型接口。
 * <p>只返回模型 content 原文（网关忽略关闭参数时可能含未剥离的思考段）；不做业务 Schema 校验、
 * 不剥离思考段、不重试——剥离在 {@code ThinkSegmentStripper}，校验在
 * {@code DishTagContractValidator}。</p>
 */
public interface DishTagModelClient {

    /**
     * 发出恰好一次模型请求。
     *
     * @param systemPrompt 提示词正文，真源是 {@code prompt/dish_tag.md}
     * @param userMessage  本批菜品与维度，由 Java 渲染
     * @return {@code choices[0].message.content} 原文
     */
    String call(String systemPrompt, String userMessage);
}
