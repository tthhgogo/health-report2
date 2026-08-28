package com.example.healthreport.llm.extraction;

import com.example.healthreport.parse.segment.TextNormalizer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** 参考范围原文解析与边界核验：认得出的写法、以及三种必须拦住的绕过。 */
class ReferenceRangeParserTest {

    private final ReferenceRangeParser parser = new ReferenceRangeParser(new TextNormalizer());

    @Test
    void closedIntervalsShouldBeRecognisedInEverySupportedSeparator() {
        for (String refRange : new String[]{"4.0-10.0", "4.0~10.0", "4.0—10.0", "4.0–10.0",
                "4.0至10.0", "4.0 ~ 10.0", "参考值 4.0~10.0 mmol/L"}) {
            assertThat(parser.admits(refRange, decimal("4.0"), true, decimal("10.0"), true))
                    .as(refRange).isTrue();
        }
    }

    @Test
    void singleSidedRangesShouldCarryTheirOwnInclusiveness() {
        assertThat(parser.admits("<3.0", null, false, decimal("3.0"), false)).isTrue();
        assertThat(parser.admits("≤3.0", null, false, decimal("3.0"), true)).isTrue();
        assertThat(parser.admits("<=3.0", null, false, decimal("3.0"), true)).isTrue();
        assertThat(parser.admits(">1.0", decimal("1.0"), false, null, false)).isTrue();
        assertThat(parser.admits("≥1.0", decimal("1.0"), true, null, false)).isTrue();
        assertThat(parser.admits(">=1.0", decimal("1.0"), true, null, false)).isTrue();
    }

    /** ① 省略一侧边界：报告印了上界，模型报成不设限，任何大值都会算成正常。 */
    @Test
    void omittingABoundThatTheTextActuallyPrintsMustBeRejected() {
        assertThat(parser.admits("4.0~10.0", decimal("4.0"), true, null, false)).isFalse();
        assertThat(parser.admits("4.0~10.0", null, false, decimal("10.0"), true)).isFalse();
    }

    /** ② 边界是子串：4.0 能在 14.0~20.0 里逐字找到，但它不是这份报告的下界。 */
    @Test
    void boundThatIsMerelyASubstringMustBeRejected() {
        assertThat(parser.admits("14.0~20.0", decimal("4.0"), true, decimal("20.0"), true))
                .isFalse();
        assertThat(parser.admits("14.0~20.0", decimal("14.0"), true, decimal("20.0"), true))
                .as("如实报的区间照常通过").isTrue();
    }

    /** ③ 开闭被改：<3.0 报成闭区间，结果正好 3.0 时就会被判成正常。 */
    @Test
    void flippedInclusivenessMustBeRejected() {
        assertThat(parser.admits("<3.0", null, false, decimal("3.0"), true)).isFalse();
        assertThat(parser.admits("≥1.0", decimal("1.0"), false, null, false)).isFalse();
    }

    /** 同一段里写了多套人群范围时，模型选中哪一套都能核验，选一套没写的就不行。 */
    @Test
    void everyRangePrintedInTheSameTextShouldBeVerifiable() {
        String refRange = "男 4.0-5.5 女 3.5-5.0";

        assertThat(parser.admits(refRange, decimal("4.0"), true, decimal("5.5"), true)).isTrue();
        assertThat(parser.admits(refRange, decimal("3.5"), true, decimal("5.0"), true)).isTrue();
        assertThat(parser.admits(refRange, decimal("3.5"), true, decimal("5.5"), true))
                .as("跨着两套范围拼出来的区间没有写在报告上").isFalse();
    }

    /** 数值按 BigDecimal 比较：4.0 与 4.00 是同一个数，40 与 4.0 不是。 */
    @Test
    void boundsShouldBeComparedNumericallyNotAsText() {
        assertThat(parser.admits("4.00~10.00", decimal("4.0"), true, decimal("10"), true)).isTrue();
        assertThat(parser.admits("4.0~10.0", decimal("40"), true, decimal("10.0"), true)).isFalse();
    }

    /** 认不出的写法一律 fail-closed：宁可不展示，也不对着猜出来的范围说「正常」。 */
    @Test
    void unparsableTextShouldAdmitNothing() {
        assertThat(parser.parse("阴性")).isEmpty();
        assertThat(parser.parse("详见报告")).isEmpty();
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.admits(null, decimal("4.0"), true, decimal("10.0"), true)).isFalse();
        assertThat(parser.admits("阴性", decimal("4.0"), true, decimal("10.0"), true)).isFalse();
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
