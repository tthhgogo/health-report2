package com.example.healthreport.api;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** 上传端点 multipart 容量配置契约测试。 */
class MultipartConfigurationTest {

    private static final long PRODUCT_DOCUMENT_MAX_BYTES = 20L * 1024L * 1024L;

    @Test
    void shouldAllowTwentyMegabyteFileAndMultipartOverhead() throws IOException {
        // 配置是 .properties，直接加载即可。
        Properties properties = new Properties();
        try (InputStream configStream =
                     new ClassPathResource("application.properties").getInputStream()) {
            properties.load(configStream);
        }
        long maxFileBytes = DataSize.parse(
                properties.getProperty("spring.servlet.multipart.max-file-size")).toBytes();
        long maxRequestBytes = DataSize.parse(
                properties.getProperty("spring.servlet.multipart.max-request-size")).toBytes();

        assertThat(maxFileBytes).isGreaterThanOrEqualTo(PRODUCT_DOCUMENT_MAX_BYTES);
        assertThat(maxRequestBytes).isGreaterThan(maxFileBytes);
    }
}
