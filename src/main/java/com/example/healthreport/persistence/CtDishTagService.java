package com.example.healthreport.persistence;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.healthreport.support.SystemActor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * {@code ct_dish_tag} 的唯一数据库操作 Service。
 */
@Service
public class CtDishTagService {

    private final CtDishTagMapper dishTagMapper;

    public CtDishTagService(CtDishTagMapper dishTagMapper) {
        this.dishTagMapper = dishTagMapper;
    }

    /**
     * 离线任务新增标签，强制使用固定打标任务身份。
     */
    public int insertFromJob(CtDishTagEntity dishTagEntity) {
        dishTagEntity.setCreateBy(SystemActor.DISH_TAG_JOB);
        dishTagEntity.setUpdateBy(SystemActor.DISH_TAG_JOB);
        return dishTagMapper.insert(dishTagEntity);
    }

    /**
     * 批量读取候选菜、哈希和维度的已有行；调用方还需按三元组做精确筛选。
     */
    public List<CtDishTagEntity> findCandidates(Set<Long> dishIdSet, Set<String> tagHashSet,
                                                Set<String> enumKeySet) {
        if (dishIdSet.isEmpty() || tagHashSet.isEmpty() || enumKeySet.isEmpty()) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<CtDishTagEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(CtDishTagEntity::getDishId, dishIdSet)
                .in(CtDishTagEntity::getTagHash, tagHashSet)
                .in(CtDishTagEntity::getEnumKey, enumKeySet);
        return dishTagMapper.selectList(queryWrapper);
    }

    /** 命中有效组合时刷新业务日，避免清理任务误删仍在使用的标签。 */
    public int refreshLastSeen(CtDishTagEntity entity, LocalDate bizDate) {
        CtDishTagEntity updateEntity = new CtDishTagEntity();
        updateEntity.setLastSeenDate(bizDate);
        updateEntity.setUpdateBy(SystemActor.DISH_TAG_JOB);
        LambdaUpdateWrapper<CtDishTagEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(CtDishTagEntity::getDishId, entity.getDishId())
                .eq(CtDishTagEntity::getTagHash, entity.getTagHash())
                .eq(CtDishTagEntity::getEnumKey, entity.getEnumKey());
        return dishTagMapper.update(updateEntity, updateWrapper);
    }

    /** 清理一批早于业务日 30 天边界的标签。 */
    public int deleteExpiredBatch(LocalDate bizDate) {
        if (bizDate == null) {
            throw new IllegalArgumentException("清理业务日不能为空");
        }
        // 截止日由 Java 从 bizDate 算出后传入，等价于 DATE_SUB(bizDate, INTERVAL 30 DAY)。
        // 禁止在 SQL 里取数据库当前时间：跨零点执行会让清理基准与打标基准分叉。
        LocalDate cutoffDate = bizDate.minusDays(30L);
        return dishTagMapper.deleteExpiredBatch(cutoffDate);
    }
}
