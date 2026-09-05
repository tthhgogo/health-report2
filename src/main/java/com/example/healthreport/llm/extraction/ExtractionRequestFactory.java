package com.example.healthreport.llm.extraction;

import com.example.healthreport.render.PageImageSequence;
import org.springframework.stereotype.Component;

/**
 * 组装单次调用输入：System 取生产提示词正文，User 只含页数与通用任务说明。
 * <p>{@code taskId / userId / companyId / display_name}、菜品数据与前序阶段结果
 * 全部不进模型请求（设计方案 §4.2.1）。</p>
 */
@Component
public class ExtractionRequestFactory {

    private final ExtractionPromptProvider promptProvider;

    public ExtractionRequestFactory(ExtractionPromptProvider promptProvider) {
        this.promptProvider = promptProvider;
    }

    /** 为指定调用组装输入；三次调用共用同一 {@code images}。 */
    public ExtractionCallInput create(ExtractionCall call, PageImageSequence images) {
        if (call == null || images == null) {
            throw new IllegalArgumentException("请求组装参数不能为空");
        }
        String systemPrompt = promptBody(promptProvider.getPrompt(call));
        String userText = "这是一份体检报告的全部页面图像，共 " + images.size()
                + " 张，按报告顺序给出。\n第 1 张是第 1 页，依此类推；输出里的 page 字段填的就是这个序号。\n"
                + "只输出提示词约定的那一个 JSON 对象。";
        return new ExtractionCallInput(call, systemPrompt, userText, images.getPageList());
    }

    /**
     * 截取提示词的 System 正文：文件头（标题、版本说明）不发给模型。
     * <p>与探针的加载口径一致：取「\n## System」之后的内容；找不到分节标记时整文发送。</p>
     */
    static String promptBody(String promptFile) {
        int marker = promptFile.indexOf("\n## System");
        if (marker < 0) {
            return promptFile;
        }
        int bodyStart = promptFile.indexOf('\n', marker + 1);
        return bodyStart < 0 ? promptFile : promptFile.substring(bodyStart + 1);
    }
}
