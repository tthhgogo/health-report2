package com.example.healthreport.task;

import com.example.healthreport.infra.S3FileStorage;
import com.example.healthreport.persistence.CtHealthReportFileEntity;
import com.example.healthreport.persistence.CtHealthReportFileService;
import com.example.healthreport.render.CapacityPrecheckService;
import com.example.healthreport.render.ContentType;
import com.example.healthreport.render.FileToImageService;
import com.example.healthreport.render.FormatDetector;
import com.example.healthreport.render.PageImageSequence;
import com.example.healthreport.render.RenderableFile;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import com.example.healthreport.support.Sha256Hex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 对象存储读回内容的完整性、安全预检和任务快照边界测试。 */
@ExtendWith(MockitoExtension.class)
class TaskRenderServiceTest {

    /** 测试任务标识；用于验证数据库任务归属与调用入参精确一致。 */
    private static final String TASK_ID = "task-1";

    /** 模拟从对象存储读回的稳定原文件字节。 */
    private static final byte[] CONTENT_BYTES = "%PDF-runtime-content".getBytes(StandardCharsets.UTF_8);

    @Mock
    private CtHealthReportFileService fileService;

    @Mock
    private S3FileStorage fileStorage;

    @Mock
    private FileToImageService fileToImageService;

    @Mock
    private FormatDetector formatDetector;

    @Mock
    private CapacityPrecheckService capacityPrecheckService;

    private TaskRenderService service;

    @BeforeEach
    void setUp() {
        service = new TaskRenderService(fileService, fileStorage, fileToImageService,
                formatDetector, capacityPrecheckService);
    }

    @Test
    void shouldRecheckLengthHashFormatSafetyAndPagesBeforeRendering() {
        CtHealthReportFileEntity fileEntity = validFile();
        when(fileService.findByTaskId(TASK_ID)).thenReturn(Collections.singletonList(fileEntity));
        when(fileStorage.read(fileEntity.getCloudFileKey())).thenReturn(CONTENT_BYTES);
        when(formatDetector.detect(CONTENT_BYTES)).thenReturn(ContentType.PDF);
        when(capacityPrecheckService.precheckPages(CONTENT_BYTES, ContentType.PDF)).thenReturn(2);
        PageImageSequence expected = onePageSequence();
        renderAfterReadingEveryFile(expected);

        assertThat(service.renderFiles(TASK_ID)).isSameAs(expected);
        verify(formatDetector).detect(CONTENT_BYTES);
        verify(capacityPrecheckService).precheckPages(CONTENT_BYTES, ContentType.PDF);
    }

    @Test
    void shouldFailBeforeSafetyPrecheckWhenStoredHashChanged() {
        CtHealthReportFileEntity fileEntity = validFile();
        fileEntity.setContentHash(Sha256Hex.of("other".getBytes(StandardCharsets.UTF_8)));
        when(fileService.findByTaskId(TASK_ID)).thenReturn(Collections.singletonList(fileEntity));
        when(fileStorage.read(fileEntity.getCloudFileKey())).thenReturn(CONTENT_BYTES);
        renderAfterReadingEveryFile(onePageSequence());

        assertThatThrownBy(() -> service.renderFiles(TASK_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("哈希");
        verifyNoInteractions(formatDetector, capacityPrecheckService);
    }

    @Test
    void shouldFailWhenRedetectedFormatDiffersFromStoredMetadata() {
        CtHealthReportFileEntity fileEntity = validFile();
        when(fileService.findByTaskId(TASK_ID)).thenReturn(Collections.singletonList(fileEntity));
        when(fileStorage.read(fileEntity.getCloudFileKey())).thenReturn(CONTENT_BYTES);
        when(formatDetector.detect(CONTENT_BYTES)).thenReturn(ContentType.OFD);
        renderAfterReadingEveryFile(onePageSequence());

        assertThatThrownBy(() -> service.renderFiles(TASK_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("真实格式");
        verifyNoInteractions(capacityPrecheckService);
    }

    @Test
    void shouldFailWhenRecheckedPageCountDiffersFromStoredMetadata() {
        CtHealthReportFileEntity fileEntity = validFile();
        when(fileService.findByTaskId(TASK_ID)).thenReturn(Collections.singletonList(fileEntity));
        when(fileStorage.read(fileEntity.getCloudFileKey())).thenReturn(CONTENT_BYTES);
        when(formatDetector.detect(CONTENT_BYTES)).thenReturn(ContentType.PDF);
        when(capacityPrecheckService.precheckPages(CONTENT_BYTES, ContentType.PDF)).thenReturn(3);
        renderAfterReadingEveryFile(onePageSequence());

        assertThatThrownBy(() -> service.renderFiles(TASK_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("精确页数");
    }

    @Test
    void shouldTranslateRuntimeSafetyPrecheckFailureToServerIntegrityFailure() {
        CtHealthReportFileEntity fileEntity = validFile();
        HealthReportException precheckFailure = new HealthReportException(FailCode.FILE_UNREADABLE, 400);
        when(fileService.findByTaskId(TASK_ID)).thenReturn(Collections.singletonList(fileEntity));
        when(fileStorage.read(fileEntity.getCloudFileKey())).thenReturn(CONTENT_BYTES);
        when(formatDetector.detect(CONTENT_BYTES)).thenThrow(precheckFailure);
        renderAfterReadingEveryFile(onePageSequence());

        assertThatThrownBy(() -> service.renderFiles(TASK_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("安全预检")
                .hasCause(precheckFailure);
    }

    @Test
    void shouldRejectCorruptTaskCapacityBeforeReadingObjectStorage() {
        CtHealthReportFileEntity fileEntity = validFile();
        fileEntity.setPrecheckPages(Integer.valueOf(FileBindingService.MAX_PRECHECK_PAGES + 1));
        when(fileService.findByTaskId(TASK_ID)).thenReturn(Collections.singletonList(fileEntity));

        assertThatThrownBy(() -> service.renderFiles(TASK_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("总页数");
        verifyNoInteractions(fileStorage, formatDetector, capacityPrecheckService, fileToImageService);
    }

    @Test
    void shouldTreatMissingTaskFilesAsServerStateFailure() {
        when(fileService.findByTaskId(TASK_ID)).thenReturn(Collections.<CtHealthReportFileEntity>emptyList());

        assertThatThrownBy(() -> service.renderFiles(TASK_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有关联文件");
        verifyNoInteractions(fileStorage, formatDetector, capacityPrecheckService, fileToImageService);
    }

    private CtHealthReportFileEntity validFile() {
        CtHealthReportFileEntity fileEntity = new CtHealthReportFileEntity();
        fileEntity.setFileId("file-1");
        fileEntity.setTaskId(TASK_ID);
        fileEntity.setFileIndex(Integer.valueOf(0));
        fileEntity.setStatus(FileStatus.UPLOADED.name());
        fileEntity.setContentType(ContentType.PDF.name());
        fileEntity.setSizeBytes(Long.valueOf(CONTENT_BYTES.length));
        fileEntity.setPrecheckPages(Integer.valueOf(2));
        fileEntity.setContentHash(Sha256Hex.of(CONTENT_BYTES));
        fileEntity.setCloudFileKey("health-report/file-1");
        return fileEntity;
    }

    private PageImageSequence onePageSequence() {
        return new PageImageSequence.Builder().addPage(0, 1, new byte[]{1}).build();
    }

    @SuppressWarnings("unchecked")
    private void renderAfterReadingEveryFile(final PageImageSequence result) {
        when(fileToImageService.render(anyList())).thenAnswer(invocation -> {
            List<RenderableFile> fileList = invocation.getArgument(0);
            for (RenderableFile file : fileList) {
                file.readContentBytes();
            }
            return result;
        });
    }
}
