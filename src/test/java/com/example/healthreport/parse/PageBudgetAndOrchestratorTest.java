package com.example.healthreport.parse;

import com.example.healthreport.cache.AnalysisModules;
import com.example.healthreport.cache.AnalysisResult;
import com.example.healthreport.parse.segment.BBox;
import com.example.healthreport.parse.segment.Segment;
import com.example.healthreport.parse.segment.TextSource;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import com.example.healthreport.support.PartialReason;
import com.example.healthreport.task.DegradeAccumulator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R43：三档页数、Word 等效页及分批前零文字裁决。 */
class PageBudgetAndOrchestratorTest {

    private final ParseOrchestrator orchestrator = new ParseOrchestrator(new PageBudgetService());

    @Test
    void exactlyThirtyPagesShouldNotDegradeAndCountsShouldReachResult() {
        DegradeAccumulator accumulator = new DegradeAccumulator();
        ParsePlan plan = orchestrator.prepare(Collections.singletonList(nonWord(0, 30, true)), accumulator);
        AnalysisResult result = AnalysisResult.create(accumulator,
                plan.getProcessedPages(), plan.getTotalPages(), AnalysisModules.empty());

        assertThat(plan.getReadableFileList().get(0).getPageList()).hasSize(30);
        assertThat(result.getProcessedPages()).isEqualTo(30);
        assertThat(result.getTotalPages()).isEqualTo(30);
        assertThat(result.isPartial()).isFalse();
    }

    @Test
    void fortyFivePagesShouldRetainFirstThirtyAndSuppressLastTwoModules() {
        DegradeAccumulator accumulator = new DegradeAccumulator();
        ParsePlan plan = orchestrator.prepare(Collections.singletonList(nonWord(0, 45, true)), accumulator);
        AnalysisResult result = AnalysisResult.create(accumulator,
                plan.getProcessedPages(), plan.getTotalPages(), AnalysisModules.empty());

        assertThat(plan.getReadableFileList().get(0).getPageList()).hasSize(30);
        assertThat(result.getProcessedPages()).isEqualTo(30);
        assertThat(result.getTotalPages()).isEqualTo(45);
        assertThat(result.isPartial()).isTrue();
        assertThat(result.getPartialReason()).isEqualTo(PartialReason.PAGE_TRUNCATED);
        assertThat(result.isSuppressDietAdvice()).isTrue();
        assertThat(result.isSuppressDishRecommend()).isTrue();
    }

    @Test
    void truncationShouldKeepFrontOfThirdFileIncludingWordSegments() {
        List<Segment> wordSegmentList = segments(2, 20, 800);
        ParsedFile word = ParsedFile.word(2, ContentType.DOCX, wordSegmentList);
        DegradeAccumulator accumulator = new DegradeAccumulator();

        ParsePlan plan = orchestrator.prepare(Arrays.asList(
                nonWord(0, 10, true), nonWord(1, 10, true), word), accumulator);

        ParsedFile retainedWord = plan.getReadableFileList().get(2);
        assertThat(plan.getProcessedPages()).isEqualTo(30);
        assertThat(plan.getTotalPages()).isEqualTo(40);
        assertThat(retainedWord.getPageList()).hasSize(10);
        assertThat(flatten(retainedWord)).hasSize(400);
        assertThat(flatten(retainedWord).get(399).getSegmentId()).isEqualTo("f2-p10-s39");
    }

    @Test
    void partialEmptyFileShouldDegradeWhileReadableFileContinues() {
        ParsedFile emptyFile = nonWord(0, 2, false);
        ParsedFile readableFile = nonWord(1, 2, true);
        DegradeAccumulator accumulator = new DegradeAccumulator();

        ParsePlan plan = orchestrator.prepare(Arrays.asList(emptyFile, readableFile), accumulator);

        assertThat(plan.getReadableFileList()).extracting(ParsedFile::getFileIndex).containsExactly(1);
        assertThat(accumulator.partial()).isTrue();
        assertThat(accumulator.primaryReason()).isEqualTo(PartialReason.BATCH_UNREADABLE);
        assertThat(accumulator.suppressDietAdvice()).isTrue();
        assertThat(accumulator.suppressDishRecommend()).isTrue();
    }

    @Test
    void allEmptyWordOrScannedPagesShouldFailBeforeAnyBatchExists() {
        DegradeAccumulator accumulator = new DegradeAccumulator();
        ParsedFile emptyWord = ParsedFile.word(0, ContentType.DOCX, Collections.<Segment>emptyList());
        ParsedFile emptyScan = nonWord(1, 1, false);

        assertThatThrownBy(() -> orchestrator.prepare(Arrays.asList(emptyWord, emptyScan), accumulator))
                .isInstanceOfSatisfying(HealthReportException.class,
                        exception -> assertThat(exception.getFailCode()).isEqualTo(FailCode.UNREADABLE));
    }

    @Test
    void exactCountOverSixtyShouldDistinguishWordFromUpstreamContractBreach() {
        assertThatThrownBy(() -> orchestrator.prepare(
                Collections.singletonList(nonWord(0, 61, true)), new DegradeAccumulator()))
                .isInstanceOfSatisfying(HealthReportException.class,
                        exception -> assertThat(exception.getFailCode()).isEqualTo(FailCode.SERVER_ERROR));

        List<Segment> wordSegmentList = segments(1, 30, 1200);
        assertThatThrownBy(() -> orchestrator.prepare(Arrays.asList(
                nonWord(0, 31, true), ParsedFile.word(1, ContentType.DOC, wordSegmentList)),
                new DegradeAccumulator()))
                .isInstanceOfSatisfying(HealthReportException.class,
                        exception -> assertThat(exception.getFailCode()).isEqualTo(FailCode.PAGE_LIMIT_EXCEEDED));
    }

    @Test
    void singleWordShouldRejectMoreThanTwelveHundredSegments() {
        assertThatThrownBy(() -> ParsedFile.word(0, ContentType.DOCX, segments(0, 31, 1201)))
                .isInstanceOfSatisfying(HealthReportException.class,
                        exception -> assertThat(exception.getFailCode()).isEqualTo(FailCode.PAGE_LIMIT_EXCEEDED));
    }

    private ParsedFile nonWord(int fileIndex, int pages, boolean withSegments) {
        List<ParsedPage> pageList = new ArrayList<ParsedPage>(pages);
        for (int page = 1; page <= pages; page++) {
            List<Segment> segmentList = withSegments
                    ? Collections.singletonList(segment(fileIndex, page, 0))
                    : Collections.<Segment>emptyList();
            pageList.add(new ParsedPage(page, segmentList, new byte[]{1}, true));
        }
        return new ParsedFile(fileIndex, ContentType.PDF, pages, pageList);
    }

    private List<Segment> segments(int fileIndex, int logicalPages, int count) {
        List<Segment> segmentList = new ArrayList<Segment>(count);
        for (int index = 0; index < count; index++) {
            int page = index / ParsedFile.WORD_SEGMENTS_PER_PAGE + 1;
            int sequence = index % ParsedFile.WORD_SEGMENTS_PER_PAGE;
            segmentList.add(segment(fileIndex, page, sequence));
        }
        assertThat(pageForCount(count)).isLessThanOrEqualTo(logicalPages);
        return segmentList;
    }

    private int pageForCount(int count) {
        return (count + ParsedFile.WORD_SEGMENTS_PER_PAGE - 1) / ParsedFile.WORD_SEGMENTS_PER_PAGE;
    }

    private Segment segment(int fileIndex, int page, int sequence) {
        return new Segment(Segment.id(fileIndex, page, sequence), "raw", "block",
                TextSource.NATIVE, new BBox(1D, 2D, 3D, 4D));
    }

    private List<Segment> flatten(ParsedFile file) {
        List<Segment> segmentList = new ArrayList<Segment>();
        for (ParsedPage page : file.getPageList()) {
            segmentList.addAll(page.getSegmentList());
        }
        return segmentList;
    }
}
