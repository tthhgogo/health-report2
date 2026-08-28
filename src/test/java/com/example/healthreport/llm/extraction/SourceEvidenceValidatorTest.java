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
        SourceEvidenceValidator validator = new SourceEvidenceValidator(new TextNormalizer());
        Segment ocrSegment = segment("f0-p1-s0", "AB6", TextSource.OCR);
        Segment nativeSegment = segment("f0-p1-s1", "AB6", TextSource.NATIVE);
        Map<String, Segment> segmentByIdMap = new LinkedHashMap<String, Segment>();
        segmentByIdMap.put(ocrSegment.getSegmentId(), ocrSegment);
        segmentByIdMap.put(nativeSegment.getSegmentId(), nativeSegment);

        assertThat(validator.matches("AB8", Collections.singletonList(ocrSegment.getSegmentId()),
                segmentByIdMap)).isTrue();
        assertThat(validator.matches("AB8", Collections.singletonList(nativeSegment.getSegmentId()),
                segmentByIdMap)).isFalse();
    }

    @Test
    void shouldAcceptWhitespaceInsensitiveOcrWithoutCountingFuzzyMatch() {
        SourceEvidenceValidator validator = new SourceEvidenceValidator(new TextNormalizer());
        Segment segment = segment("f0-p1-s0", "A B C", TextSource.OCR);
        Map<String, Segment> segmentByIdMap = Collections.singletonMap(segment.getSegmentId(), segment);

        assertThat(validator.matches("ABC", Collections.singletonList(segment.getSegmentId()),
                segmentByIdMap)).isTrue();
    }

    /**
     * 空白字段一律不通过：空串是任意文本的子串。
     * <p>放行等于对这个字段取消来源校验——模型把 refRange、title 给成 {@code ""}
     * 就能带着一个查无实据的字段进入展示。</p>
     */
    @Test
    void shouldRejectBlankFieldValueBecauseEmptyTextMatchesEverything() {
        SourceEvidenceValidator validator = new SourceEvidenceValidator(new TextNormalizer());
        Segment segment = segment("f0-p1-s0", "项目甲 6.2 4.0~10.0", TextSource.NATIVE);
        Map<String, Segment> segmentByIdMap = Collections.singletonMap(segment.getSegmentId(), segment);

        assertThat(validator.matches("", Collections.singletonList(segment.getSegmentId()),
                segmentByIdMap)).isFalse();
        assertThat(validator.matches("   ", Collections.singletonList(segment.getSegmentId()),
                segmentByIdMap)).isFalse();
        assertThat(validator.matches("4.0~10.0", Collections.singletonList(segment.getSegmentId()),
                segmentByIdMap)).as("有实据的字段照常通过").isTrue();
    }

    @Test
    void shouldRejectSingleCharacterAgainstUnrelatedOcrEvidenceWithoutCountingFuzzyMatch() {
        SourceEvidenceValidator validator = new SourceEvidenceValidator(new TextNormalizer());
        Segment segment = segment("f0-p1-s0", "检查项目", TextSource.OCR);
        Map<String, Segment> segmentByIdMap = Collections.singletonMap(segment.getSegmentId(), segment);

        assertThat(validator.matches("男", Collections.singletonList(segment.getSegmentId()),
                segmentByIdMap)).isFalse();
    }

    @Test
    void shouldRejectSingleCharacterAgainstEmptyOcrEvidence() {
        SourceEvidenceValidator validator = new SourceEvidenceValidator(new TextNormalizer());
        Segment segment = segment("f0-p1-s0", "", TextSource.OCR);
        Map<String, Segment> segmentByIdMap = Collections.singletonMap(segment.getSegmentId(), segment);

        assertThat(validator.matches("女", Collections.singletonList(segment.getSegmentId()),
                segmentByIdMap)).isFalse();
    }

    private Segment segment(String segmentId, String text, TextSource source) {
        return new Segment(segmentId, text, text, source, null);
    }
}
