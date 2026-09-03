package com.example.healthreport.render;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** R66g：以独立的 512 MiB JVM 执行八千万像素图片路径。 */
@Tag("release-gate")
@Tag("pre-release-only")
class LargeImageMemoryPreReleaseTest {

    @Test
    void shouldProcessEightyMillionPixelsInBoundedSubprocess() throws Exception {
        Path metricPath = Paths.get("target/pre-release/r66g-candidate.properties")
                .toAbsolutePath();
        Path childOutputPath = Paths.get("target/pre-release/r66g-child.log")
                .toAbsolutePath();
        Files.createDirectories(metricPath.getParent());
        Process process = new ProcessBuilder(javaExecutable(), "-Xmx512m",
                "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
                LargeImageMemoryProbe.class.getName(), metricPath.toString())
                .redirectErrorStream(true)
                .redirectOutput(childOutputPath.toFile())
                .start();
        boolean completed = process.waitFor(120L, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
        }

        assertThat(completed).as("R66g 子进程必须在两分钟内结束").isTrue();
        String childOutput = new String(Files.readAllBytes(childOutputPath),
                StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(childOutput).isZero();
        assertThat(metricPath).isRegularFile();
        String metrics = new String(Files.readAllBytes(metricPath), StandardCharsets.UTF_8);
        assertThat(metrics).contains("sourcePixels=80000000", "peakUsedBytes=",
                "compressedBytes=");
    }

    /** 返回当前 JDK 的 java 可执行文件，确保子进程与 Maven 测试使用同一版本。 */
    private String javaExecutable() {
        return Paths.get(System.getProperty("java.home"), "bin", "java").toString();
    }

}
