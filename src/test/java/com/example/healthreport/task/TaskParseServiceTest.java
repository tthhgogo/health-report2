package com.example.healthreport.task;

import com.example.healthreport.infra.S3FileStorage;
import com.example.healthreport.parse.ContentType;
import com.example.healthreport.parse.FileParseService;
import com.example.healthreport.parse.ParsedFile;
import com.example.healthreport.parse.ParsedPage;
import com.example.healthreport.parse.segment.Segment;
import com.example.healthreport.parse.segment.TextSource;
import com.example.healthreport.persistence.CtHealthReportFileEntity;
import com.example.healthreport.persistence.CtHealthReportFileService;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 文件读取顺序、绑定完整性与格式枚举的失败归属。 */
class TaskParseServiceTest {

    private static final String TASK_ID = "20000000-0000-0000-0000-000000000001";

    @Test
    void shouldParseInFileIndexOrderRegardlessOfDatabaseOrder() {
        CtHealthReportFileService fileService = mock(CtHealthReportFileService.class);
        S3FileStorage fileStorage = mock(S3FileStorage.class);
        FileParseService fileParseService = mock(FileParseService.class);
        // 数据库按 file_id 返回，顺序与 fileIndex 相反。
        when(fileService.findByTaskId(TASK_ID))
                .thenReturn(Arrays.asList(fileEntity("b", 1), fileEntity("a", 0)));
        when(fileStorage.read(anyString())).thenReturn(new byte[]{1});
        when(fileParseService.parse(anyInt(), any(ContentType.class), any(byte[].class), anyInt()))
                .thenAnswer(invocation -> readableFile(invocation.getArgument(0)));

        new TaskParseService(fileService, fileStorage, fileParseService).parseFiles(TASK_ID);

        // segmentId 的 f{fileIndex} 与批次编址都依赖这个顺序，库返回顺序不作数。
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(fileStorage);
        order.verify(fileStorage).read("key-a");
        order.verify(fileStorage).read("key-b");
    }

    @Test
    void nullFileIndexShouldFailWithColumnNamesInsteadOfBareNpe() {
        CtHealthReportFileService fileService = mock(CtHealthReportFileService.class);
        S3FileStorage fileStorage = mock(S3FileStorage.class);
        FileParseService fileParseService = mock(FileParseService.class);
        // file_index 在 DDL 里可为 NULL：上传时置空、绑定时才写。
        when(fileService.findByTaskId(TASK_ID))
                .thenReturn(Collections.singletonList(fileEntity("a", null)));

        assertThatThrownBy(() -> new TaskParseService(fileService, fileStorage, fileParseService)
                .parseFiles(TASK_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fileIndex");
        // 校验必须在读对象之前，不能先把文件从 S3 拉下来再发现绑定不完整。
        verify(fileStorage, never()).read(anyString());
    }

    @Test
    void unknownContentTypeShouldBeUnsupportedFormatNotServerError() {
        CtHealthReportFileService fileService = mock(CtHealthReportFileService.class);
        S3FileStorage fileStorage = mock(S3FileStorage.class);
        FileParseService fileParseService = mock(FileParseService.class);
        CtHealthReportFileEntity entity = fileEntity("a", 0);
        entity.setContentType("HEIC");
        when(fileService.findByTaskId(TASK_ID)).thenReturn(Collections.singletonList(entity));
        when(fileStorage.read(anyString())).thenReturn(new byte[]{1});

        assertThatThrownBy(() -> new TaskParseService(fileService, fileStorage, fileParseService)
                .parseFiles(TASK_ID))
                .isInstanceOfSatisfying(HealthReportException.class, exception ->
                        assertThat(exception.getFailCode()).isEqualTo(FailCode.UNSUPPORTED_FORMAT));
    }

    @Test
    void emptyFileListShouldBeUnreadable() {
        CtHealthReportFileService fileService = mock(CtHealthReportFileService.class);
        when(fileService.findByTaskId(TASK_ID)).thenReturn(Collections.<CtHealthReportFileEntity>emptyList());

        assertThatThrownBy(() -> new TaskParseService(fileService, mock(S3FileStorage.class),
                mock(FileParseService.class)).parseFiles(TASK_ID))
                .isInstanceOfSatisfying(HealthReportException.class, exception ->
                        assertThat(exception.getFailCode()).isEqualTo(FailCode.UNREADABLE));
    }

    /** ParsedFile 是 final 类，Mockito 造不出来，也不该为测试放开 final。 */
    private ParsedFile readableFile(int fileIndex) {
        Segment segment = new Segment(Segment.id(fileIndex, 1, 0), "血脂",
                "血脂", TextSource.OCR, null);
        ParsedPage page = new ParsedPage(1, Collections.singletonList(segment), null, false);
        return new ParsedFile(fileIndex, ContentType.PDF, 1, Collections.singletonList(page));
    }

    private CtHealthReportFileEntity fileEntity(String suffix, Integer fileIndex) {
        CtHealthReportFileEntity entity = new CtHealthReportFileEntity();
        entity.setFileId("file-" + suffix);
        entity.setTaskId(TASK_ID);
        entity.setFileIndex(fileIndex);
        entity.setPrecheckPages(1);
        entity.setContentType(ContentType.PDF.name());
        entity.setCloudFileKey("key-" + suffix);
        return entity;
    }
}
