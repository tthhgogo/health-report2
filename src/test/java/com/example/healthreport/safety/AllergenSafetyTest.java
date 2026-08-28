package com.example.healthreport.safety;

import com.example.healthreport.constants.AllergenKey;
import com.example.healthreport.llm.extraction.AllergenResultStatus;
import com.example.healthreport.llm.extraction.ValidatedExtractionOutput;
import com.example.healthreport.parse.segment.Segment;
import com.example.healthreport.parse.segment.TextSource;
import com.example.healthreport.support.HealthReportException;
import com.example.healthreport.task.DegradeAccumulator;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R4/R5/R9/R10/R12：过敏安全扫描与准入边界。 */
class AllergenSafetyTest {

	@Test
	void shouldDetectOnlySameSegmentPositiveCandidate() {
		PositiveRowCoverageScanner scanner = new PositiveRowCoverageScanner();
		DegradeAccumulator accumulator = new DegradeAccumulator();
		List<Segment> separatedSegmentList = Arrays.asList(segment("f0-p1-s0", "牛奶"), segment("f0-p1-s1", "阳性(+)"));

		assertThat(scanner.scan(separatedSegmentList, Collections.<String>emptySet(), accumulator)).isZero();
		assertThat(accumulator.partial()).isFalse();

		Segment sameSegment = segment("f0-p1-s2", "牛奶 阳性(+)");
		assertThat(scanner.scan(Collections.singletonList(sameSegment), Collections.<String>emptySet(), accumulator))
			.isEqualTo(1);
		assertThat(accumulator.partial()).isTrue();
	}

	@Test
	void borderlineShouldEnterProductSafetyFilterWithoutAdmittingNegativeOrUnknown() {
		AllergenAdmissionFilter filter = new AllergenAdmissionFilter();
		List<ValidatedExtractionOutput.Allergen> sourceList = Arrays.asList(allergen(AllergenResultStatus.NEGATIVE),
				allergen(AllergenResultStatus.UNKNOWN), allergen(AllergenResultStatus.POSITIVE),
				allergen(AllergenResultStatus.BORDERLINE));

		List<ValidatedExtractionOutput.Allergen> admittedList = filter.filter(sourceList);
		assertThat(admittedList).hasSize(2);
		assertThat(admittedList.get(0).getResultStatus()).isEqualTo(AllergenResultStatus.POSITIVE);
		assertThat(admittedList.get(1).getResultStatus()).isEqualTo(AllergenResultStatus.BORDERLINE);
	}

	@Test
	void shouldResolveFormalFoodFlagFromGroupsAndTrustOther() {
		AllergenAdmissionFilter filter = new AllergenAdmissionFilter();

		assertThat(filter.resolveFoodBorne(AllergenKey.MILK, false)).isTrue();
		assertThat(filter.resolveFoodBorne(AllergenKey.DUST_MITE, true)).isFalse();
		assertThat(filter.resolveFoodBorne(AllergenKey.OTHER, true)).isTrue();
		assertThat(filter.resolveFoodBorne(AllergenKey.OTHER, false)).isFalse();
	}

	@Test
	void shouldDegradeWhenAllergenSectionExistsButArrayIsEmpty() {
		AllergenSuspectScanner scanner = new AllergenSuspectScanner();
		Segment segment = segment("f0-p1-s0", "过敏原筛查");
		Set<String> sectionIdSet = new LinkedHashSet<String>();
		sectionIdSet.add(segment.getSegmentId());
		DegradeAccumulator accumulator = new DegradeAccumulator();

		assertThat(scanner.scan(Collections.singletonList(segment), sectionIdSet, 0, accumulator)).isTrue();
		assertThat(accumulator.partial()).isTrue();
	}

	@Test
	void shouldDegradeCoverageGapAndRejectSubsetViolation() {
		AllergenCoverageScanner scanner = new AllergenCoverageScanner();
		Set<String> sectionIdSet = new LinkedHashSet<String>(Arrays.asList("s0", "s1"));
		Set<String> dataIdSet = new LinkedHashSet<String>(Arrays.asList("s1"));
		DegradeAccumulator accumulator = new DegradeAccumulator();

		assertThat(scanner.scan(sectionIdSet, dataIdSet, Collections.<String>emptySet(), accumulator)).isTrue();
		assertThat(accumulator.partial()).isTrue();
		assertThatThrownBy(() -> scanner.scan(Collections.singleton("s0"), Collections.singleton("s1"),
				Collections.<String>emptySet(), new DegradeAccumulator()))
			.isInstanceOf(HealthReportException.class);
	}

	private ValidatedExtractionOutput.Allergen allergen(AllergenResultStatus status) {
		return new ValidatedExtractionOutput.Allergen(0, 0, 0, 0, 0, 1, Collections.singletonList("f0-p1-s0"),
				AllergenKey.OTHER, true, "样本项", "样本结果", status);
	}

	private Segment segment(String segmentId, String text) {
		return new Segment(segmentId, text, text, TextSource.NATIVE, null);
	}

}
