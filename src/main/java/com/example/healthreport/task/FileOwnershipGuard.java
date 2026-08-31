package com.example.healthreport.task;

import com.example.healthreport.persistence.CtHealthReportFileEntity;
import com.example.healthreport.persistence.CtHealthReportFileService;
import com.example.healthreport.persistence.FileBindingRecord;
import com.example.healthreport.support.IdCanonicalizer;
import com.example.healthreport.support.OwnershipException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件归属校验入口。
 * <p>
 * 查询只按 file_id 取行，随后在 Java 中对 user_id 做区分大小写的精确比较。
 * </p>
 * <p>
 * 文件状态、有效期与可绑定性由文件绑定服务统一判断，本类不消费这些业务条件。
 * </p>
 */
@Component
public class FileOwnershipGuard {

	private final CtHealthReportFileService fileService;

	private final IdCanonicalizer idCanonicalizer;

	/**
	 * 创建文件归属校验器。
	 */
	@Autowired
	public FileOwnershipGuard(CtHealthReportFileService fileService, IdCanonicalizer idCanonicalizer) {
		this.fileService = fileService;
		this.idCanonicalizer = idCanonicalizer;
	}

	/**
	 * 校验一个文件属于当前用户。
	 * @return 已通过精确归属校验的文件行
	 */
	public CtHealthReportFileEntity assertOwned(String fileId, String currentUserId, String currentCompanyId) {
		idCanonicalizer.canonicalize(fileId);
		CtHealthReportFileEntity fileEntity = fileService.findByFileId(fileId);
		if (fileEntity == null || !fileId.equals(fileEntity.getFileId()) || currentUserId == null
				|| !currentUserId.equals(fileEntity.getUserId()) || currentCompanyId == null
				|| !currentCompanyId.equals(fileEntity.getCompanyId())) {
			throw new OwnershipException();
		}
		return fileEntity;
	}

	/**
	 * 按提交顺序逐个校验文件归属，不依赖数据库大小写不敏感的 user_id 条件。
	 * @return 与 fileIdList 顺序一致的文件行
	 */
	public List<CtHealthReportFileEntity> assertOwned(List<String> fileIdList, String currentUserId,
			String currentCompanyId) {
		if (fileIdList == null) {
			throw new OwnershipException();
		}
		List<CtHealthReportFileEntity> resultList = new ArrayList<>(fileIdList.size());
		for (String fileId : fileIdList) {
			resultList.add(assertOwned(fileId, currentUserId, currentCompanyId));
		}
		return resultList;
	}

	/**
	 * 对一次批量查询返回的绑定快照逐行做精确归属校验，并恢复请求顺序。
	 * <p>
	 * 本方法不再访问数据库，供事务外预检与事务内锁行校验各调用一次。
	 * </p>
	 */
	public List<FileBindingRecord> assertOwnedRecords(List<String> fileIdList, List<FileBindingRecord> recordList,
			String currentUserId, String currentCompanyId) {
		if (fileIdList == null || recordList == null || currentUserId == null || currentCompanyId == null) {
			throw new OwnershipException();
		}
		Map<String, FileBindingRecord> recordMap = new HashMap<String, FileBindingRecord>(recordList.size());
		for (FileBindingRecord record : recordList) {
			if (record != null && record.getFileId() != null) {
				recordMap.put(record.getFileId(), record);
			}
		}
		List<FileBindingRecord> orderedRecordList = new ArrayList<FileBindingRecord>(fileIdList.size());
		for (String fileId : fileIdList) {
			idCanonicalizer.canonicalize(fileId);
			FileBindingRecord record = recordMap.get(fileId);
			if (record == null || !fileId.equals(record.getFileId()) || !currentUserId.equals(record.getUserId())
					|| !currentCompanyId.equals(record.getCompanyId())) {
				throw new OwnershipException();
			}
			orderedRecordList.add(record);
		}
		return orderedRecordList;
	}

}
