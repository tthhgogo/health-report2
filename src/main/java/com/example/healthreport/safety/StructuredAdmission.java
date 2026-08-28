package com.example.healthreport.safety;

import com.example.healthreport.llm.extraction.AdviceApplicability;
import com.example.healthreport.llm.extraction.AdviceStructuredSafety;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 饮食建议能否进入结构化内容链路的确定性政策。
 *
 * <p><b>fail-closed</b>：只有「明确针对本次受检者」且「性质为常规建议」才放行，
 * 其余一律只保留报告原文、不生成食材清单、不参与菜品匹配。
 * 这与改造前的「命中黑名单才抑制」是相反的默认值——模型字段没填好时会抑制得<b>更多</b>，
 * 而不是更少。这是刻意选的安全方向，不是缺陷。</p>
 *
 * <p><b>Java 在这里不做任何推断</b>：不判断年龄、不解析代词指向、不猜「儿童」在说谁。
 * 那些是语义判断，由 LLM-A 在 {@code applicability} / {@code structuredSafety} 里给出；
 * 本类只把两个枚举按固定政策组合起来。</p>
 */
@Component
public class StructuredAdmission {

    private final HighRiskAdviceGate highRiskAdviceGate;

    public StructuredAdmission(HighRiskAdviceGate highRiskAdviceGate) {
        this.highRiskAdviceGate = highRiskAdviceGate;
    }

    /**
     * 判断一条建议是否必须退出结构化内容链路。
     *
     * @param applicability 模型判定的适用范围；null 按 fail-safe 抑制
     * @param structuredSafety 模型判定的建议性质；null 按 fail-safe 抑制
     * @param adviceQuote 模型摘出的建议原文
     * @param evidenceTextList 该建议引用的证据段原文，与 adviceQuote 一起参与词表兜底
     *                         ——只看摘出的那一句时，模型把限制词摘掉就能绕过
     */
    public boolean shouldSuppress(AdviceApplicability applicability,
                                  AdviceStructuredSafety structuredSafety, String adviceQuote,
                                  List<String> evidenceTextList) {
        if (applicability != AdviceApplicability.CURRENT_PATIENT
                || structuredSafety != AdviceStructuredSafety.NORMAL) {
            return true;
        }
        // 模型说是常规建议，但原文里写着方向性限制时仍然抑制——
        // 这是不可被模型推翻的那一半（AGENTS.md §3 的安全兜底例外）。
        return highRiskAdviceGate.shouldSuppress(adviceQuote, evidenceTextList);
    }
}
