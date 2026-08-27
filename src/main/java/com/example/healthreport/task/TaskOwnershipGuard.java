package com.example.healthreport.task;

import com.example.healthreport.persistence.CtHealthReportTaskEntity;
import com.example.healthreport.persistence.CtHealthReportTaskService;
import com.example.healthreport.support.IdCanonicalizer;
import com.example.healthreport.support.OwnershipException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 任务归属与可见性统一校验入口。
 * <p>数据库的 {@code utf8mb4_general_ci} 会忽略大小写，本类必须在 Java 中精确比较。</p>
 */
@Component
public class TaskOwnershipGuard {

    private final CtHealthReportTaskService taskService;
    private final IdCanonicalizer idCanonicalizer;
    private final Clock clock;

    /**
     * 生产构造器使用系统时钟。
     */
    @Autowired
    public TaskOwnershipGuard(CtHealthReportTaskService taskService, IdCanonicalizer idCanonicalizer) {
        this(taskService, idCanonicalizer, Clock.systemDefaultZone());
    }

    /**
     * 可注入时钟的构造器，仅用于确定性测试。
     */
    public TaskOwnershipGuard(CtHealthReportTaskService taskService, IdCanonicalizer idCanonicalizer,
                              Clock clock) {
        this.taskService = taskService;
        this.idCanonicalizer = idCanonicalizer;
        this.clock = clock;
    }

    /**
     * 断言任务属于当前用户，且未删除、未过期。
     * <p>不存在、归属不符、已删除、已过期四种情况抛出完全相同的异常，避免泄露差异。</p>
     *
     * @return 已通过精确归属校验的任务行
     */
    public CtHealthReportTaskEntity assertOwned(String taskId, String currentUserId) {
        idCanonicalizer.canonicalize(taskId);
        CtHealthReportTaskEntity taskEntity = taskService.findByTaskId(taskId);
        LocalDateTime currentTime = LocalDateTime.now(clock);
        if (taskEntity == null
                || !taskId.equals(taskEntity.getTaskId())
                || currentUserId == null
                || !currentUserId.equals(taskEntity.getUserId())
                || taskEntity.getDeletedAt() != null
                || taskEntity.getExpireAt() == null
                || !taskEntity.getExpireAt().isAfter(currentTime)) {
            throw new OwnershipException();
        }
        return taskEntity;
    }
}
