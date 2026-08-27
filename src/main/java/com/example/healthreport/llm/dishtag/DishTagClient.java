package com.example.healthreport.llm.dishtag;

import com.example.healthreport.infra.DishTagModelClient;
import com.example.healthreport.infra.RequestTooLargeException;
import org.springframework.stereotype.Component;

/**
 * LLM-B 离线调用入口。
 * <p>只调用一次模型，不重试；顺序是<b>先剥离思考段、再做契约校验</b>——
 * 顺序反过来的话，Schema 校验会拿到带 {@code <think>} 前缀的字符串而直接失败，
 * 真正的原因（模型在思考）就被一个含糊的「Schema 不合法」盖掉了。</p>
 */
@Component
public class DishTagClient {

    private final DishTagModelClient modelClient;
    private final DishTagPromptProvider promptProvider;
    private final DishTagContractValidator contractValidator;

    public DishTagClient(DishTagModelClient modelClient, DishTagPromptProvider promptProvider,
                         DishTagContractValidator contractValidator) {
        this.modelClient = modelClient;
        this.promptProvider = promptProvider;
        this.contractValidator = contractValidator;
    }

    /** 调用一次模型并完成剥离、Schema、覆盖与互斥校验。 */
    public DishTagOutput tag(DishTagInput input) {
        String rawContent;
        try {
            rawContent = modelClient.call(promptProvider.getPrompt(),
                    DishTagUserMessageRenderer.render(input));
        } catch (DishTagBatchRejectedException exception) {
            throw exception;
        } catch (RequestTooLargeException exception) {
            // 必须翻译成"整批作废"而不是让它裸奔上去：DishTagService 只捕获
            // DishTagBatchRejectedException 与 DishTagCallException，其余异常会中止
            // 整个夜间打标任务——一个为了隔离单批而加的防护反倒会掀掉全场。
            throw new DishTagBatchRejectedException("LLM-B 批次请求体超限，整批作废");
        } catch (RuntimeException exception) {
            throw new DishTagCallException(exception);
        }
        String responseJson = ThinkSegmentStripper.strip(rawContent);
        return contractValidator.validate(responseJson, input.getEnumKey(), input.getDishList());
    }
}
