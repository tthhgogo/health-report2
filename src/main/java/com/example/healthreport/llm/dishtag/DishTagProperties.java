package com.example.healthreport.llm.dishtag;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** LLM-B 离线打标部署参数；改模型版本会触发全量标签哈希失效。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "llm")
public class DishTagProperties {

    /**
     * LLM-B 直连请求里的 model 字段，<b>全案唯一真源</b>。
     * <p>字段名要能被 {@code llm.model-version-dishtag} 松散绑定命中——绑不上会静默取空串，
     * 而这个值进 {@code tagHash}，换模型不会触发重打标。改直连之前它还要与 Dify DSL 的
     * 环境变量保持一致，那处双真源已随 Dify 一并消失。</p>
     */
    private String modelVersionDishtag = "";
}
