package com.example.healthreport.llm.dishtag;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 从 JAR 类路径读取并缓存 LLM-B 提示词正文；这是提示词的唯一真源。 */
@Component
public class DishTagPromptProvider {

    static final String PROMPT_RESOURCE = "prompt/dish_tag.md";

    private volatile String prompt;

    /** 返回完整提示词；首次读取失败直接中止调用，不使用空提示词降级。 */
    public String getPrompt() {
        String current = prompt;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (prompt == null) {
                prompt = loadPrompt();
            }
            return prompt;
        }
    }

    private String loadPrompt() {
        ClassPathResource resource = new ClassPathResource(PROMPT_RESOURCE);
        try (InputStream input = resource.getInputStream()) {
            String loaded = StreamUtils.copyToString(input, StandardCharsets.UTF_8);
            if (loaded.length() == 0) {
                throw new IllegalStateException("LLM-B 提示词资源为空");
            }
            return loaded;
        } catch (IOException exception) {
            throw new IllegalStateException("LLM-B 提示词资源不可读", exception);
        }
    }
}
