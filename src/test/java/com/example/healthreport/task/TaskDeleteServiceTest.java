package com.example.healthreport.task;

import com.example.healthreport.persistence.CtHealthReportTaskEntity;
import com.example.healthreport.persistence.CtHealthReportTaskService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 删除四步顺序与不可逆标志的回归。 */
class TaskDeleteServiceTest {

    @Test
    void shouldMarkDeletedBeforeCleaningStoresWithoutWorkerCancellation() {
        String taskId = "123e4567-e89b-12d3-a456-426614174000";
        String userId = "CaseSensitiveUser";
        TaskOwnershipGuard ownershipGuard = mock(TaskOwnershipGuard.class);
        CtHealthReportTaskService taskService = mock(CtHealthReportTaskService.class);
        TaskResourceCleanupService cleanupService = mock(TaskResourceCleanupService.class);
        CtHealthReportTaskEntity taskEntity = new CtHealthReportTaskEntity();
        taskEntity.setTaskId(taskId);
        when(ownershipGuard.assertOwned(taskId, userId)).thenReturn(taskEntity);
        when(taskService.markDeleted(taskId, userId)).thenReturn(1);
        TaskDeleteService deleteService = new TaskDeleteService(ownershipGuard, taskService, cleanupService);

        deleteService.delete(taskId, userId);

        InOrder order = inOrder(ownershipGuard, taskService, cleanupService);
        order.verify(ownershipGuard).assertOwned(taskId, userId);
        order.verify(taskService).markDeleted(taskId, userId);
        order.verify(cleanupService).deleteFiles(taskId);
        order.verify(cleanupService).deleteResult(taskId);
    }
}
