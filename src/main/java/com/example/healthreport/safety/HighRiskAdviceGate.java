package com.example.healthreport.safety;

import com.example.healthreport.support.text.TextNormalizer;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 饮食建议高危表述安全闸（设计方案 §7.3）——结构化链路仅剩的一层。
 *
 * <p><b>只扫模型摘出的 {@code quote} 那一句（≤100 字），不扫 {@code rawText}</b>（R15j）。
 * 旧契约里模型侧的 {@code applicability} / {@code structuredSafety} 两个字段已删除，
 * 因此「建议家属同查」「孕妇和14岁以下儿童除外」这类指向不是受检者的文本，
 * 现在只靠提示词的抽取范围规则拦截，<b>没有机械兜底</b>；原文含高危词而模型摘句时
 * 摘掉了它的情况同样拦不住（R15h）——两者都是设计方案 §11 登记的已接受盲区。</p>
 *
 * <p><b>词表只保留语义完整的方向性限制</b>，不含「妊娠 / 孕期 / 哺乳期 / 儿童」人群裸词——
 * 它们出现在文本里不表示这条建议受限（R15b 的误杀现场）。
 * 命中只用于抑制结构化输出（该条按 OTHER 路径处理），<b>绝不改写 enumKey</b>；
 * 作用范围仅限营养补充与饮食注意，不含过敏原（2026-08-26 产品确认）。</p>
 */
@Component
public class HighRiskAdviceGate {

    /**
     * 方向性饮食限制词。「低蛋白」「限钾」的字面含义就是限制本身，
     * 必须由医嘱个体化，不能由系统配上食材清单去推荐。
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
     * @param quote 模型摘出的建议原文那一句；null 或空白按 fail-safe 抑制
     */
    public boolean shouldSuppress(String quote) {
        if (quote == null || quote.trim().isEmpty()) {
            return true;
        }
        // 先归一化再做字面包含：不可见字符会让「低(零宽空格)蛋白」躲过兜底。
        String normalizedText = textNormalizer.normalize(quote);
        for (String highRiskTerm : HIGH_RISK_TERM_LIST) {
            if (normalizedText.contains(highRiskTerm)) {
                return true;
            }
        }
        return false;
    }
}
