package com.example.healthreport.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code ct_health_report_file} 的 MyBatis-Plus Mapper。
 */
@Mapper
public interface CtHealthReportFileMapper extends BaseMapper<CtHealthReportFileEntity> {

	/**
	 * 事务外一次读取全部预检字段；Java 仍会逐行做区分大小写的精确归属校验。
	 */
	List<FileBindingRecord> selectForPrecheck(@Param("fileIdList") List<String> fileIdList,
			@Param("userId") String userId, @Param("companyId") String companyId);

	/**
	 * 事务内只锁文件行；{@code OF f} 不得删除，否则会连带锁住旧任务行。
	 */
	List<FileBindingRecord> selectForUpdate(@Param("fileIdList") List<String> fileIdList,
			@Param("userId") String userId, @Param("companyId") String companyId);

	/**
	 * 查询已过期且从未绑定任务的孤儿上传。
	 *
	 * <p>
	 * 与任务候选查询同一条理由需要 {@code LIMIT}：对象存储不可用时孤儿行同样删不掉、
	 * 同样会无限堆积，而巡检本身会被它拖垮。最旧的先处理，保证积压能确定性地排空。
	 * </p>
	 */
	List<CtHealthReportFileEntity> selectExpiredOrphans(@Param("currentTime") LocalDateTime currentTime,
			@Param("batchSize") int batchSize);

}
