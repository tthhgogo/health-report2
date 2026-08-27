package com.example.healthreport.llm.dishtag;

/** LLM-B 单次远程调用失败；离线编排只作废当前批次且不得重试。 */
public class DishTagCallException extends RuntimeException {

    /** 包装远程异常，消息不包含请求或响应正文。 */
    public DishTagCallException(Throwable cause) {
        super("LLM-B远程调用失败", cause);
    }
}
