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
	public List<CtDishTagEntity> findCandidates(String companyId, Set<Long> dishIdSet, Set<String> tagHashSet,
			Set<String> enumKeySet) {
		if (companyId == null || companyId.length() == 0) {
			throw new IllegalArgumentException("企业ID不能为空");
		}
		if (dishIdSet.isEmpty() || tagHashSet.isEmpty() || enumKeySet.isEmpty()) {
			return Collections.emptyList();
		}
		LambdaQueryWrapper<CtDishTagEntity> queryWrapper = new LambdaQueryWrapper<>();
		queryWrapper.eq(CtDishTagEntity::getCompanyId, companyId)
			.in(CtDishTagEntity::getDishId, dishIdSet)
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
		updateWrapper.eq(CtDishTagEntity::getCompanyId, entity.getCompanyId())
			.eq(CtDishTagEntity::getDishId, entity.getDishId())
			.eq(CtDishTagEntity::getTagHash, entity.getTagHash())
			.eq(CtDishTagEntity::getEnumKey, entity.getEnumKey());
		return dishTagMapper.update(updateEntity, updateWrapper);
	}

	/**
	 * 标签保留天数。
	 * <p><b>它决定的是「下架多久的菜再上架要重打标」，不是「多久重打一次」。</b>
	 * 在架菜品每天被预热刷新 {@code last_seen_date}，永远不会被清掉；
	 * 只有连续下架超过本值的菜品，其 {@code tagHash} 记录才消失，重新上架时必须重调 LLM-B。
	 * 缩短它省的是表体积，代价是轮换菜的重打标量——菜单轮换周期大于本值时，
	 * 每轮回来都要重打一次。</p>
	 * <p><b>取 7 天是为了覆盖周菜单轮换。</b> 曾短暂定为 3 天，但食堂菜单以周为周期时，
	 * 周一的菜下周一才回来（间隔 7 天），3 天会让每一道轮换菜每周重调一次 LLM-B。</p>
	 */
	private static final long TAG_RETENTION_DAYS = 7L;

	/** 清理一批早于业务日保留期边界的标签。 */
	public int deleteExpiredBatch(LocalDate bizDate) {
		if (bizDate == null) {
			throw new IllegalArgumentException("清理业务日不能为空");
		}
		// 截止日由 Java 从 bizDate 算出后传入，等价于 DATE_SUB(bizDate, INTERVAL 7 DAY)。
		// 禁止在 SQL 里取数据库当前时间：跨零点执行会让清理基准与打标基准分叉。
		LocalDate cutoffDate = bizDate.minusDays(TAG_RETENTION_DAYS);
		return dishTagMapper.deleteExpiredBatch(cutoffDate);
	}

}
