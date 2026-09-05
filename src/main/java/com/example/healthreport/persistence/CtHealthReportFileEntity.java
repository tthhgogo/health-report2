package com.example.healthreport.persistence;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * {@code ct_health_report_file} 上传文件元数据实体。
 * <p>
 * 展示名由服务端安全生成（体检报告-{fileId前8位}.{ext}），不含任何用户输入；
 * 原始文件名（常含姓名与体检属性）从不落任何存储（2026-09-04 消除敏感例外）。
 * </p>
 */
@Data
@TableName("ct_health_report_file")
public class CtHealthReportFileEntity {

	@TableId(value = "file_id", type = IdType.INPUT)
	private String fileId;

	private String companyId;

	private String userId;

	private String taskId;

	private Integer fileIndex;

	private String status;

	private String displayName;

	private String contentType;

	private Long sizeBytes;

	private Integer precheckPages;

	private String contentHash;

	private String cloudFileKey;

	private LocalDateTime expireAt;

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
