package com.example.healthreport.llm.dishtag;

/**
 * 从 qwen3 的 {@code content} 里剥离思考段，取出真正的 JSON。
 *
 * <p>该模型把思考过程<b>内联在 {@code content} 里</b>，不是单独的 {@code reasoning_content}
 * 字段，形如 {@code <think>…</think>\n\n{ JSON }}。因此 {@code content} 不能直接解析。</p>
 *
 * <p><b>为什么规则必须这么严。</b> 思考段里极常出现示例 JSON——模型会在里面自言自语地
 * 试写输出格式。任何「找第一个 <code>{</code> 到最后一个 <code>}</code>」式的宽松提取，
 * 都会把<b>示例</b>当成<b>结果</b>：它 Schema 完全合法、覆盖与互斥校验全过、
 * 没有任何一层会报错，最后写进库的是模型的草稿。
 * 这是本链路唯一会产生静默错误数据的地方，所以这里只认锚点、不做任何猜测式修复。</p>
 *
 * <p><b>剥离逻辑无条件保留。</b> qwen3 支持关闭思考，但即使确认网关透传该参数并关掉了，
 * 本类也必须留着——一个部署开关不该成为解析正确性的前提，它被谁改回去都不会有编译错误。</p>
 */
public final class ThinkSegmentStripper {

    static final String OPEN_TAG = "<think>";
    static final String CLOSE_TAG = "</think>";

    private ThinkSegmentStripper() {
    }

    /**
     * 取出 {@code content} 中的 JSON 正文。
     *
     * <pre>
     * ① 以 &lt;think&gt; 开头 → 取【最后一个】&lt;/think&gt; 之后的部分
     * ② 既无 &lt;think&gt; 也无 &lt;/think&gt; → 整体返回
     * ③ 其余任何形态 → 抛异常，由调用方整批作废
     * </pre>
     *
     * @throws DishTagBatchRejectedException 形态不属于 ①②，或剥离后为空
     */
    public static String strip(String content) {
        if (content == null) {
            throw new DishTagBatchRejectedException("LLM-B 响应 content 为空");
        }
        String trimmed = content.trim();
        boolean hasOpenTag = trimmed.contains(OPEN_TAG);
        boolean hasCloseTag = trimmed.contains(CLOSE_TAG);

        if (!hasOpenTag && !hasCloseTag) {
            return requireNonEmpty(trimmed);
        }
        if (!trimmed.startsWith(OPEN_TAG) || !hasCloseTag) {
            // 只有闭标签、只有开标签、或开标签不在开头：都说明响应结构不是预期的那一种。
            // 不猜、不修复——猜错的代价是把思考里的示例当成结果写进库。
            throw new DishTagBatchRejectedException("LLM-B 响应思考段结构异常，整批作废");
        }
        // 取【最后一个】闭标签：思考段里可能出现被模型引用的 </think> 字面量。
        int jsonStart = trimmed.lastIndexOf(CLOSE_TAG) + CLOSE_TAG.length();
        return requireNonEmpty(trimmed.substring(jsonStart).trim());
    }

    private static String requireNonEmpty(String value) {
        if (value.isEmpty()) {
            throw new DishTagBatchRejectedException("LLM-B 响应剥离思考段后为空，整批作废");
        }
        return value;
    }
}
