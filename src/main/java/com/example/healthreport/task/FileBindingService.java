package com.example.healthreport.task;

import com.example.healthreport.persistence.CtHealthReportFileService;
import com.example.healthreport.persistence.FileBindingRecord;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 文件容量预检与原子绑定的唯一业务执行点。
 */
@Slf4j
@Service
public class FileBindingService {

	static final int MIN_FILE_COUNT = 1;
	static final int MAX_FILE_COUNT = 5;
	static final int MAX_PRECHECK_PAGES = 30;
	static final long MAX_TOTAL_BYTES = 60L * 1024L * 1024L;

	private final CtHealthReportFileService fileService;

	private final FileOwnershipGuard fileOwnershipGuard;

	private final Clock clock;

	@Autowired
	public FileBindingService(CtHealthReportFileService fileService, FileOwnershipGuard fileOwnershipGuard) {
		this(fileService, fileOwnershipGuard, Clock.systemDefaultZone());
	}

	/** 可注入时钟的构造器，仅用于确定性测试。 */
	public FileBindingService(CtHealthReportFileService fileService, FileOwnershipGuard fileOwnershipGuard,
			Clock clock) {
		this.fileService = fileService;
		this.fileOwnershipGuard = fileOwnershipGuard;
		this.clock = clock;
	}

	/**
	 * 事务外一次查询并快速失败；此处不创建任务、不更新文件。
	 */
	public void precheckFiles(List<String> fileIdList, String currentUserId, String currentCompanyId) {
		assertRequestShape(fileIdList);
		List<FileBindingRecord> recordList = fileService.findForPrecheck(fileIdList, currentUserId, currentCompanyId);
		List<FileBindingRecord> orderedRecordList = fileOwnershipGuard.assertOwnedRecords(fileIdList, recordList,
				currentUserId, currentCompanyId);
		validateRecords(orderedRecordList);
	}

	/**
	 * B1 唯一执行点：一个文件同时只能属于一个活着的任务。DDL 无兜底。
	 * @return 绑定成功的文件数
	 */
	public int bindFiles(List<String> fileIdList, String newTaskId, String currentUserId, String currentCompanyId) {
		assertRequestShape(fileIdList);
		List<FileBindingRecord> recordList = fileService.lockForBinding(fileIdList, currentUserId, currentCompanyId);
		// 归属在事务内再次精确校验；事务外预检不能替代这一判据。
		List<FileBindingRecord> orderedRecordList = fileOwnershipGuard.assertOwnedRecords(fileIdList, recordList,
				currentUserId, currentCompanyId);
		validateRecords(orderedRecordList);

		LocalDateTime boundExpireAt = LocalDateTime.now(clock).plusMinutes(30L);
		for (int fileIndex = 0; fileIndex < orderedRecordList.size(); fileIndex++) {
			FileBindingRecord record = orderedRecordList.get(fileIndex);
			int affectedRows = fileService.bindConditionally(record.getFileId(), currentUserId, currentCompanyId,
					record.getTaskId(), newTaskId, fileIndex, boundExpireAt);
			if (affectedRows != 1) {
				String boundTaskId = currentBoundTaskId(record.getFileId());
				throw new BusinessException(FailCode.FILE_ALREADY_BOUND, boundTaskId);
			}
		}
		// 绑定是 B1 唯一执行点，也是「文件从此归这个任务」的分界；成功一次记一条。
		// 后面任何一处出现「文件找不到 / 归属不对」，先来看这条有没有、文件数对不对。
		log.info("文件绑定成功，taskId={}，文件数={}", newTaskId, orderedRecordList.size());
		return orderedRecordList.size();
	}

	private void assertRequestShape(List<String> fileIdList) {
		if (fileIdList == null || fileIdList.size() < MIN_FILE_COUNT || fileIdList.size() > MAX_FILE_COUNT) {
			throw new IllegalArgumentException("fileIds数量必须为1至5");
		}
		Set<String> uniqueFileIdSet = new HashSet<String>(fileIdList);
		if (uniqueFileIdSet.size() != fileIdList.size()) {
			throw new IllegalArgumentException("fileIds不得重复");
		}
	}

	private void validateRecords(List<FileBindingRecord> orderedRecordList) {
		long totalBytes = 0L;
		long totalPages = 0L;
		LocalDateTime currentTime = LocalDateTime.now(clock);
		for (FileBindingRecord record : orderedRecordList) {
			if (!FileStatus.UPLOADED.name().equals(record.getStatus())) {
				throw new BusinessException(FailCode.SERVER_ERROR);
			}
			if (record.getExpireAt() == null || !record.getExpireAt().isAfter(currentTime)) {
				throw new BusinessException(FailCode.FILE_EXPIRED);
			}
			assertBindable(record);
			if (record.getSizeBytes() == null || record.getPrecheckPages() == null || record.getSizeBytes() < 0L
					|| record.getPrecheckPages() < 0) {
				throw new BusinessException(FailCode.SERVER_ERROR);
			}
			totalBytes += record.getSizeBytes();
			totalPages += record.getPrecheckPages();
			if (totalPages > MAX_PRECHECK_PAGES) {
				throw new BusinessException(FailCode.PAGE_LIMIT_EXCEEDED);
			}
			if (totalBytes > MAX_TOTAL_BYTES) {
				throw new BusinessException(FailCode.FILE_TOO_LARGE);
			}
		}
	}

	private void assertBindable(FileBindingRecord record) {
		if (record.getTaskId() == null) {
			return;
		}
		boolean reanalyzableFailure = TaskStatus.FAILED.name().equals(record.getBoundTaskStatus())
				&& Boolean.TRUE.equals(record.getBoundTaskReanalyzable());
		if (!reanalyzableFailure) {
			throw new BusinessException(FailCode.FILE_ALREADY_BOUND, record.getTaskId());
		}
	}

	private String currentBoundTaskId(String fileId) {
		com.example.healthreport.persistence.CtHealthReportFileEntity current = fileService.findByFileId(fileId);
		return current == null ? null : current.getTaskId();
	}

}
