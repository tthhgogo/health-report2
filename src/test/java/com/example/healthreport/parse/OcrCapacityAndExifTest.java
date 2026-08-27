package com.example.healthreport.parse;

import com.example.healthreport.parse.ocr.OcrBboxNormalizer;
import com.example.healthreport.parse.ocr.OcrBlock;
import com.example.healthreport.parse.ocr.OcrPageSegmentFactory;
import com.example.healthreport.parse.ocr.OcrPageSegmentResult;
import com.example.healthreport.parse.ocr.OcrResult;
import com.example.healthreport.parse.segment.BBox;
import com.example.healthreport.parse.segment.TextNormalizer;
import com.example.healthreport.parse.segment.TextSource;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** OCR 容量 long 运算、启动参数和 EXIF 八矩阵测试。 */
class OcrCapacityAndExifTest {

    @Test
    void shouldCalculateJsonAndMultipartLimitsWithoutLongOverflow() {
        OcrCapacityCalculator calculator = new OcrCapacityCalculator();
        long jsonAvailable = 4L * 1024L * 1024L;

        assertThat(calculator.calculate(Long.MAX_VALUE,
                jsonAvailable + OcrCapacityCalculator.JSON_FIXED_OVERHEAD_BYTES,
                OcrRequestEncoding.JSON_BASE64)).isEqualTo(3L * 1024L * 1024L);
        assertThat(calculator.calculate(Long.MAX_VALUE, Long.MAX_VALUE,
                OcrRequestEncoding.JSON_BASE64)).isPositive();
        assertThat(calculator.calculate(12345L,
                12345L + OcrCapacityCalculator.MULTIPART_FIXED_OVERHEAD_BYTES,
                OcrRequestEncoding.MULTIPART)).isEqualTo(12345L);
    }

    @Test
    void shouldFailStartupForMissingNonPositiveAndImpossibleOcrSettings() {
        OcrCapacityCalculator calculator = new OcrCapacityCalculator();
        assertThatThrownBy(() -> calculator.calculate(0L, 100000L, OcrRequestEncoding.MULTIPART))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> calculator.calculate(1L, -1L, OcrRequestEncoding.MULTIPART))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> calculator.calculate(1L,
                OcrCapacityCalculator.JSON_FIXED_OVERHEAD_BYTES, OcrRequestEncoding.JSON_BASE64))
                .isInstanceOf(IllegalStateException.class);

        OcrProperties missing = new OcrProperties();
        assertThatThrownBy(missing::afterPropertiesSet).isInstanceOf(IllegalStateException.class);

        OcrProperties impossibleExif = validProperties();
        impossibleExif.setAppliesExifOrientation(Boolean.FALSE);
        impossibleExif.setReturnsImageDimensions(Boolean.FALSE);
        assertThatThrownBy(impossibleExif::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldTransformAllExifOrientationsAndNeverFlipOcrYTwice() {
        ExifOrientationTransform transform = new ExifOrientationTransform();
        BBox source = new BBox(10D, 20D, 30D, 40D);
        double[][] expected = new double[][]{
                {10D, 20D, 30D, 40D}, {60D, 20D, 30D, 40D},
                {60D, 140D, 30D, 40D}, {10D, 140D, 30D, 40D},
                {20D, 10D, 40D, 30D}, {140D, 10D, 40D, 30D},
                {140D, 60D, 40D, 30D}, {20D, 60D, 40D, 30D}
        };
        for (int orientation = 1; orientation <= 8; orientation++) {
            BBox actual = transform.transform(source, 100, 200, orientation);
            assertThat(new double[]{actual.getX(), actual.getY(), actual.getWidth(), actual.getHeight()})
                    .containsExactly(expected[orientation - 1]);
            assertThat(transform.swapsDimensions(orientation)).isEqualTo(orientation >= 5);
        }

        OcrResult result = new OcrResult(Collections.emptyList(), 100, 200);
        OcrBboxNormalizer alreadyApplied = new OcrBboxNormalizer(true, true, transform);
        assertThat(alreadyApplied.normalize(source, 6, result)).isSameAs(source);
        OcrBboxNormalizer requiresTransform = new OcrBboxNormalizer(false, true, transform);
        assertThat(requiresTransform.normalize(source, 6, result).getY()).isEqualTo(10D);

        OcrResult topLeftResult = new OcrResult(
                Collections.singletonList(new OcrBlock("ocr", source)), 100, 200);
        OcrPageSegmentResult segmentResult = new OcrPageSegmentFactory(new TextNormalizer(), alreadyApplied)
                .create(topLeftResult, 0, 1, 0, 1);
        assertThat(segmentResult.getSegmentList().get(0).getTextSource()).isEqualTo(TextSource.OCR);
        assertThat(segmentResult.getSegmentList().get(0).getBbox()).isSameAs(source);
    }

    @Test
    void parserCounterShouldCountAffectedSegmentsInsteadOfResidualCharacters() {
        TextNormalizer textNormalizer = new TextNormalizer();
        OcrResult result = new OcrResult(Arrays.asList(
                new OcrBlock("\u2E80\u2E81", null),
                new OcrBlock("clean", null),
                new OcrBlock("\u2E80\u2E81", null)), 100, 200);
        OcrBboxNormalizer bboxNormalizer = new OcrBboxNormalizer(true, true,
                new ExifOrientationTransform());

        OcrPageSegmentResult segmentResult = new OcrPageSegmentFactory(
                textNormalizer, bboxNormalizer).create(result, 0, 1, 0, 1);

        assertThat(segmentResult.getResidualNonStandardCount()).isEqualTo(4);
        assertThat(textNormalizer.residualNonStandardCount()).isEqualTo(2L);
    }

    private OcrProperties validProperties() {
        OcrProperties properties = new OcrProperties();
        properties.setMaxEncodedImageBytes(8L * 1024L * 1024L);
        properties.setMaxRequestBodyBytes(12L * 1024L * 1024L);
        properties.setRequestEncoding(OcrRequestEncoding.JSON_BASE64);
        properties.setAcceptsEncodedBytes(Boolean.TRUE);
        properties.setAppliesExifOrientation(Boolean.TRUE);
        properties.setReturnsImageDimensions(Boolean.TRUE);
        return properties;
    }
}
