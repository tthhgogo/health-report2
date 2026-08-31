package com.example.healthreport.task;

import com.example.healthreport.infra.S3FileStorage;
import com.example.healthreport.parse.CapacityPrecheckService;
import com.example.healthreport.parse.FormatDetector;
import com.example.healthreport.parse.OcrProperties;
import com.example.healthreport.parse.ReadabilityChecker;
import com.example.healthreport.parse.ZipBombGuard;
import com.example.healthreport.persistence.CtHealthReportFileService;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import com.example.healthreport.support.IdCanonicalizer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** OCR 必填配置与上传图片上限共享同一启动计算结果的集成测试。 */
class FileUploadOcrCapacityIntegrationTest {

	static {
		System.setProperty("java.awt.headless", "true");
	}

	@Test
	void sixMibOcrLimitShouldRejectEightMibReadableJpegBeforeStorage() throws Exception {
		byte[] jpegBytes = paddedReadableJpeg(8 * 1024 * 1024);
		BufferedImage decodedImage = ImageIO.read(new ByteArrayInputStream(jpegBytes));
		try {
			assertThat(decodedImage).isNotNull();
		}
		finally {
			if (decodedImage != null) {
				decodedImage.flush();
			}
		}

		new ApplicationContextRunner().withUserConfiguration(OcrUploadTestConfiguration.class)
			.withPropertyValues("ocr.base-url=http://127.0.0.1", "ocr.model=test-ocr-model",
					"ocr.api-key=test-ocr-api-key", "ocr.max-encoded-image-bytes=6291456",
					"ocr.max-request-body-bytes=7340032", "ocr.request-encoding=MULTIPART",
					"ocr.accepts-encoded-bytes=true", "ocr.applies-exif-orientation=true",
					"ocr.returns-image-dimensions=true")
			.run(context -> {
				assertThat(context.getStartupFailure()).isNull();
				OcrProperties ocrProperties = context.getBean(OcrProperties.class);
				assertThat(ocrProperties.getEffectiveOcrImageBytes()).isEqualTo(6L * 1024L * 1024L);

				FileUploadService uploadService = context.getBean(FileUploadService.class);
				MockMultipartFile file = new MockMultipartFile("file", "synthetic.jpg", "image/jpeg", jpegBytes);
				assertThatThrownBy(() -> uploadService.upload(file, "case-sensitive-user", "company-a"))
					.isInstanceOfSatisfying(HealthReportException.class,
							exception -> assertThat(exception.getFailCode()).isEqualTo(FailCode.FILE_TOO_LARGE));

				ReadabilityChecker readabilityChecker = context.getBean(ReadabilityChecker.class);
				verify(readabilityChecker, never()).check(any(byte[].class), any());
				verify(context.getBean(S3FileStorage.class), never()).write(any(String.class), any(byte[].class));
			});
	}

	private byte[] paddedReadableJpeg(int targetBytes) throws Exception {
		BufferedImage sourceImage = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
		try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
			if (!ImageIO.write(sourceImage, "jpeg", outputStream)) {
				throw new IllegalStateException("测试环境缺少 JPEG 编码器");
			}
			byte[] encodedBytes = outputStream.toByteArray();
			if (encodedBytes.length >= targetBytes) {
				throw new IllegalStateException("测试 JPEG 已超过目标字节数");
			}
			return Arrays.copyOf(encodedBytes, targetBytes);
		}
		finally {
			sourceImage.flush();
		}
	}

	/** 只装配上传容量链路，不连接数据库、对象存储或外部 OCR。 */
	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(OcrProperties.class)
	@Import(FileUploadService.class)
	static class OcrUploadTestConfiguration {

		@Bean
		FormatDetector formatDetector() {
			return new FormatDetector(new ZipBombGuard());
		}

		@Bean
		ReadabilityChecker readabilityChecker() {
			return mock(ReadabilityChecker.class);
		}

		@Bean
		CapacityPrecheckService capacityPrecheckService() {
			return mock(CapacityPrecheckService.class);
		}

		@Bean
		CtHealthReportFileService ctHealthReportFileService() {
			return mock(CtHealthReportFileService.class);
		}

		@Bean
		S3FileStorage s3FileStorage() {
			return mock(S3FileStorage.class);
		}

		@Bean
		IdCanonicalizer idCanonicalizer() {
			return mock(IdCanonicalizer.class);
		}

	}

}
