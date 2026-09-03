package com.example.healthreport.llm.extraction;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/**
 * 从 JAR 类路径读取并缓存三份生产提示词正文。
 * <p>生产加载器显式拒绝文件名含 {@code probe} 的资源；
 * 读取失败直接中止启动或调用，不使用空提示词降级。</p>
 */
@Component
public class ExtractionPromptProvider {

    private final Map<ExtractionCall, String> promptMap =
            new EnumMap<ExtractionCall, String>(ExtractionCall.class);

    /** 返回指定调用的完整提示词。 */
    public String getPrompt(ExtractionCall call) {
        if (call == null) {
            throw new IllegalArgumentException("调用类型不能为空");
        }
        String current = promptMap.get(call);
        if (current != null) {
            return current;
        }
        synchronized (this) {
            String loaded = promptMap.get(call);
            if (loaded == null) {
                loaded = loadPrompt(call.getPromptResource());
                promptMap.put(call, loaded);
            }
            return loaded;
        }
    }

    private String loadPrompt(String resourcePath) {
        if (resourcePath == null || resourcePath.toLowerCase().contains("probe")) {
            throw new IllegalStateException("生产加载器拒绝 probe 资源：" + resourcePath);
        }
        ClassPathResource resource = new ClassPathResource(resourcePath);
        try (InputStream input = resource.getInputStream()) {
            String loaded = StreamUtils.copyToString(input, StandardCharsets.UTF_8);
            if (loaded.length() == 0) {
                throw new IllegalStateException("提示词资源为空：" + resourcePath);
            }
            return loaded;
        } catch (IOException exception) {
            throw new IllegalStateException("提示词资源不可读：" + resourcePath, exception);
        }
    }
}
