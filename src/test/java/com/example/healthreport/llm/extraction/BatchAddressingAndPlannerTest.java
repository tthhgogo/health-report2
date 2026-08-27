package com.example.healthreport.llm.extraction;

import com.example.healthreport.parse.ContentType;
import com.example.healthreport.parse.ParsePlan;
import com.example.healthreport.parse.ParsedFile;
import com.example.healthreport.parse.ParsedPage;
import com.example.healthreport.parse.segment.BBox;
import com.example.healthreport.parse.segment.Segment;
import com.example.healthreport.parse.segment.TextSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R58/R60/R61：真实页码、逐块坐标、块号映射与不跨文件分批。 */
class BatchAddressingAndPlannerTest {

    @Test
    void shouldSortBySequenceRenderEveryBboxAndKeepPrivateMapping() {
        Segment second = segment(0, 2, 1, TextSource.OCR, null);
        Segment first = segment(0, 2, 0, TextSource.NATIVE, new BBox(72D, 110D, 180D, 22D));
        ParsedPage page = new ParsedPage(2, Arrays.asList(second, first), new byte[]{1}, true);

        BatchAddressing addressing = new BatchAddressing(0, Collections.singletonList(page));
        String rendered = addressing.getPageList().get(0).getRenderedText();

        assertThat(rendered).startsWith("=== 第 2 页 ===\n");
        assertThat(rendered).contains("[0] (NATIVE, bbox=72,110,180,22) block-0\n");
        assertThat(rendered).contains("[1] (OCR, bbox=null) block-1\n");
        assertThat(rendered.indexOf("[0]")).isLessThan(rendered.indexOf("[1]"));
        assertThat(addressing.expand(0)).isEqualTo("f0-p2-s0");
        assertThat(addressing.expand(1)).isEqualTo("f0-p2-s1");
        assertThat(rendered).doesNotContain("segmentId").doesNotContain("f0-p2");
    }

    @Test
    void twentyTwoPagesShouldBecomeThreeBatchesAndNeverCrossFile() {
        BatchPlanner planner = new BatchPlanner(new ExtractionPromptProvider());
        ParsedFile firstFile = nonWord(0, 9);
        ParsedFile secondFile = nonWord(1, 13);
        ParsePlan parsePlan = new ParsePlan(Arrays.asList(firstFile, secondFile), 22, 22);

        List<ExtractionBatchPlan> planList = planner.plan(parsePlan);

        assertThat(planList).hasSize(4);
        assertThat(planList).extracting(plan -> plan.getInput().getFileIndex())
                .containsExactly(0, 0, 1, 1);
        assertThat(planList).extracting(plan -> plan.getInput().getPageList().size())
                .containsExactly(8, 1, 8, 5);
        assertThat(planList).extracting(plan -> plan.getInput().getBatchIndex())
                .containsExactly(0, 1, 2, 3);
        assertThat(planList).allSatisfy(plan ->
                assertThat(plan.getInput().getBatchCount()).isEqualTo(4));
    }

    @Test
    void singleTwentyTwoPageFileShouldBecomeExactlyThreeBatches() {
        List<ExtractionBatchPlan> planList = new BatchPlanner(new ExtractionPromptProvider()).plan(
                new ParsePlan(Collections.singletonList(nonWord(0, 22)), 22, 22));

        assertThat(planList).hasSize(3);
        assertThat(planList).extracting(plan -> plan.getInput().getPageList().size())
                .containsExactly(8, 8, 6);
    }

    @Test
    void wordLogicalPagesShouldNeverRequireOrCarryImages() {
        List<Segment> segmentList = new ArrayList<Segment>(41);
        for (int index = 0; index < 41; index++) {
            int page = index / 40 + 1;
            segmentList.add(segment(0, page, index % 40, TextSource.NATIVE, null));
        }
        ParsedFile word = ParsedFile.word(0, ContentType.DOCX, segmentList);

        List<ExtractionBatchPlan> planList = new BatchPlanner(new ExtractionPromptProvider()).plan(
                new ParsePlan(Collections.singletonList(word), 2, 2));

        assertThat(planList.get(0).getInput().getPageList()).allSatisfy(page -> {
            assertThat(page.isImageRequired()).isFalse();
            assertThat(page.getJpegBytes()).isNull();
        });
    }

    @Test
    void duplicateOrMismatchedSegmentAddressShouldFailDeterministically() {
        ParsedPage mismatched = new ParsedPage(2,
                Collections.singletonList(segment(0, 1, 0, TextSource.NATIVE, null)),
                new byte[]{1}, true);

        assertThatThrownBy(() -> new BatchAddressing(0, Collections.singletonList(mismatched)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BatchAddressing(0, Arrays.asList(
                page(0, 2), page(0, 2))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ParsedFile nonWord(int fileIndex, int pageCount) {
        List<ParsedPage> pageList = new ArrayList<ParsedPage>(pageCount);
        for (int page = 1; page <= pageCount; page++) {
            pageList.add(page(fileIndex, page));
        }
        return new ParsedFile(fileIndex, ContentType.PDF, pageCount, pageList);
    }

    private ParsedPage page(int fileIndex, int page) {
        return new ParsedPage(page,
                Collections.singletonList(segment(fileIndex, page, 0, TextSource.NATIVE, null)),
                new byte[]{1}, true);
    }

    private Segment segment(int fileIndex, int page, int sequence, TextSource source, BBox bbox) {
        return new Segment(Segment.id(fileIndex, page, sequence), "raw-" + sequence,
                "block-" + sequence, source, bbox);
    }
}
