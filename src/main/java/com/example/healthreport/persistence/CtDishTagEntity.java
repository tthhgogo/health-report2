package com.example.healthreport.persistence;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code ct_dish_tag} 菜品维度离线打标实体。
 * <p>只保存食堂公开菜品数据的打标结果，不保存用户或健康数据。</p>
 */
@Data
@TableName("ct_dish_tag")
public class CtDishTagEntity {

    private Long dishId;
    private String tagHash;
    private String enumKey;
    private String verdict;
    private String evidenceType;
    private String matchedIngredients;
    private String reason;
    private String modelVersion;
    private String promptVersion;
    private String tagRuleVersion;
    private LocalDate lastSeenDate;

    /** 创建时间完全由数据库维护，禁止进入 insert/update SQL。 */
    @TableField(value = "create_time", insertStrategy = FieldStrategy.NEVER,
            updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    /** 创建人只能在插入时由 Service 写固定任务标识，更新时禁止改写。 */
    @TableField(value = "create_by", updateStrategy = FieldStrategy.NEVER)
    private String createBy;

    /** 更新时间完全由数据库维护，禁止进入 insert/update SQL。 */
    @TableField(value = "update_time", insertStrategy = FieldStrategy.NEVER,
            updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;
    private String updateBy;
}
