package com.example.healthreport.llm.extraction;

import com.example.healthreport.parse.segment.Segment;
import com.example.healthreport.parse.segment.TextNormalizer;
import com.example.healthreport.parse.segment.TextSource;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** R46：OCR 允许一个字符误差，原生文本对同一误差保持严格拒绝。 */
class SourceEvidenceValidatorTest {

    @Test
    void shouldAllowOneOcrEditButRejectSameNativeMismatch() {
        ExtractionValidationCounters counters = new ExtractionValidationCounters();
        SourceEvidenceValidator validator = new SourceEvidenceValidator(new TextNormalizer(), counters);
        Segment ocrSegment = segment("f0-p1-s0", "AB6", TextSource.OCR);
        Segment nativeSegment = segment("f0-p1-s1", "AB6", TextSource.NATIVE);
        Map<String, Segment> segmentByIdMap = new LinkedHashMap<String, Segment>();
        segmentByIdMap.put(ocrSegment.getSegmentId(), ocrSegment);
        segmentByIdMap.put(nativeSegment.getSegmentId(), nativeSegment);

        assertThat(validator.matches("AB8", Collections.singletonList(ocrSegment.getSegmentId()),
                segmentByIdMap)).isTrue();
        assertThat(validator.matches("AB8", Collections.singletonList(nativeSegment.getSegmentId()),
                segmentByIdMap)).isFalse();
        assertThat(counters.getOcrFuzzyMatchCount().get()).isEqualTo(1L);
    }

    @Test
    void shouldAcceptWhitespaceInsensitiveOcrWithoutCountingFuzzyMatch() {
        ExtractionValidationCounters counters = new ExtractionValidationCounters();
        SourceEvidenceValidator validator = new SourceEvidenceValidator(new TextNormalizer(), counters);
        Segment segment = segment("f0-p1-s0", "A B C", TextSource.OCR);
        Map<String, Segment> segmentByIdMap = Collections.singletonMap(segment.getSegmentId(), segment);

        assertThat(validator.matches("ABC", Collections.singletonList(segment.getSegmentId()),
                segmentByIdMap)).isTrue();
        assertThat(counters.getOcrFuzzyMatchCount().get()).isZero();
    }

    @Test
    void shouldRejectSingleCharacterAgainstUnrelatedOcrEvidenceWithoutCountingFuzzyMatch() {
        ExtractionValidationCounters counters = new ExtractionValidationCounters();
        SourceEvidenceValidator validator = new SourceEvidenceValidator(new TextNormalizer(), counters);
        Segment segment = segment("f0-p1-s0", "检查项目", TextSource.OCR);
        Map<String, Segment> segmentByIdMap = Collections.singletonMap(segment.getSegmentId(), segment);

        assertThat(validator.matches("男", Collections.singletonList(segment.getSegmentId()),
                segmentByIdMap)).isFalse();
        assertThat(counters.getOcrFuzzyMatchCount().get()).isZero();
    }

    @Test
    void shouldRejectSingleCharacterAgainstEmptyOcrEvidence() {
        ExtractionValidationCounters counters = new ExtractionValidationCounters();
        SourceEvidenceValidator validator = new SourceEvidenceValidator(new TextNormalizer(), counters);
        Segment segment = segment("f0-p1-s0", "", TextSource.OCR);
        Map<String, Segment> segmentByIdMap = Collections.singletonMap(segment.getSegmentId(), segment);

        assertThat(validator.matches("女", Collections.singletonList(segment.getSegmentId()),
                segmentByIdMap)).isFalse();
        assertThat(counters.getOcrFuzzyMatchCount().get()).isZero();
    }

    private Segment segment(String segmentId, String text, TextSource source) {
        return new Segment(segmentId, text, text, source, null);
    }
}
