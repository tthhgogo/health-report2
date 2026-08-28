package com.example.healthreport.llm.extraction;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 参考范围比较的边界断言。
 *
 * <p>本类只覆盖 Java 实际承担的那一点职责：<b>对已经拆好的十进制数做开闭区间比较</b>。
 * 「哪个参考范围适用」「单位对不对齐」不在这里，也不该在 Java 里——那是 LLM-A 的活。</p>
 */
class IndicatorRangeComparisonTest {

    @Test
    void valueInsideClosedRangeShouldBeInRange() {
        assertThat(closed("6.2", "4.0", "10.0").inRange()).isTrue();
    }

    @Test
    void valueOutsideClosedRangeShouldNotBeInRange() {
        assertThat(closed("3.9", "4.0", "10.0").inRange()).isFalse();
        assertThat(closed("10.1", "4.0", "10.0").inRange()).isFalse();
    }

    @Test
    void boundariesShouldFollowInclusiveFlags() {
        assertThat(closed("4.0", "4.0", "10.0").inRange()).as("闭区间下界应算在内").isTrue();
        assertThat(closed("10.0", "4.0", "10.0").inRange()).as("闭区间上界应算在内").isTrue();

        IndicatorRangeComparison openLower = new IndicatorRangeComparison(decimal("4.0"),
                decimal("4.0"), false, decimal("10.0"), true);
        assertThat(openLower.inRange()).as("开区间下界不算在内").isFalse();

        IndicatorRangeComparison openUpper = new IndicatorRangeComparison(decimal("10.0"),
                decimal("4.0"), true, decimal("10.0"), false);
        assertThat(openUpper.inRange()).as("开区间上界不算在内").isFalse();
    }

    /**
     * 标度差异必须判等。
     * <p>{@code BigDecimal.equals} 把标度也算进相等条件，{@code 1.10.equals(1.1)} 是 false；
     * 边界比较一旦误用 equals，恰好等于上界的指标会被判成超标。</p>
     */
    @Test
    void scaleDifferenceOnBoundaryMustStillCountAsEqual() {
        assertThat(new BigDecimal("1.10").equals(new BigDecimal("1.1")))
                .as("这正是不能用 equals 的原因").isFalse();

        IndicatorRangeComparison comparison = new IndicatorRangeComparison(decimal("1.10"),
                decimal("0.5"), true, decimal("1.1"), true);
        assertThat(comparison.inRange()).as("1.10 与上界 1.1 是同一个数").isTrue();
    }

    @Test
    void singleSidedRangesShouldOnlyCheckTheDeclaredBound() {
        // <3.0：只有上界，下方不设限
        IndicatorRangeComparison upperOnly = new IndicatorRangeComparison(decimal("0.01"),
                null, false, decimal("3.0"), false);
        assertThat(upperOnly.inRange()).isTrue();
        assertThat(new IndicatorRangeComparison(decimal("3.0"), null, false,
                decimal("3.0"), false).inRange()).isFalse();

        // >1.0（如 HDL 这类越高越好）：只有下界，上方不设限
        IndicatorRangeComparison lowerOnly = new IndicatorRangeComparison(decimal("99.9"),
                decimal("1.0"), false, null, false);
        assertThat(lowerOnly.inRange()).isTrue();
        assertThat(new IndicatorRangeComparison(decimal("1.0"), decimal("1.0"), false,
                null, false).inRange()).isFalse();
    }

    @Test
    void contradictoryOrUnboundedComparisonMustBeRejectedAtConstruction() {
        assertThatThrownBy(() -> new IndicatorRangeComparison(decimal("5"), decimal("10"),
                true, decimal("1"), true))
                .as("下界大于上界是自相矛盾的输入")
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new IndicatorRangeComparison(decimal("5"), null, false, null, false))
                .as("上下界都不设限时「落在范围内」恒真，等于没有准入条件")
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new IndicatorRangeComparison(null, decimal("1"), true,
                decimal("2"), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private IndicatorRangeComparison closed(String value, String lower, String upper) {
        return new IndicatorRangeComparison(decimal(value), decimal(lower), true,
                decimal(upper), true);
    }

    private BigDecimal decimal(String text) {
        return new BigDecimal(text);
    }
}
