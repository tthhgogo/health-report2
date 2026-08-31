package com.example.healthreport.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code ct_health_report_task} 的 MyBatis-Plus Mapper。
 */
@Mapper
public interface CtHealthReportTaskMapper extends BaseMapper<CtHealthReportTaskEntity> {

	/** 工作线程领取 QUEUED 任务，同时固定十分钟硬截止。 */
	int claim(@Param("taskId") String taskId, @Param("heartbeatAt") LocalDateTime heartbeatAt,
			@Param("deadlineAt") LocalDateTime deadlineAt, @Param("updateBy") String updateBy);

	/** 按纯单向状态机进入下一阶段，所有工作线程写回均受删除标志保护。 */
	int transition(@Param("taskId") String taskId, @Param("expectedStatus") String expectedStatus,
			@Param("nextStatus") String nextStatus, @Param("stage") String stage, @Param("progress") int progress,
			@Param("updateBy") String updateBy);

	/** 独立调度线程只更新心跳，不顺延 deadline_at。 */
	int heartbeat(@Param("taskId") String taskId);

	/** 非终态任务进入失败终态，终态和删除后的任务不可被覆盖。 */
	int failActive(@Param("taskId") String taskId, @Param("failCode") String failCode,
			@Param("reanalyzable") boolean reanalyzable, @Param("updateBy") String updateBy);

	/** 成功 CAS 失败后，仅把确实超过硬截止的 ASSEMBLING 任务判为超时。 */
	int failExpiredAssembling(@Param("taskId") String taskId, @Param("currentTime") LocalDateTime currentTime,
			@Param("updateBy") String updateBy);

	/** B5：只在未删除的非终态任务上写入成对的 partial 字段。 */
	int markPartial(@Param("taskId") String taskId, @Param("partialReason") String partialReason,
			@Param("updateBy") String updateBy);

	/** MySQL 是成功可见性的唯一提交点，同时把任务有效期顺延两小时。 */
	int succeed(@Param("taskId") String taskId, @Param("currentTime") LocalDateTime currentTime,
			@Param("expireAt") LocalDateTime expireAt, @Param("updateBy") String updateBy);

	/** B7：删除标志只允许从 NULL 写成时间，永远没有清空路径。 */
	int markDeleted(@Param("taskId") String taskId, @Param("userId") String userId,
			@Param("companyId") String companyId, @Param("updateBy") String updateBy);

	/** 心跳超过十五分钟的执行中任务按服务端故障收敛。 */
	int failHeartbeatTimeout(@Param("heartbeatThreshold") LocalDateTime heartbeatThreshold,
			@Param("updateBy") String updateBy);

	/** 超过硬截止的执行中任务使用独立的 EXECUTION_TIMEOUT 失败码。 */
	int failDeadlineTimeout(@Param("currentTime") LocalDateTime currentTime, @Param("updateBy") String updateBy);

	/** 提交线程池前崩溃导致的陈旧 QUEUED 任务按服务端故障收敛。 */
	int failQueuedTimeout(@Param("queuedThreshold") LocalDateTime queuedThreshold, @Param("updateBy") String updateBy);

	/**
	 * 查询按六类清理矩阵需要处理文件或结果的任务；执行中任务即使过期也不得入选。
	 *
	 * <p>
	 * <b>WHERE 故意写得宽，收窄条件的活不在这里做。</b> 精确的六类判断只有 {@code CleanupJob.cleanupTask} 一处；把它复制成
	 * SQL 谓词（例如再加一个 {@code EXISTS} 去查还有没有文件行）就变成两处判断，迟早判得不一样。 {@code SUCCEEDED}
	 * 之所以无条件入选，正是为了兜住「成功那一刻删文件失败」的重试路径。
	 * </p>
	 *
	 * <p>
	 * <b>{@code ORDER BY expire_at} 不只是为了稳定顺序</b>：已过期的 {@code expire_at} 小、 排在前面，非过期的
	 * {@code SUCCEEDED} 是成功时刻 +2h、排在最后。于是真正有活干的天然优先， 无所事事的那批落在 {@code LIMIT}
	 * 之外——单轮上限与减少空转由同一个改动一起解决。
	 * </p>
	 *
	 * <p>
	 * <b>代价（不是白捡的）</b>：非过期 {@code SUCCEEDED} 且当初删文件失败的那种，
	 * 重试会被推后，最坏等到它过期（≤2h）才轮到。这是用「原文件多留最多两小时」 换「清理链路在存储故障下不会自己噎死」，取舍见
	 * {@link com.example.healthreport.task.CleanupProperties}。
	 * </p>
	 */
	List<CtHealthReportTaskEntity> selectCleanupCandidates(@Param("currentTime") LocalDateTime currentTime,
			@Param("batchSize") int batchSize);

}
