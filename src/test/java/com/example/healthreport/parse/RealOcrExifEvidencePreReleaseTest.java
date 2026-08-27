package com.example.healthreport.parse;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** R66h：真实 OCR 的 EXIF Orientation=6 联调留档门禁。 */
@Tag("external-ocr")
class RealOcrExifEvidencePreReleaseTest {

    @Test
    void realOcrExifOrientationSixEvidenceMustBeExplicitlyProvidedAndPassed() throws Exception {
        String evidenceFile = System.getProperty("r66h.evidenceFile");
        assertThat(evidenceFile)
                .as("真实 OCR 联调必须通过 -Dr66h.evidenceFile 指定留档，不能用本地桩替代")
                .isNotBlank();
        Path evidencePath = Paths.get(evidenceFile);
        assertThat(evidencePath).isRegularFile();
        Properties evidence = new Properties();
        try (InputStream inputStream = Files.newInputStream(evidencePath)) {
            evidence.load(inputStream);
        }

        assertThat(evidence.getProperty("test")).isEqualTo("R66h");
        assertThat(evidence.getProperty("sample")).isEqualTo("EXIF_ORIENTATION_6");
        assertThat(evidence.getProperty("ocrServiceVersion")).isNotBlank();
        assertThat(evidence.getProperty("executedAt")).isNotBlank();
        assertThat(evidence.getProperty("orientationBehavior")).isIn(
                "APPLIED_BY_OCR", "NOT_APPLIED_BY_OCR");
        assertThat(evidence.getProperty("coordinateConclusion")).isNotBlank();
        assertThat(evidence.getProperty("result")).isEqualTo("PASSED");
    }
}
