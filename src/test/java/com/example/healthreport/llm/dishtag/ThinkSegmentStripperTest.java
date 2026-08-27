package com.example.healthreport.llm.dishtag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R56b：思考段剥离。这是本链路唯一会产生静默错误数据的地方。 */
class ThinkSegmentStripperTest {

    private static final String JSON = "{\"enumKey\":\"LOW_FAT\"}";

    @Test
    void shouldStripLeadingThinkSegment() {
        assertThat(ThinkSegmentStripper.strip("<think>\n我想想\n</think>\n\n" + JSON))
                .isEqualTo(JSON);
    }

    @Test
    void shouldReturnContentUnchangedWhenThereIsNoThinkSegment() {
        assertThat(ThinkSegmentStripper.strip("  " + JSON + "  ")).isEqualTo(JSON);
    }

    @Test
    void exampleJsonInsideThinkSegmentMustNotBeMistakenForTheResult() {
        String decoy = "{\"enumKey\":\"WRONG\",\"neutralDishIds\":[999]}";
        String content = "<think>\n先试写一下格式：" + decoy + "\n再看看对不对\n</think>\n\n" + JSON;

        // 「找第一个 { 到最后一个 }」会取到 decoy —— 而它 Schema 完全合法、
        // 覆盖与互斥校验也可能全过，最后写进库的就是模型的草稿。
        assertThat(ThinkSegmentStripper.strip(content)).isEqualTo(JSON);
    }

    @Test
    void shouldTakeTheLastCloseTagBecauseThinkingMayQuoteOne() {
        String content = "<think>\n我不该输出 </think> 这个字面量\n</think>\n" + JSON;

        assertThat(ThinkSegmentStripper.strip(content)).isEqualTo(JSON);
    }

    @Test
    void everyMalformedShapeShouldRejectTheWholeBatch() {
        // ① 被 max_tokens 截断：有开标签、没有闭标签
        assertThatThrownBy(() -> ThinkSegmentStripper.strip("<think>\n还在想"))
                .isInstanceOf(DishTagBatchRejectedException.class);
        // ② 只有闭标签：开头那段不知道是什么，不能猜
        assertThatThrownBy(() -> ThinkSegmentStripper.strip("想完了</think>" + JSON))
                .isInstanceOf(DishTagBatchRejectedException.class);
        // ③ 开标签不在开头：结构与预期不符
        assertThatThrownBy(() -> ThinkSegmentStripper.strip("前言 <think>x</think>" + JSON))
                .isInstanceOf(DishTagBatchRejectedException.class);
        // ④ 剥完是空的
        assertThatThrownBy(() -> ThinkSegmentStripper.strip("<think>x</think>   "))
                .isInstanceOf(DishTagBatchRejectedException.class);
        // ⑤ content 本身为 null
        assertThatThrownBy(() -> ThinkSegmentStripper.strip(null))
                .isInstanceOf(DishTagBatchRejectedException.class);
    }
}
