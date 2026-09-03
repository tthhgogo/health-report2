package com.example.healthreport.task;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.example.healthreport.infra.S3FileStorage;
import com.example.healthreport.render.CapacityPrecheckService;
import com.example.healthreport.render.ContentType;
import com.example.healthreport.render.FormatDetector;
import com.example.healthreport.persistence.CtHealthReportFileEntity;
import com.example.healthreport.persistence.CtHealthReportFileService;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import com.example.healthreport.support.IdCanonicalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上传写入顺序、持久化元数据与失败清理测试。
 */
class FileUploadServiceTest {

	private static final String FILE_ID = "00000000-0000-0000-0000-000000000001";

	private static final String USER_ID = "case-sensitive-user";

	private static final String COMPANY_ID = "company-a";

	private FormatDetector formatDetector;

	private CapacityPrecheckService capacityPrecheckService;

	private CtHealthReportFileService fileService;

	private RecordingFileStorage fileStorage;

	private IdCanonicalizer idCanonicalizer;

	private FileUploadService service;

	@BeforeEach
	void setUp() {
		formatDetector = mock(FormatDetector.class);
		capacityPrecheckService = mock(CapacityPrecheckService.class);
		fileService = mock(CtHealthReportFileService.class);
		fileStorage = new RecordingFileStorage();
		idCanonicalizer = mock(IdCanonicalizer.class);
		when(idCanonicalizer.newFileId()).thenReturn(FILE_ID);
		// 让插库与写对象记进同一份日志，才能断言跨对象的先后顺序。
		doAnswer(invocation -> {
			fileStorage.callLog.add("insert");
			return 1;
		}).when(fileService).insertFromApi(any(CtHealthReportFileEntity.class));
		service = new FileUploadService(formatDetector, capacityPrecheckService, fileService,
				fileStorage, idCanonicalizer,
				Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC));
	}

	@Test
	void shouldPersistUploadedMetadataAfterAllChecksEvenWhenExtensionDiffers() {
		byte[] contentBytes = new byte[] { 1, 2, 3 };
		MockMultipartFile file = new MockMultipartFile("file", "renamed.txt", "text/plain", contentBytes);
		when(formatDetector.detect(contentBytes)).thenReturn(ContentType.PDF);
		when(capacityPrecheckService.precheckPages(contentBytes, ContentType.PDF)).thenReturn(2);
		when(fileService.insertFromApi(any(CtHealthReportFileEntity.class))).thenReturn(1);

		assertThat(service.upload(file, USER_ID, COMPANY_ID)).isEqualTo(FILE_ID);

		ArgumentCaptor<CtHealthReportFileEntity> captor = ArgumentCaptor.forClass(CtHealthReportFileEntity.class);
		verify(fileService).insertFromApi(captor.capture());
		CtHealthReportFileEntity entity = captor.getValue();
		assertThat(entity.getUserId()).isEqualTo(USER_ID);
		assertThat(entity.getTaskId()).isNull();
		assertThat(entity.getFileIndex()).isNull();
		assertThat(entity.getStatus()).isEqualTo(FileStatus.UPLOADED.name());
		assertThat(entity.getOriginName()).isEqualTo("renamed.txt");
		assertThat(entity.getContentType()).isEqualTo(ContentType.PDF.name());
		assertThat(entity.getPrecheckPages()).isEqualTo(2);
		assertThat(entity.getSizeBytes()).isEqualTo(3L);
		assertThat(entity.getContentHash()).hasSize(64);
		assertThat(entity.getCloudFileKey()).isEqualTo("health-report/" + FILE_ID);
		assertThat(entity.getExpireAt()).isEqualTo("2026-08-26T00:30:00");
		assertThat(fileStorage.writtenKey).isEqualTo(entity.getCloudFileKey());
		assertThat(fileStorage.writtenBytes).isEqualTo(contentBytes);
	}

	@Test
	void shouldApplyProductImageLimitBeforeStorage() {
		// OCR 退出后图片上限只剩产品口径 10MB；模型请求体约束由压缩器与客户端上限承担。
		byte[] contentBytes = new byte[11 * 1024 * 1024];
		contentBytes[0] = (byte) 0xFF;
		contentBytes[1] = (byte) 0xD8;
		when(formatDetector.detect(contentBytes)).thenReturn(ContentType.JPG);

		assertThatThrownBy(() -> service.upload(file(contentBytes), USER_ID, COMPANY_ID)).isInstanceOfSatisfying(
				HealthReportException.class,
				exception -> assertThat(exception.getFailCode()).isEqualTo(FailCode.FILE_TOO_LARGE));
		assertThat(fileStorage.writeCount).isZero();
		verify(fileService, never()).insertFromApi(any(CtHealthReportFileEntity.class));
	}

	@Test
	void shouldNotWriteStorageWhenCapacityPrecheckFails() {
		byte[] contentBytes = new byte[] { 1 };
		when(formatDetector.detect(contentBytes)).thenReturn(ContentType.PDF);
		when(capacityPrecheckService.precheckPages(contentBytes, ContentType.PDF))
			.thenThrow(new HealthReportException(FailCode.PAGE_LIMIT_EXCEEDED, 400));

		assertThatThrownBy(() -> service.upload(file(contentBytes), USER_ID, COMPANY_ID)).isInstanceOfSatisfying(
				HealthReportException.class,
				exception -> assertThat(exception.getFailCode()).isEqualTo(FailCode.PAGE_LIMIT_EXCEEDED));
		assertThat(fileStorage.writeCount).isZero();
		verify(fileService, never()).insertFromApi(any(CtHealthReportFileEntity.class));
	}

	@Test
	void databaseInsertFailureShouldNeverHaveWrittenAnyObject() {
		byte[] contentBytes = new byte[] { 1 };
		when(formatDetector.detect(contentBytes)).thenReturn(ContentType.PDF);
		when(capacityPrecheckService.precheckPages(contentBytes, ContentType.PDF)).thenReturn(1);
		doThrow(new IllegalStateException("synthetic database failure")).when(fileService)
			.insertFromApi(any(CtHealthReportFileEntity.class));

		assertThatThrownBy(() -> service.upload(file(contentBytes), USER_ID, COMPANY_ID)).isInstanceOfSatisfying(
				HealthReportException.class,
				exception -> assertThat(exception.getFailCode()).isEqualTo(FailCode.SERVER_ERROR));
		// 行没插进去时对象根本没被写过，不存在需要补偿删除的东西。
		assertThat(fileStorage.writeCount).isZero();
		assertThat(fileStorage.deletedKey).isNull();
	}

	@Test
	void objectWriteMustHappenAfterTheLedgerRowIsInserted() {
		byte[] contentBytes = new byte[] { 1 };
		when(formatDetector.detect(contentBytes)).thenReturn(ContentType.PDF);
		when(capacityPrecheckService.precheckPages(contentBytes, ContentType.PDF)).thenReturn(1);

		service.upload(file(contentBytes), USER_ID, COMPANY_ID);

		// 顺序反了就会在两步之间留下【库里没有任何行指向的】S3 对象，
		// 而孤儿清理是从 file 行出发找对象的，那个对象永远不会被发现。
		assertThat(fileStorage.callLog).containsExactly("insert", "write");
	}

	@Test
	void objectWriteFailureMustKeepTheLedgerRowForOrphanCleanup() {
		byte[] contentBytes = new byte[] { 1 };
		when(formatDetector.detect(contentBytes)).thenReturn(ContentType.PDF);
		when(capacityPrecheckService.precheckPages(contentBytes, ContentType.PDF)).thenReturn(1);
		fileStorage.failWrite = true;

		assertThatThrownBy(() -> service.upload(file(contentBytes), USER_ID, COMPANY_ID)).isInstanceOfSatisfying(
				HealthReportException.class,
				exception -> assertThat(exception.getFailCode()).isEqualTo(FailCode.SERVER_ERROR));
		// 写对象失败可能是假阴性（超时的 PUT 服务端已成功），删掉行就回到了
		// 「对象存在但无人指向」的原问题。行必须留着，让孤儿清理无条件删一次对象。
		verify(fileService, never()).deleteOrphan(anyString());
		assertThat(fileStorage.deletedKey).isNull();
	}

	@Test
	void r49UploadFailureLogsShouldExcludeAllSensitiveMarkersAndOriginName() {
		String originNameMarker = "R49_ORIGIN_NAME_TOKEN";
		List<String> prohibitedMarkerList = Arrays.asList(originNameMarker, "R49_REPORT_PAYLOAD_TOKEN",
				"R49_PERSON_NAME_TOKEN", "R49_OCR_TOKEN", "R49_HEALTH_TOKEN", "R49_CREDENTIAL_TOKEN",
				"R49_MODEL_BODY_TOKEN");
		byte[] contentBytes = new byte[] { 1 };
		MockMultipartFile file = new MockMultipartFile("file", originNameMarker, "application/octet-stream",
				contentBytes);
		when(formatDetector.detect(contentBytes)).thenReturn(ContentType.PDF);
		when(capacityPrecheckService.precheckPages(contentBytes, ContentType.PDF)).thenReturn(1);
		doThrow(new IllegalStateException(String.join("|", prohibitedMarkerList))).when(fileService)
			.insertFromApi(any(CtHealthReportFileEntity.class));
		Logger logger = (Logger) LoggerFactory.getLogger(FileUploadService.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
		appender.start();
		logger.addAppender(appender);
		try {
			assertThatThrownBy(() -> service.upload(file, USER_ID, COMPANY_ID))
				.isInstanceOf(HealthReportException.class);

			assertThat(appender.list).isNotEmpty().allSatisfy(event -> {
				String throwableText = event.getThrowableProxy() == null ? ""
						: ThrowableProxyUtil.asString(event.getThrowableProxy());
				for (String prohibitedMarker : prohibitedMarkerList) {
					assertThat(event.getFormattedMessage()).doesNotContain(prohibitedMarker);
					assertThat(throwableText).doesNotContain(prohibitedMarker);
				}
				assertThat(event.getThrowableProxy()).isNotNull();
				assertThat(event.getThrowableProxy().getStackTraceElementProxyArray())
					.extracting(proxy -> proxy.getStackTraceElement().getClassName())
					.contains(FileUploadServiceTest.class.getName());
			});
		}
		finally {
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	private MockMultipartFile file(byte[] contentBytes) {
		return new MockMultipartFile("file", "synthetic.bin", "application/octet-stream", contentBytes);
	}

	/** 测试专用对象存储 Fake，只记录调用，不接触外部服务。 */
	private static class RecordingFileStorage implements S3FileStorage {

		private int writeCount;

		private String writtenKey;

		private byte[] writtenBytes;

		private String deletedKey;

		private boolean failWrite;

		/** 与 fileService 共享的调用日志，用于断言跨对象的先后顺序。 */
		private final List<String> callLog = new ArrayList<String>();

		@Override
		public void write(String objectKey, byte[] contentBytes) {
			callLog.add("write");
			if (failWrite) {
				throw new IllegalStateException("synthetic object storage failure");
			}
			writeCount++;
			writtenKey = objectKey;
			writtenBytes = contentBytes;
		}

		@Override
		public void delete(String objectKey) {
			deletedKey = objectKey;
		}

	}

}
