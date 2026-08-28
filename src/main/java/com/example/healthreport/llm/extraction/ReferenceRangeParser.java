package com.example.healthreport.llm.extraction;

import com.example.healthreport.parse.segment.TextNormalizer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 参考范围原文的确定性解析，用于核验 LLM-A 拆出的上下界确实写在报告上。
 *
 * <p><b>为什么 Java 必须自己解析一次</b>：只核验「边界数字是 refRange 的子串」拦不住三种绕过
 * ——① 只给一个边界（{@code 4.0~10.0} 报成「下界 4.0、上界不设限」，于是 12.5 也算正常）；
 * ② 拿子串伪造边界（{@code 4.0} 是 {@code 14.0~20.0} 的子串）；
 * ③ 篡改开闭（{@code <3.0} 报成闭区间，于是 3.0 也算正常）。
 * 三种都会让系统对着一个报告没写过的范围宣布「在参考范围内」。</p>
 *
 * <p><b>职责边界没有变</b>：哪套人群范围适用、单位是否对齐，仍然由 LLM-A 判断并选择；
 * 本类不挑范围、不换算单位、不做医疗语义推断，只回答一个可穷举的问题——
 * 「模型报上来的这组边界，是不是 refRange 里逐字写着的某一个区间」。
 * 输入可穷举、结果可复现，属 {@code AGENTS.md} §3 允许 Java 承担的确定性字符串与数值判断。</p>
 *
 * <p><b>只认下面这几种写法，其余一律解析不出（fail-closed，指标随之不展示）</b>：</p>
 * <ul>
 * <li>闭区间：{@code 4.0-10.0}、{@code 4.0~10.0}、{@code 4.0—10.0}、{@code 4.0至10.0}</li>
 * <li>单边上界：{@code <3.0}（开）、{@code ≤3.0} 与 {@code <=3.0}（闭）</li>
 * <li>单边下界：{@code >1.0}（开）、{@code ≥1.0} 与 {@code >=1.0}（闭）</li>
 * </ul>
 * <p>一段 refRange 里写了多套范围（{@code 男4.0-5.5 女3.5-5.0}）时全部解出，
 * 模型选中哪一套都能核验；<b>但绝不允许它报一套没写在里面的</b>。</p>
 */
@Component
public class ReferenceRangeParser {

    /** 闭区间分隔符：半角连字符、波浪号、两种破折号与「至」。全角形式已由 NFKC 归一成半角。 */
    private static final String INTERVAL_SEPARATORS = "\\-~—–至";

    /** 十进制数字，不含符号：带号的写法一律解析不出，宁可不展示。 */
    private static final String DECIMAL = "\\d+(?:\\.\\d+)?";

    /** 闭区间：两个十进制数字被分隔符连接，上下界都闭合。 */
    private static final Pattern INTERVAL_PATTERN = Pattern.compile(
            "(" + DECIMAL + ")[" + INTERVAL_SEPARATORS + "](" + DECIMAL + ")");

    /** 单边区间：比较符加一个十进制数字；带等号即闭合。 */
    private static final Pattern BOUNDED_PATTERN = Pattern.compile(
            "(<=|>=|≤|≥|≦|≧|<|>)(" + DECIMAL + ")");

    private final TextNormalizer textNormalizer;

    public ReferenceRangeParser(TextNormalizer textNormalizer) {
        if (textNormalizer == null) {
            throw new IllegalArgumentException("参考范围解析依赖不能为空");
        }
        this.textNormalizer = textNormalizer;
    }

    /**
     * 判断模型声明的一组边界是否确实写在参考范围原文里。
     *
     * <p>四个要素<b>全部</b>要对上：下界数值、下界开闭、上界数值、上界开闭。
     * 数值用 {@link BigDecimal#compareTo} 比较，{@code 4.0} 与 {@code 4.00} 判等
     * ——它们是同一个数的两种合法写法；而 {@code 40} 与 {@code 4.0} 不是，会被拒。</p>
     *
     * @param refRange 已通过来源回切的参考范围原文
     * @return 原文里能找到完全一致的区间时为 true；解析不出或对不上时为 false
     */
    public boolean admits(String refRange, BigDecimal lowerBound, boolean lowerInclusive,
                          BigDecimal upperBound, boolean upperInclusive) {
        if (refRange == null) {
            return false;
        }
        for (Range range : parse(refRange)) {
            if (range.matches(lowerBound, lowerInclusive, upperBound, upperInclusive)) {
                return true;
            }
        }
        return false;
    }

    /** 解析出参考范围原文里写着的全部区间；一个都认不出时返回空表。 */
    public List<Range> parse(String refRange) {
        if (refRange == null) {
            return Collections.emptyList();
        }
        String normalized = textNormalizer.normalize(refRange).getNormalizedText()
                .replaceAll("\\s+", "");
        List<Range> rangeList = new ArrayList<Range>(2);
        Matcher intervalMatcher = INTERVAL_PATTERN.matcher(normalized);
        while (intervalMatcher.find()) {
            BigDecimal lower = new BigDecimal(intervalMatcher.group(1));
            BigDecimal upper = new BigDecimal(intervalMatcher.group(2));
            if (lower.compareTo(upper) <= 0) {
                rangeList.add(new Range(lower, true, upper, true));
            }
        }
        Matcher boundedMatcher = BOUNDED_PATTERN.matcher(normalized);
        while (boundedMatcher.find()) {
            String operator = boundedMatcher.group(1);
            BigDecimal bound = new BigDecimal(boundedMatcher.group(2));
            boolean inclusive = operator.length() > 1 || "≤".equals(operator)
                    || "≥".equals(operator) || "≦".equals(operator) || "≧".equals(operator);
            if (operator.startsWith("<") || "≤".equals(operator) || "≦".equals(operator)) {
                rangeList.add(new Range(null, false, bound, inclusive));
            } else {
                rangeList.add(new Range(bound, inclusive, null, false));
            }
        }
        return Collections.unmodifiableList(rangeList);
    }

    /** 参考范围原文里写着的一个区间；不设限的一侧为 null。 */
    public static final class Range {

        private final BigDecimal lowerBound;
        private final boolean lowerInclusive;
        private final BigDecimal upperBound;
        private final boolean upperInclusive;

        Range(BigDecimal lowerBound, boolean lowerInclusive, BigDecimal upperBound,
              boolean upperInclusive) {
            this.lowerBound = lowerBound;
            this.lowerInclusive = lowerInclusive;
            this.upperBound = upperBound;
            this.upperInclusive = upperInclusive;
        }

        /** 四个要素全部一致才算同一个区间；不设限的一侧要求对方也不设限。 */
        boolean matches(BigDecimal otherLower, boolean otherLowerInclusive, BigDecimal otherUpper,
                        boolean otherUpperInclusive) {
            return sameBound(lowerBound, otherLower)
                    && (lowerBound == null || lowerInclusive == otherLowerInclusive)
                    && sameBound(upperBound, otherUpper)
                    && (upperBound == null || upperInclusive == otherUpperInclusive);
        }

        private boolean sameBound(BigDecimal left, BigDecimal right) {
            if (left == null || right == null) {
                return left == null && right == null;
            }
            return left.compareTo(right) == 0;
        }
    }
}
