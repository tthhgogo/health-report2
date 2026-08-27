package com.example.healthreport.infra;

import com.example.healthreport.llm.extraction.ExtractionBatchInput;

/**
 * LLM-A 直连模型接口。
 * <p>只返回模型 content 原文；不做业务 Schema 校验、医疗语义处理或重试。</p>
 */
public interface ExtractionModelClient {

    /** 发出恰好一次模型请求并返回 content。 */
    String call(ExtractionBatchInput input);
}
