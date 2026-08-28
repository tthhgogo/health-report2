package com.example.healthreport.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.healthreport.support.SystemActor;
import com.example.healthreport.task.FileStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code ct_health_report_file} 的唯一数据库操作 Service。
 */
@Service
public class CtHealthReportFileService {

    private final CtHealthReportFileMapper fileMapper;

    public CtHealthReportFileService(CtHealthReportFileMapper fileMapper) {
        this.fileMapper = fileMapper;
    }

    /**
     * 按文件主键读取；归属必须由 FileOwnershipGuard 在 Java 中精确比较。
     */
    public CtHealthReportFileEntity findByFileId(String fileId) {
        return fileMapper.selectById(fileId);
    }

    /**
     * 在线接口写入文件元数据，强制使用固定系统身份。
     */
    public int insertFromApi(CtHealthReportFileEntity fileEntity) {
        fileEntity.setCreateBy(SystemActor.HEALTH_REPORT_API);
        fileEntity.setUpdateBy(SystemActor.HEALTH_REPORT_API);
        return fileMapper.insert(fileEntity);
    }

    /** 一次读取事务外容量预检与可绑定性所需字段。 */
    public List<FileBindingRecord> findForPrecheck(List<String> fileIdList, String userId) {
        return fileMapper.selectForPrecheck(fileIdList, userId);
    }

    /** 事务内使用 {@code FOR UPDATE OF f} 只锁文件行。 */
    public List<FileBindingRecord> lockForBinding(List<String> fileIdList, String userId) {
        return fileMapper.selectForUpdate(fileIdList, userId);
    }

    /** 执行带旧任务 ID 的条件绑定更新。 */
    public int bindConditionally(String fileId, String userId, String oldTaskId, String newTaskId,
                                 int fileIndex, LocalDateTime expireAt) {
        LambdaUpdateWrapper<CtHealthReportFileEntity> updateWrapper =
                new LambdaUpdateWrapper<CtHealthReportFileEntity>();
        updateWrapper.set(CtHealthReportFileEntity::getTaskId, newTaskId)
                .set(CtHealthReportFileEntity::getFileIndex, fileIndex)
                .set(CtHealthReportFileEntity::getExpireAt, expireAt)
                .set(CtHealthReportFileEntity::getUpdateBy, SystemActor.HEALTH_REPORT_API)
                .eq(CtHealthReportFileEntity::getFileId, fileId)
                .eq(CtHealthReportFileEntity::getUserId, userId)
                .eq(CtHealthReportFileEntity::getStatus, FileStatus.UPLOADED.name());
        if (oldTaskId == null) {
            updateWrapper.isNull(CtHealthReportFileEntity::getTaskId);
        } else {
            updateWrapper.and(taskWrapper -> taskWrapper
                    .isNull(CtHealthReportFileEntity::getTaskId)
                    .or()
                    .eq(CtHealthReportFileEntity::getTaskId, oldTaskId));
        }
        return fileMapper.update(null, updateWrapper);
    }

    /**
     * 查询任务关联的全部 file 行，不加行锁，避免对象存储调用期间长时间持锁。
     */
    public List<CtHealthReportFileEntity> findByTaskId(String taskId) {
        LambdaQueryWrapper<CtHealthReportFileEntity> queryWrapper =
                new LambdaQueryWrapper<CtHealthReportFileEntity>();
        queryWrapper.eq(CtHealthReportFileEntity::getTaskId, taskId)
                .orderByAsc(CtHealthReportFileEntity::getFileIndex,
                        CtHealthReportFileEntity::getFileId);
        return fileMapper.selectList(queryWrapper);
    }

    /** 对象存储删除成功后物理删除仍属于该任务的 file 行。 */
    public int deleteByFileAndTask(String fileId, String taskId) {
        LambdaQueryWrapper<CtHealthReportFileEntity> deleteWrapper =
                new LambdaQueryWrapper<CtHealthReportFileEntity>();
        deleteWrapper.eq(CtHealthReportFileEntity::getFileId, fileId)
                .eq(CtHealthReportFileEntity::getTaskId, taskId);
        return fileMapper.delete(deleteWrapper);
    }

    /** 查询已过期孤儿上传，单轮截断且最旧优先。 */
    public List<CtHealthReportFileEntity> findExpiredOrphans(LocalDateTime currentTime, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("清理批量必须大于零");
        }
        return fileMapper.selectExpiredOrphans(currentTime, batchSize);
    }

    /** 物理删除仍未绑定任务的孤儿 file 行。 */
    public int deleteOrphan(String fileId) {
        LambdaQueryWrapper<CtHealthReportFileEntity> deleteWrapper =
                new LambdaQueryWrapper<CtHealthReportFileEntity>();
        deleteWrapper.eq(CtHealthReportFileEntity::getFileId, fileId)
                .isNull(CtHealthReportFileEntity::getTaskId);
        return fileMapper.delete(deleteWrapper);
    }
}
