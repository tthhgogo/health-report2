package com.example.healthreport.safety;

import com.example.healthreport.parse.segment.TextNormalizer;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 饮食建议高危表述安全闸。
 *
 * <p><b>本闸只是兜底，不是主判据。</b> 「这条建议给不给这个人」「是不是面向特殊人群」
 * 由 LLM-A 通过 {@code applicability} / {@code structuredSafety} 两个字段判断，
 * 后端按 {@link StructuredAdmission} 执行确定性政策。本闸负责的是
 * <b>不可被模型推翻的那一半</b>：模型说 NORMAL、但建议原文里明明写着方向性限制时，仍然抑制。</p>
 *
 * <p><b>词表只保留语义完整的方向性限制</b>（2026-08-28 修订）。
 * 原先表里还有「妊娠 / 孕期 / 哺乳期 / 儿童」四个<b>人群裸词</b>，已移除——
 * 它们出现在文本里根本不表示这条建议受限，可能在说受检者、家属、既往史，也可能是科普。
 * 而后端只能做字面包含，分辨不了指向。</p>
 *
 * <p><b>移除的直接起因</b>：实测报告把「(孕妇和14岁以下儿童除外)」的后半截
 * 与「请您戒烟忌酒，低脂、低糖饮食」切进了同一个 PDF 绘制单元。
 * 按整块扫描时「儿童」命中，4 条饮食建议与 1 条营养补充被一起抑制，
 * 菜品推荐模块随之整个清空，而全程没有任何异常日志。
 * 现在匹配对象也已从整块收敛到 {@code adviceQuote} 这一句（见 §4.4）。</p>
 *
 * <p><b>但只扫 {@code adviceQuote} 会被摘句绕过</b>（2026-08-28 补）：那一句由模型自己摘，
 * 而回切只要求它是证据原文的子串。原文「建议低蛋白、低脂饮食」摘成「低脂饮食」并标 NORMAL，
 * 兜底就一次也没触发过。因此除了摘出的那一句，<b>证据段原文也要扫</b>——
 * 词表里剩下的全是语义完整的方向性限制词，扫原文不会再现 F3 那种人群裸词误杀。
 * 代价是同段里另一条建议带方向性限制时会一起抑制，这是刻意选的方向：宁可少给结构化内容。</p>
 */
@Component
public class HighRiskAdviceGate {

    /**
     * 方向性饮食限制词；命中只用于抑制结构化输出，绝不改写模型给的结论。
     *
     * <p>这些是<b>语义完整的限制表述</b>：无论建议给谁，「低蛋白」「限钾」都必须由医嘱个体化，
     * 不能由系统配上食材清单去推荐。与人群名词不同，它们的字面含义就是限制本身，
     * 误报风险低得多。</p>
     */
    private static final List<String> HIGH_RISK_TERM_LIST = Collections.unmodifiableList(
            Arrays.asList("低蛋白", "限蛋白", "优质低蛋白", "低钾", "限钾", "低磷", "限磷",
                    "低碘", "限碘", "忌碘", "高碘"));

    private final TextNormalizer textNormalizer;

    public HighRiskAdviceGate(TextNormalizer textNormalizer) {
        if (textNormalizer == null) {
            throw new IllegalArgumentException("高危表述闸依赖不能为空");
        }
        this.textNormalizer = textNormalizer;
    }

    /**
     * 判断一条建议是否必须退出结构化内容链路。
     *
     * <p>两个入参都要扫：模型摘出的那一句，以及它所引用的证据段原文。
     * 只扫前者会被摘句绕过，只扫后者又拿不到跨段摘录的情况，两个一起才是完整的兜底面。</p>
     *
     * @param adviceQuote 模型摘出的建议原文；null 按 fail-safe 抑制
     * @param evidenceTextList 该建议引用的证据段原文；null 按 fail-safe 抑制，空表表示无证据可扫
     */
    public boolean shouldSuppress(String adviceQuote, List<String> evidenceTextList) {
        if (adviceQuote == null || evidenceTextList == null) {
            return true;
        }
        if (containsHighRiskTerm(adviceQuote)) {
            return true;
        }
        for (String evidenceText : evidenceTextList) {
            if (evidenceText != null && containsHighRiskTerm(evidenceText)) {
                return true;
            }
        }
        return false;
    }

    /** 先归一化再做字面包含：不可见字符会让「低(零宽空格)蛋白」这类文本躲过兜底。 */
    private boolean containsHighRiskTerm(String text) {
        String normalizedText = textNormalizer.normalize(text).getNormalizedText();
        for (String highRiskTerm : HIGH_RISK_TERM_LIST) {
            if (normalizedText.contains(highRiskTerm)) {
                return true;
            }
        }
        return false;
    }
}
