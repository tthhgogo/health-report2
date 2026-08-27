package com.example.healthreport.persistence;

import com.example.healthreport.support.SystemActor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据库操作 Service 的固定审计身份测试。
 */
class SystemActorServiceTest {

    @Test
    void taskServiceShouldForceApiAndWorkerActors() {
        CtHealthReportTaskMapper mapper = mock(CtHealthReportTaskMapper.class);
        when(mapper.insert(any(CtHealthReportTaskEntity.class))).thenReturn(1);
        when(mapper.updateById(any(CtHealthReportTaskEntity.class))).thenReturn(1);
        CtHealthReportTaskService service = new CtHealthReportTaskService(mapper);
        CtHealthReportTaskEntity entity = new CtHealthReportTaskEntity();
        entity.setCreateBy("external-value");
        entity.setUpdateBy("external-value");

        assertEquals(1, service.insertFromApi(entity));
        assertEquals(SystemActor.HEALTH_REPORT_API, entity.getCreateBy());
        assertEquals(SystemActor.HEALTH_REPORT_API, entity.getUpdateBy());

        assertEquals(1, service.updateFromWorker(entity));
        assertEquals(SystemActor.HEALTH_REPORT_WORKER, entity.getUpdateBy());
        verify(mapper).insert(entity);
        verify(mapper).updateById(entity);
    }

    @Test
    void fileServiceShouldForceApiActor() {
        CtHealthReportFileMapper mapper = mock(CtHealthReportFileMapper.class);
        when(mapper.insert(any(CtHealthReportFileEntity.class))).thenReturn(1);
        when(mapper.updateById(any(CtHealthReportFileEntity.class))).thenReturn(1);
        CtHealthReportFileService service = new CtHealthReportFileService(mapper);
        CtHealthReportFileEntity entity = new CtHealthReportFileEntity();

        assertEquals(1, service.insertFromApi(entity));
        assertEquals(SystemActor.HEALTH_REPORT_API, entity.getCreateBy());
        assertEquals(SystemActor.HEALTH_REPORT_API, entity.getUpdateBy());

        assertEquals(1, service.updateFromApi(entity));
        assertEquals(SystemActor.HEALTH_REPORT_API, entity.getUpdateBy());
    }

    @Test
    void dishTagServiceShouldForceJobActor() {
        CtDishTagMapper mapper = mock(CtDishTagMapper.class);
        when(mapper.insert(any(CtDishTagEntity.class))).thenReturn(1);
        when(mapper.update(any(CtDishTagEntity.class), any())).thenReturn(1);
        CtDishTagService service = new CtDishTagService(mapper);
        CtDishTagEntity entity = new CtDishTagEntity();
        entity.setDishId(1L);
        entity.setTagHash("hash");
        entity.setEnumKey("ENUM_KEY");

        assertEquals(1, service.insertFromJob(entity));
        assertEquals(SystemActor.DISH_TAG_JOB, entity.getCreateBy());
        assertEquals(SystemActor.DISH_TAG_JOB, entity.getUpdateBy());

        assertEquals(1, service.updateFromJob(entity));
        assertEquals(SystemActor.DISH_TAG_JOB, entity.getUpdateBy());
    }
}
