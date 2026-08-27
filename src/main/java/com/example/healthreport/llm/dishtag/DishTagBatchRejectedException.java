package com.example.healthreport.llm.dishtag;

/** LLM-B 批次契约失败；调用方必须整批作废且不得重试。 */
public class DishTagBatchRejectedException extends RuntimeException {

    /** 创建不携带模型正文的安全异常。 */
    public DishTagBatchRejectedException(String message) {
        super(message);
    }
}
