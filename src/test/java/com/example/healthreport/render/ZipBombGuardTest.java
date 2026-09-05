package com.example.healthreport.render;

import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.BusinessException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ZIP 流式解压安全边界测试。
 */
class ZipBombGuardTest {

    @Test
    void shouldRejectActualInflationRatioOverOneHundred() throws Exception {
        byte[] zeros = new byte[1024 * 1024];
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(zeros);
            zip.closeEntry();
        }

        assertThatThrownBy(() -> new ZipBombGuard().inspect(output.toByteArray()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getFailCode()).isEqualTo(FailCode.FILE_UNREADABLE));
    }

    @Test
    void implementationMustNeverTrustZipEntryDeclaredSize() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/example/healthreport/render/ZipBombGuard.java")), StandardCharsets.UTF_8);
        assertThat(source).doesNotContain(".getSize(");
    }
}
