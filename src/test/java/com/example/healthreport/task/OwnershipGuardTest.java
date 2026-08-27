package com.example.healthreport.task;

import com.example.healthreport.persistence.CtHealthReportFileEntity;
import com.example.healthreport.persistence.CtHealthReportFileService;
import com.example.healthreport.persistence.CtHealthReportTaskEntity;
import com.example.healthreport.persistence.CtHealthReportTaskService;
import com.example.healthreport.persistence.FileBindingRecord;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.IdCanonicalizer;
import com.example.healthreport.support.OwnershipException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 归属精确比较与统一失败形状测试。
 */
class OwnershipGuardTest {

    private static final String RESOURCE_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String CURRENT_USER_ID = "CaseUser";
    private static final LocalDateTime CURRENT_TIME = LocalDateTime.of(2026, 1, 1, 0, 0);

    private CtHealthReportTaskService taskService;
    private CtHealthReportFileService fileService;
    private TaskOwnershipGuard taskOwnershipGuard;
    private FileOwnershipGuard fileOwnershipGuard;

    @BeforeEach
    void setUp() {
        taskService = mock(CtHealthReportTaskService.class);
        fileService = mock(CtHealthReportFileService.class);
        IdCanonicalizer idCanonicalizer = new IdCanonicalizer();
        taskOwnershipGuard = new TaskOwnershipGuard(taskService, idCanonicalizer,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        fileOwnershipGuard = new FileOwnershipGuard(fileService, idCanonicalizer);
    }

    @Test
    void shouldReturnOwnedLiveTask() {
        CtHealthReportTaskEntity taskEntity = liveTask(CURRENT_USER_ID);
        when(taskService.findByTaskId(RESOURCE_ID)).thenReturn(taskEntity);

        assertSame(taskEntity, taskOwnershipGuard.assertOwned(RESOURCE_ID, CURRENT_USER_ID));
    }

    @Test
    void taskUserIdComparisonShouldBeCaseSensitive() {
        when(taskService.findByTaskId(RESOURCE_ID)).thenReturn(liveTask("caseuser"));

        assertThrows(OwnershipException.class,
                () -> taskOwnershipGuard.assertOwned(RESOURCE_ID, CURRENT_USER_ID));
    }

    @Test
    void fileUserIdComparisonShouldBeCaseSensitive() {
        CtHealthReportFileEntity fileEntity = new CtHealthReportFileEntity();
        fileEntity.setFileId(RESOURCE_ID);
        fileEntity.setUserId("caseuser");
        fileEntity.setExpireAt(CURRENT_TIME.plusMinutes(1));
        when(fileService.findByFileId(RESOURCE_ID)).thenReturn(fileEntity);

        assertThrows(OwnershipException.class,
                () -> fileOwnershipGuard.assertOwned(RESOURCE_ID, CURRENT_USER_ID));
    }

    @Test
    void expiredFileWithExactOwnerShouldPassOwnershipGuard() {
        CtHealthReportFileEntity fileEntity = new CtHealthReportFileEntity();
        fileEntity.setFileId(RESOURCE_ID);
        fileEntity.setUserId(CURRENT_USER_ID);
        fileEntity.setExpireAt(CURRENT_TIME.minusSeconds(1));
        when(fileService.findByFileId(RESOURCE_ID)).thenReturn(fileEntity);

        assertSame(fileEntity, fileOwnershipGuard.assertOwned(RESOURCE_ID, CURRENT_USER_ID));
    }

    @Test
    void batchRecordUserIdComparisonShouldBeCaseSensitive() {
        FileBindingRecord record = bindingRecord(RESOURCE_ID, "caseuser");

        assertThrows(OwnershipException.class, () -> fileOwnershipGuard.assertOwnedRecords(
                Collections.singletonList(RESOURCE_ID), Collections.singletonList(record), CURRENT_USER_ID));
    }

    @Test
    void batchRecordLookupShouldRejectMissingFile() {
        assertThrows(OwnershipException.class, () -> fileOwnershipGuard.assertOwnedRecords(
                Collections.singletonList(RESOURCE_ID), Collections.<FileBindingRecord>emptyList(), CURRENT_USER_ID));
    }

    @Test
    void batchRecordLookupShouldRestoreRequestOrder() {
        String secondId = "123e4567-e89b-12d3-a456-426614174001";
        FileBindingRecord first = bindingRecord(RESOURCE_ID, CURRENT_USER_ID);
        FileBindingRecord second = bindingRecord(secondId, CURRENT_USER_ID);

        List<FileBindingRecord> orderedRecordList = fileOwnershipGuard.assertOwnedRecords(
                Arrays.asList(RESOURCE_ID, secondId), Arrays.asList(second, first), CURRENT_USER_ID);

        assertSame(first, orderedRecordList.get(0));
        assertSame(second, orderedRecordList.get(1));
    }

    @Test
    void fourTaskVisibilityFailuresShouldHaveIdenticalExternalShape() {
        when(taskService.findByTaskId(RESOURCE_ID)).thenReturn(null);
        String absentShape = failureShape();

        when(taskService.findByTaskId(RESOURCE_ID)).thenReturn(liveTask("DifferentUser"));
        String ownerMismatchShape = failureShape();

        CtHealthReportTaskEntity deletedTask = liveTask(CURRENT_USER_ID);
        deletedTask.setDeletedAt(CURRENT_TIME.minusSeconds(1));
        when(taskService.findByTaskId(RESOURCE_ID)).thenReturn(deletedTask);
        String deletedShape = failureShape();

        CtHealthReportTaskEntity expiredTask = liveTask(CURRENT_USER_ID);
        expiredTask.setExpireAt(CURRENT_TIME);
        when(taskService.findByTaskId(RESOURCE_ID)).thenReturn(expiredTask);
        String expiredShape = failureShape();

        assertEquals(absentShape, ownerMismatchShape);
        assertEquals(absentShape, deletedShape);
        assertEquals(absentShape, expiredShape);
        assertEquals("404|RESULT_EXPIRED|资源不存在或已失效", absentShape);
    }

    private String failureShape() {
        OwnershipException exception = assertThrows(OwnershipException.class,
                () -> taskOwnershipGuard.assertOwned(RESOURCE_ID, CURRENT_USER_ID));
        assertEquals(FailCode.RESULT_EXPIRED, exception.getFailCode());
        return exception.getHttpStatus() + "|" + exception.getFailCode().name()
                + "|" + exception.getMessage();
    }

    private static CtHealthReportTaskEntity liveTask(String userId) {
        CtHealthReportTaskEntity taskEntity = new CtHealthReportTaskEntity();
        taskEntity.setTaskId(RESOURCE_ID);
        taskEntity.setUserId(userId);
        taskEntity.setExpireAt(CURRENT_TIME.plusMinutes(1));
        return taskEntity;
    }

    private static FileBindingRecord bindingRecord(String fileId, String userId) {
        FileBindingRecord record = new FileBindingRecord();
        record.setFileId(fileId);
        record.setUserId(userId);
        return record;
    }
}
