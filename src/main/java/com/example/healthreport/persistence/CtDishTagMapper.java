package com.example.healthreport.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

/**
 * {@code ct_dish_tag} 的 MyBatis-Plus Mapper。
 */
@Mapper
public interface CtDishTagMapper extends BaseMapper<CtDishTagEntity> {

    /** 按调用方算出的严格截止日分批清理过期标签；DDL 无级联或触发器兜底。 */
    int deleteExpiredBatch(@Param("cutoffDate") LocalDate cutoffDate);
}
