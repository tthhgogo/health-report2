package com.example.healthreport.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 企业菜品方向集合的增量构建、原子发布与在线批量读取边界。 */
@Slf4j
@Service
public class DishRecommendSetCache {

	/** 正式集合保留三天，以覆盖每日重建窗口；在线仍只读取指定业务日。 */
	private static final Duration FORMAL_TTL = Duration.ofDays(3L);

	/** 构建集合保留六小时，供进程异常退出后自动回收。 */
	private static final Duration STAGING_TTL = Duration.ofHours(6L);

	/** 33 个同 slot SET 的原子替换脚本；返回处理方向数作为 Java 与 Lua 清单的契约握手。 */
	private static final DefaultRedisScript<Long> PUBLISH_SCRIPT = new DefaultRedisScript<Long>(
			"local half = #KEYS / 2 " + "local processed = 0 " + "for i = 1, half do " + "  local staging = KEYS[i] "
					+ "  local formal = KEYS[half + i] " + "  redis.call('del', formal) "
					+ "  if redis.call('exists', staging) == 1 then " + "    redis.call('rename', staging, formal) "
					+ "    redis.call('pexpire', formal, ARGV[1]) " + "  end " + "  processed = processed + 1 "
					+ "end " + "return processed",
			Long.class);

	private final StringRedisTemplate redisTemplate;

	private final DishRecommendSetKeyFactory keyFactory;

	public DishRecommendSetCache(StringRedisTemplate redisTemplate, DishRecommendSetKeyFactory keyFactory) {
		this.redisTemplate = redisTemplate;
		this.keyFactory = keyFactory;
	}

	/** 将当前分页得到的成员增量加入一个 staging SET，并刷新短 TTL。 */
	public void append(String companyId, LocalDate bizDate, String buildId, DishTagSetRef setRef,
			Collection<String> memberCollection) {
		if (memberCollection == null || memberCollection.isEmpty()) {
			return;
		}
		String key = keyFactory.stagingKey(companyId, bizDate, buildId, setRef);
		String[] memberArray = memberCollection.toArray(new String[memberCollection.size()]);
		redisTemplate.opsForSet().add(key, memberArray);
		redisTemplate.expire(key, STAGING_TTL);
	}

	/**
	 * 一次 Lua 调用整体发布企业当天固定方向清单。
	 * <p>
	 * 所有 Key 共享 {@code companyId:bizDate} hash tag，Redis Cluster 下不会跨 slot。
	 * 脚本返回值只用于发现 Java 发布清单与 Lua 契约漂移，不代表成功写入的成员数量。
	 * </p>
	 */
	public void publish(String companyId, LocalDate bizDate, String buildId, List<DishTagSetRef> publishRefList) {
		if (publishRefList == null || publishRefList.isEmpty()) {
			throw new IllegalArgumentException("发布方向清单不能为空");
		}
		List<String> keyList = new ArrayList<String>(publishRefList.size() * 2);
		for (DishTagSetRef setRef : publishRefList) {
			keyList.add(keyFactory.stagingKey(companyId, bizDate, buildId, setRef));
		}
		for (DishTagSetRef setRef : publishRefList) {
			keyList.add(keyFactory.formalKey(companyId, bizDate, setRef));
		}
		Long processedDirectionCount = redisTemplate.execute(PUBLISH_SCRIPT, keyList,
				String.valueOf(FORMAL_TTL.toMillis()));
		if (processedDirectionCount == null || processedDirectionCount.longValue() != publishRefList.size()) {
			throw new IllegalStateException("Redis发布脚本处理方向数与Java清单不一致，疑似脚本契约漂移");
		}
	}

	/** 构建失败时删除本次 buildId 的 staging SET，不触碰当天正式快照。 */
	public void discard(String companyId, LocalDate bizDate, String buildId, List<DishTagSetRef> publishRefList) {
		if (publishRefList == null || publishRefList.isEmpty()) {
			return;
		}
		List<String> keyList = new ArrayList<String>(publishRefList.size());
		for (DishTagSetRef setRef : publishRefList) {
			keyList.add(keyFactory.stagingKey(companyId, bizDate, buildId, setRef));
		}
		redisTemplate.delete(keyList);
	}

	/**
	 * 通过一次 pipeline 按标签维度批量读取相关集合，不按菜品逐个访问。Redis 不可用或响应格式异常时返回空集合，在线禁止回源数据库或重新计算。
	 */
	public Map<DishTagSetRef, Set<String>> read(String companyId, LocalDate bizDate, List<DishTagSetRef> setRefList) {
		if (setRefList == null || setRefList.isEmpty()) {
			return Collections.emptyMap();
		}
		try {
			final RedisSerializer<String> stringSerializer = redisTemplate.getStringSerializer();
			List<Object> pipelineResultList = redisTemplate.executePipelined(new RedisCallback<Object>() {
				@Override
				public Object doInRedis(RedisConnection connection) {
					for (DishTagSetRef setRef : setRefList) {
						connection.sMembers(stringSerializer.serialize(keyFactory.formalKey(companyId, bizDate, setRef)));
					}
					return null;
				}
			}, stringSerializer);
			if (pipelineResultList.size() != setRefList.size()) {
				throw new IllegalStateException("Redis批量读取响应数量与方向清单不一致");
			}
			Map<DishTagSetRef, Set<String>> resultMap = new LinkedHashMap<DishTagSetRef, Set<String>>(setRefList.size());
			for (int index = 0; index < setRefList.size(); index++) {
				resultMap.put(setRefList.get(index), toMemberSet(pipelineResultList.get(index)));
			}
			return resultMap;
		}
		catch (RuntimeException exception) {
			// 在线缓存异常只返回空态；禁止将含企业编码和健康维度的 Redis Key 或异常正文写日志。
			log.warn("企业菜品标签集合读取失败，模块四返回空态，异常类型={}", exception.getClass().getName());
			return Collections.emptyMap();
		}
	}

	/** 将 pipeline 的单条响应收窄为不可变字符串集合，异常类型由 read 统一降级为空态。 */
	private Set<String> toMemberSet(Object pipelineResult) {
		if (pipelineResult == null) {
			return Collections.emptySet();
		}
		if (!(pipelineResult instanceof Set)) {
			throw new IllegalStateException("Redis集合响应类型异常");
		}
		Set<?> rawMemberSet = (Set<?>) pipelineResult;
		Set<String> memberSet = new LinkedHashSet<String>(rawMemberSet.size());
		for (Object rawMember : rawMemberSet) {
			if (!(rawMember instanceof String)) {
				throw new IllegalStateException("Redis集合成员类型异常");
			}
			memberSet.add((String) rawMember);
		}
		return Collections.unmodifiableSet(memberSet);
	}

}
