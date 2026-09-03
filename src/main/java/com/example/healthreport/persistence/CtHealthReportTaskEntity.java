package com.example.healthreport.persistence;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.PartialReason;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * {@code ct_health_report_task} 任务状态真源实体。
 * <p>
 * 不承载报告正文、识别文本或结构化健康结论。
 * </p>
 */
@Data
@TableName("ct_health_report_task")
public class CtHealthReportTaskEntity {

	@TableId(value = "task_id", type = IdType.INPUT)
	private String taskId;

	private String companyId;

	private String userId;

	private String status;

	private String stage;

	private Integer progress;

	private FailCode failCode;

	private Boolean reanalyzable;

	private Boolean partial;

	private PartialReason partialReason;

	private LocalDateTime heartbeatAt;

	private LocalDateTime deadlineAt;

	private LocalDateTime expireAt;

	private LocalDateTime deletedAt;

	@Version
	private Integer version;

	/** 创建时间完全由数据库维护，禁止进入 insert/update SQL。 */
	@TableField(value = "create_time", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
	private LocalDateTime createTime;

	/** 创建人只能在插入时由 Service 写固定系统标识，更新时禁止改写。 */
	@TableField(value = "create_by", updateStrategy = FieldStrategy.NEVER)
	private String createBy;

	/** 更新时间完全由数据库维护，禁止进入 insert/update SQL。 */
	@TableField(value = "update_time", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
	private LocalDateTime updateTime;

	private String updateBy;

}
