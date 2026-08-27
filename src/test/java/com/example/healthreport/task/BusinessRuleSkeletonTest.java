package com.example.healthreport.task;

import com.example.healthreport.dish.DishTagWriteService;
import com.example.healthreport.dish.TagState;
import com.example.healthreport.persistence.CtDishTagEntity;
import com.example.healthreport.persistence.CtDishTagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/** 已落地业务规则的失败显式性测试，保留原回归入口。 */
class BusinessRuleSkeletonTest {

    @Test
    void rejectWithoutEvidenceTypeShouldFailBeforeDatabaseWrite() {
        DishTagWriteService dishTagWriteService = new DishTagWriteService(
                mock(CtDishTagService.class), new ObjectMapper());
        CtDishTagEntity entity = new CtDishTagEntity();
        entity.setVerdict(TagState.REJECT.name());

        assertThrows(IllegalArgumentException.class, () -> dishTagWriteService.write(entity,
                Collections.<String>emptySet()));
    }
}
