package com.example.healthreport.infra;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM-B 直连接入参数。
 * <p>模型标识不在这里：它是 {@code llm.model-version-dishtag}，同时也是 {@code tagHash}
 * 的输入（§9.5.1），由 {@code DishTagProperties} 持有——<b>全案只有那一处真源</b>。
 * 在这里再放一份就是把已经消掉的双真源又请回来。</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "llm.dishtag")
public class DishTagConnectionProperties {

    /** 网关根地址，必填，与 LLM-A、OCR 是同一个网关但各配各的。 */
    private String baseUrl;

    /** OpenAI 兼容的对话补全路径。 */
    private String chatCompletionsPath = "/v1/chat/completions";

    /** 访问凭证，必填，只允许由环境变量注入。 */
    private String apiKey;

    /**
     * 输出 token 上限。
     * <p>请求已显式关闭深度思考；仍保留足以容纳结构化 JSON 的余量，并兼容网关忽略
     * {@code enable_thinking=false} 后返回思考段的异常情况。任何截断都按整批作废处理。</p>
     */
    private int maxTokens = 8192;

    /**
     * 请求体上限。
     * <p>与 LLM-A / OCR 同样用有界缓冲，理由却不同：那两条的载荷本身就大（Base64 图像），
     * 这条的载荷是文本，正常一批约 17KB（提示词约 12.7KB + 40 道菜的渲染）。
     * 1MiB 留了约 60 倍余量，<b>它防的不是常态而是上游数据异常</b>——
     * {@code Dish} 与 {@code DishIngredient} 只校验非空，菜名和食材名长度、
     * 单菜食材条数都没有上限，一次数据迁移事故就能造出巨大的批次。</p>
     * <p>不设上限时代价会翻倍：{@code ByteArrayOutputStream} 扩容按倍增、峰值约 2 倍体积，
     * 加上 {@code bufferRequestBody=true} 又复制一份，共约 3 倍，落在与 Web 层共享的堆上。
     * 而这样的请求发出去也会被模型按上下文长度拒掉——提前失败更快也更省。</p>
     */
    private long maxRequestBodyBytes = 1L << 20;

    /** 响应体有界读取上限。 */
    private long maxResponseBodyBytes = 4L << 20;

    /** 建连超时毫秒。 */
    private int connectTimeoutMillis = 10000;

    /** 读超时毫秒；离线批量跑，且思考段会拉长单次耗时，比在线链路宽松。 */
    private int readTimeoutMillis = 300000;
}
