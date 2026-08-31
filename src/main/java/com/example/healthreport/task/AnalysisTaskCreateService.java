package com.example.healthreport.task;

import com.example.healthreport.persistence.CtHealthReportTaskEntity;
import com.example.healthreport.persistence.CtHealthReportTaskService;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import com.example.healthreport.support.IdCanonicalizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 分析任务创建服务。
 * <p>
 * 事务外预检由调用方先执行；本服务在事务内插入任务并重新锁行校验、绑定文件。
 * </p>
 */
@Service
public class AnalysisTaskCreateService {

	private final CtHealthReportTaskService taskService;

	private final FileBindingService fileBindingService;

	private final IdCanonicalizer idCanonicalizer;

	private final Clock clock;

	@Autowired
	public AnalysisTaskCreateService(CtHealthReportTaskService taskService, FileBindingService fileBindingService,
			IdCanonicalizer idCanonicalizer) {
		this(taskService, fileBindingService, idCanonicalizer, Clock.systemDefaultZone());
	}

	/** 可注入时钟的构造器，仅用于确定性测试。 */
	public AnalysisTaskCreateService(CtHealthReportTaskService taskService, FileBindingService fileBindingService,
			IdCanonicalizer idCanonicalizer, Clock clock) {
		this.taskService = taskService;
		this.fileBindingService = fileBindingService;
		this.idCanonicalizer = idCanonicalizer;
		this.clock = clock;
	}

	/**
	 * 事务外容量与可绑定性预检；失败时保证尚未创建任务。
	 */
	public void precheck(List<String> fileIdList, String userId, String companyId) {
		assertOwnerContext(userId, companyId);
		fileBindingService.precheckFiles(fileIdList, userId, companyId);
	}

	/**
	 * 在同一个数据库事务中创建任务并绑定全部文件。
	 * <p>
	 * 任何绑定异常都会以 RuntimeException 传播，触发任务插入和已完成绑定整体回滚。
	 * </p>
	 */
	@Transactional(rollbackFor = Exception.class)
	public String createInTransaction(List<String> fileIdList, String userId, String companyId) {
		assertOwnerContext(userId, companyId);
		String taskId = idCanonicalizer.newTaskId();
		CtHealthReportTaskEntity taskEntity = new CtHealthReportTaskEntity();
		taskEntity.setTaskId(taskId);
		taskEntity.setCompanyId(companyId);
		taskEntity.setUserId(userId);
		taskEntity.setStatus(TaskStatus.QUEUED.name());
		taskEntity.setStage(TaskStage.UPLOADING.name());
		taskEntity.setProgress(0);
		taskEntity.setReanalyzable(Boolean.FALSE);
		taskEntity.setPartial(Boolean.FALSE);
		taskEntity.setExpireAt(LocalDateTime.now(clock).plusMinutes(30L));
		taskEntity.setVersion(0);
		int insertedRows = taskService.insertFromApi(taskEntity);
		if (insertedRows != 1) {
			throw new HealthReportException(FailCode.SERVER_ERROR, 500);
		}
		fileBindingService.bindFiles(fileIdList, taskId, userId, companyId);
		return taskId;
	}

	/** 用户与企业归属都必须在任务创建前由可信认证上下文给出。 */
	private void assertOwnerContext(String userId, String companyId) {
		if (userId == null || userId.length() == 0 || companyId == null || companyId.length() == 0) {
			throw new IllegalArgumentException("用户与企业归属不能为空");
		}
	}

}
