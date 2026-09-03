package com.example.healthreport.task;

import com.example.healthreport.persistence.CtHealthReportFileEntity;
import com.example.healthreport.persistence.CtHealthReportFileService;
import com.example.healthreport.persistence.FileBindingRecord;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 建任务容量预检、重复绑定与顺序绑定边界测试。
 */
class FileBindingServiceTest {

	private static final String FILE_1 = "00000000-0000-0000-0000-000000000001";

	private static final String FILE_2 = "00000000-0000-0000-0000-000000000002";

	private static final String NEW_TASK = "00000000-0000-0000-0000-000000000003";

	private static final String OLD_TASK = "00000000-0000-0000-0000-000000000004";

	private static final String USER_ID = "case-sensitive-user";

	private static final String COMPANY_ID = "company-a";

	private CtHealthReportFileService fileService;

	private FileOwnershipGuard ownershipGuard;

	private FileBindingService service;

	@BeforeEach
	void setUp() {
		fileService = mock(CtHealthReportFileService.class);
		ownershipGuard = spy(
				new FileOwnershipGuard(fileService, new com.example.healthreport.support.IdCanonicalizer()));
		service = new FileBindingService(fileService, ownershipGuard,
				Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC));
	}

	@Test
	void shouldFailTransactionOutPrecheckAtThirtyOnePagesWithoutLockOrWrite() {
		List<String> fileIdList = Arrays.asList(FILE_1, FILE_2);
		List<FileBindingRecord> recordList = Arrays.asList(record(FILE_1, 15), record(FILE_2, 16));
		when(fileService.findForPrecheck(fileIdList, USER_ID, COMPANY_ID)).thenReturn(recordList);

		assertFailCode(() -> service.precheckFiles(fileIdList, USER_ID, COMPANY_ID), FailCode.PAGE_LIMIT_EXCEEDED);
		verify(fileService, never()).lockForBinding(anyList(), anyString(), anyString());
		verify(fileService, never()).bindConditionally(anyString(), anyString(), anyString(), any(), anyString(),
				org.mockito.ArgumentMatchers.anyInt(), any(LocalDateTime.class));
	}

	@Test
	void shouldAllowExactlyThirtyPages() {
		List<String> fileIdList = Arrays.asList(FILE_1, FILE_2);
		List<FileBindingRecord> recordList = Arrays.asList(record(FILE_1, 15), record(FILE_2, 15));
		when(fileService.findForPrecheck(fileIdList, USER_ID, COMPANY_ID)).thenReturn(recordList);

		service.precheckFiles(fileIdList, USER_ID, COMPANY_ID);
		verify(fileService).findForPrecheck(fileIdList, USER_ID, COMPANY_ID);
		verify(ownershipGuard).assertOwnedRecords(fileIdList, recordList, USER_ID, COMPANY_ID);
	}

	@Test
	void shouldApplySixtyMegabyteAggregateBoundary() {
		FileBindingRecord boundary = record(FILE_1, 1);
		boundary.setSizeBytes(60L * 1024L * 1024L);
		when(fileService.findForPrecheck(Collections.singletonList(FILE_1), USER_ID, COMPANY_ID))
			.thenReturn(Collections.singletonList(boundary));
		service.precheckFiles(Collections.singletonList(FILE_1), USER_ID, COMPANY_ID);

		boundary.setSizeBytes(60L * 1024L * 1024L + 1L);
		assertFailCode(() -> service.precheckFiles(Collections.singletonList(FILE_1), USER_ID, COMPANY_ID),
				FailCode.FILE_TOO_LARGE);
	}

	@Test
	void shouldAcceptOneToFiveFilesAndRejectOutsideRange() {
		List<String> fiveFileIdList = IntStream.rangeClosed(1, 5)
			.mapToObj(index -> String.format("00000000-0000-0000-0000-%012d", index))
			.collect(Collectors.toList());
		List<FileBindingRecord> fiveRecordList = fiveFileIdList.stream()
			.map(fileId -> record(fileId, 1))
			.collect(Collectors.toList());
		when(fileService.findForPrecheck(fiveFileIdList, USER_ID, COMPANY_ID)).thenReturn(fiveRecordList);
		service.precheckFiles(fiveFileIdList, USER_ID, COMPANY_ID);

		assertThatThrownBy(() -> service.precheckFiles(Collections.<String>emptyList(), USER_ID, COMPANY_ID))
			.isInstanceOf(IllegalArgumentException.class);
		List<String> sixFileIdList = IntStream.rangeClosed(1, 6)
			.mapToObj(index -> String.format("00000000-0000-0000-0000-%012d", index))
			.collect(Collectors.toList());
		assertThatThrownBy(() -> service.precheckFiles(sixFileIdList, USER_ID, COMPANY_ID))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void shouldRejectExpiredAndLiveBoundFiles() {
		FileBindingRecord expired = record(FILE_1, 1);
		expired.setExpireAt(LocalDateTime.of(2026, 8, 26, 0, 0));
		when(fileService.findForPrecheck(Collections.singletonList(FILE_1), USER_ID, COMPANY_ID))
			.thenReturn(Collections.singletonList(expired));
		assertFailCode(() -> service.precheckFiles(Collections.singletonList(FILE_1), USER_ID, COMPANY_ID),
				FailCode.FILE_EXPIRED);

		FileBindingRecord bound = record(FILE_1, 1);
		bound.setTaskId(OLD_TASK);
		bound.setBoundTaskStatus(TaskStatus.QUEUED.name());
		when(fileService.findForPrecheck(Collections.singletonList(FILE_1), USER_ID, COMPANY_ID))
			.thenReturn(Collections.singletonList(bound));
		assertThatThrownBy(() -> service.precheckFiles(Collections.singletonList(FILE_1), USER_ID, COMPANY_ID))
			.isInstanceOfSatisfying(HealthReportException.class, exception -> {
				assertThat(exception.getFailCode()).isEqualTo(FailCode.FILE_ALREADY_BOUND);
				assertThat(exception.getTaskId()).isEqualTo(OLD_TASK);
			});
	}

	@Test
	void shouldBindInRequestOrderAndAllowReanalyzableFailure() {
		FileBindingRecord first = record(FILE_2, 1);
		FileBindingRecord second = record(FILE_1, 1);
		second.setTaskId(OLD_TASK);
		second.setBoundTaskStatus(TaskStatus.FAILED.name());
		second.setBoundTaskReanalyzable(Boolean.TRUE);
		List<String> fileIdList = Arrays.asList(FILE_2, FILE_1);
		when(fileService.lockForBinding(fileIdList, USER_ID, COMPANY_ID)).thenReturn(Arrays.asList(first, second));
		when(fileService.bindConditionally(anyString(), eq(USER_ID), eq(COMPANY_ID), any(), eq(NEW_TASK),
				org.mockito.ArgumentMatchers.anyInt(), any(LocalDateTime.class)))
			.thenReturn(1);

		assertThat(service.bindFiles(fileIdList, NEW_TASK, USER_ID, COMPANY_ID)).isEqualTo(2);
		verify(ownershipGuard).assertOwnedRecords(fileIdList, Arrays.asList(first, second), USER_ID, COMPANY_ID);
		verify(fileService).bindConditionally(eq(FILE_2), eq(USER_ID), eq(COMPANY_ID), eq(null), eq(NEW_TASK), eq(0),
				any(LocalDateTime.class));
		verify(fileService).bindConditionally(eq(FILE_1), eq(USER_ID), eq(COMPANY_ID), eq(OLD_TASK), eq(NEW_TASK),
				eq(1), any(LocalDateTime.class));
	}

	@Test
	void shouldReturnCurrentTaskIdWhenConditionalUpdateLosesRace() {
		when(fileService.lockForBinding(Collections.singletonList(FILE_1), USER_ID, COMPANY_ID))
			.thenReturn(Collections.singletonList(record(FILE_1, 1)));
		when(fileService.bindConditionally(anyString(), anyString(), anyString(), any(), anyString(),
				org.mockito.ArgumentMatchers.anyInt(), any(LocalDateTime.class)))
			.thenReturn(0);
		CtHealthReportFileEntity current = new CtHealthReportFileEntity();
		current.setTaskId(OLD_TASK);
		when(fileService.findByFileId(FILE_1)).thenReturn(current);

		assertThatThrownBy(() -> service.bindFiles(Collections.singletonList(FILE_1), NEW_TASK, USER_ID, COMPANY_ID))
			.isInstanceOfSatisfying(HealthReportException.class, exception -> {
				assertThat(exception.getFailCode()).isEqualTo(FailCode.FILE_ALREADY_BOUND);
				assertThat(exception.getTaskId()).isEqualTo(OLD_TASK);
			});
	}

	private FileBindingRecord record(String fileId, int pages) {
		FileBindingRecord record = new FileBindingRecord();
		record.setFileId(fileId);
		record.setCompanyId(COMPANY_ID);
		record.setUserId(USER_ID);
		record.setStatus(FileStatus.UPLOADED.name());
		record.setSizeBytes(1024L);
		record.setPrecheckPages(pages);
		record.setExpireAt(LocalDateTime.of(2026, 8, 26, 0, 30));
		return record;
	}

	private void assertFailCode(Runnable runnable, FailCode failCode) {
		assertThatThrownBy(runnable::run).isInstanceOfSatisfying(HealthReportException.class,
				exception -> assertThat(exception.getFailCode()).isEqualTo(failCode));
	}

}
