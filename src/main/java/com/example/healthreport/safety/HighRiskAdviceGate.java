package com.example.healthreport.safety;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 饮食建议高危表述安全闸。
 * <p>命中只会让条目退出结构化内容链路，不会改写 LLM-A 的 enumKey 或生成替代建议。</p>
 */
@Component
public class HighRiskAdviceGate {

    /** 方向性饮食禁忌与特殊人群词；命中只用于抑制结构化输出，绝不改写模型给的结论。 */
    private static final List<String> HIGH_RISK_TERM_LIST = Collections.unmodifiableList(
            Arrays.asList("低蛋白", "限蛋白", "优质低蛋白", "低钾", "限钾", "低磷", "限磷",
                    "低碘", "限碘", "忌碘", "高碘", "妊娠", "孕期", "哺乳期", "儿童"));

    /**
     * 判断报告原文是否必须退出结构化内容链路。
     * <p>null 列表或 null 段按 fail-safe 抑制；空列表不命中，因为合法建议必须已有来源校验。</p>
     */
    public boolean shouldSuppress(List<String> rawTextList) {
        if (rawTextList == null) {
            return true;
        }
        for (String rawText : rawTextList) {
            if (rawText == null) {
                return true;
            }
            for (String highRiskTerm : HIGH_RISK_TERM_LIST) {
                if (rawText.contains(highRiskTerm)) {
                    return true;
                }
            }
        }
        return false;
    }
}
