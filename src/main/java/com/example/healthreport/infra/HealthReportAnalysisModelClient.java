package com.example.healthreport.infra;

import com.example.healthreport.llm.extraction.ExtractionCallInput;

/**
 * 体检报告分析模型直连接口。
 * <p>只返回模型 content 原文；不做业务 Schema 校验、医疗语义处理或重试。</p>
 */
public interface HealthReportAnalysisModelClient {

    /** 发出恰好一次模型请求并返回 content。 */
    String call(ExtractionCallInput input);
}
