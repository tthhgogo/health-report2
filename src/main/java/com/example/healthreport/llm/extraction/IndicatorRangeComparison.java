package com.example.healthreport.llm.extraction;

import lombok.Getter;

import java.math.BigDecimal;

/**
 * 已由 LLM-A 拆解成十进制的参考范围比较条件。
 *
 * <p><b>职责边界</b>：哪个参考范围适用、单位是否对齐、性别年龄方法学怎么选，
 * 全部由 LLM-A 判断并拆成上下界；本类只承载拆好的数，
 * 由 {@link #inRange()} 做纯粹的 {@link BigDecimal#compareTo} 比较，不做任何单位换算。</p>
 *
 * <p>拆好的这组数<b>是否真的写在报告上</b>由 {@link ReferenceRangeParser} 在准入时核验
 * ——本类只管比大小，不承担防伪。</p>
 *
 * <p><b>用 BigDecimal 不用 double</b>：边界值上 {@code 1.10} 与 {@code 1.1} 必须判等，
 * 而双精度在这类比较上会出分歧。同理只用 {@code compareTo} 不用 {@code equals}
 * ——后者把标度也算进相等条件，{@code 1.10.equals(1.1)} 是 false。</p>
 */
@Getter
public final class IndicatorRangeComparison {

    private final BigDecimal measuredValue;

    /** 下界；单边区间（如 {@code <3.0}）时为 null，表示下方不设限。 */
    private final BigDecimal lowerBound;

    /** 下界是否闭合：{@code a~b} 为 true，{@code >a} 为 false。 */
    private final boolean lowerInclusive;

    /** 上界；单边区间（如 {@code >1.0}）时为 null，表示上方不设限。 */
    private final BigDecimal upperBound;

    /** 上界是否闭合：{@code a~b} 为 true，{@code <b} 为 false。 */
    private final boolean upperInclusive;

    public IndicatorRangeComparison(BigDecimal measuredValue, BigDecimal lowerBound,
                                    boolean lowerInclusive, BigDecimal upperBound,
                                    boolean upperInclusive) {
        if (measuredValue == null) {
            throw new IllegalArgumentException("检查结果数值不能为空");
        }
        if (lowerBound == null && upperBound == null) {
            // 上下界都不设限时「落在范围内」恒真，等于没有准入条件。
            throw new IllegalArgumentException("参考范围至少要有一个边界");
        }
        if (lowerBound != null && upperBound != null && lowerBound.compareTo(upperBound) > 0) {
            throw new IllegalArgumentException("参考范围下界不能大于上界");
        }
        this.measuredValue = measuredValue;
        this.lowerBound = lowerBound;
        this.lowerInclusive = lowerInclusive;
        this.upperBound = upperBound;
        this.upperInclusive = upperInclusive;
    }

    /**
     * 判断结果是否落在参考范围内。
     *
     * <p>整个方法只有四次 {@code compareTo} 与两次开闭判断，输入可穷举、结果可复现，
     * 属 {@code AGENTS.md} §3 允许 Java 承担的确定性数值比较。</p>
     */
    public boolean inRange() {
        boolean lowerMatched = lowerBound == null
                || measuredValue.compareTo(lowerBound) > 0
                || (lowerInclusive && measuredValue.compareTo(lowerBound) == 0);
        boolean upperMatched = upperBound == null
                || measuredValue.compareTo(upperBound) < 0
                || (upperInclusive && measuredValue.compareTo(upperBound) == 0);
        return lowerMatched && upperMatched;
    }
}
