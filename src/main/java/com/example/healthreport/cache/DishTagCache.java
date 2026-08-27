package com.example.healthreport.cache;

import com.example.healthreport.dish.TagValue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 按业务日和维度组织的菜品标签 Redis 缓存。 */
@Service
@Slf4j
public class DishTagCache {

    /** 标签缓存保留三天，跨越每日重建窗口。 */
    private static final Duration TAG_TTL = Duration.ofDays(3L);

    /** Redis Key 前缀，不包含用户或健康标识。 */
    private static final String KEY_PREFIX = "dish:tag:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public DishTagCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /** 写入同一维度的当日标签并刷新整个 Hash 的 TTL。 */
    public void putAll(LocalDate bizDate, String enumKey, Map<String, TagValue> valueByFieldMap) {
        if (valueByFieldMap.isEmpty()) {
            return;
        }
        Map<String, String> jsonByFieldMap = new LinkedHashMap<String, String>(
                valueByFieldMap.size());
        for (Map.Entry<String, TagValue> entry : valueByFieldMap.entrySet()) {
            try {
                jsonByFieldMap.put(entry.getKey(), objectMapper.writeValueAsString(entry.getValue()));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("菜品标签缓存序列化失败", exception);
            }
        }
        String key = key(bizDate, enumKey);
        try {
            redisTemplate.opsForHash().putAll(key, jsonByFieldMap);
            redisTemplate.expire(key, TAG_TTL);
            // 预热成功才意味着在线读能命中缓存；只记维度与字段数，不记 key 之外的任何内容。
            // 这里是离线批处理，enumKey 不关联任何用户（与 getAll 的失败分支不同，
            // 那条是在线路径，才必须避免暴露"本次报告涉及哪类过敏原"）。
            log.info("菜品标签缓存预热完成，业务日={}，维度={}，字段数={}",
                    bizDate, enumKey, jsonByFieldMap.size());
        } catch (RuntimeException exception) {
            // MySQL 已是真源；缓存预热失败不回滚标签，也不输出维度或菜品字段。
            // 【不传异常对象】Redis 异常消息可能带上 key，而 key 含 enumKey，
            // 会间接暴露本次报告涉及哪类过敏原。
            log.warn("菜品标签缓存预热失败，在线读取将回源数据库，异常类型={}",
                    exception.getClass().getName());
        }
    }

    /** 批量读取字段；缓存值损坏按未命中处理，以便调用方回源 MySQL。 */
    public Map<String, TagValue> getAll(LocalDate bizDate, String enumKey,
                                        List<String> fieldList) {
        if (fieldList.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Object> fieldObjectList = new ArrayList<Object>(fieldList.size());
        fieldObjectList.addAll(fieldList);
        List<Object> jsonList;
        try {
            jsonList = redisTemplate.opsForHash().multiGet(key(bizDate, enumKey), fieldObjectList);
        } catch (RuntimeException exception) {
            // Redis 不可用按全部未命中处理，调用方必须回源 MySQL。
            // 同上：只记异常类型名，不把可能含 key 的异常交给日志框架。
            log.warn("菜品标签缓存读取失败，转为数据库回源，异常类型={}",
                    exception.getClass().getName());
            return Collections.emptyMap();
        }
        Map<String, TagValue> resultMap = new LinkedHashMap<String, TagValue>(fieldList.size());
        for (int index = 0; index < fieldList.size(); index++) {
            Object json = jsonList == null ? null : jsonList.get(index);
            if (json != null) {
                try {
                    resultMap.put(fieldList.get(index), objectMapper.readValue(
                            String.valueOf(json), TagValue.class));
                } catch (JsonProcessingException | RuntimeException exception) {
                    // 缓存不是事实源，损坏值必须回源，不能降成 NEUTRAL。
                }
            }
        }
        return resultMap;
    }

    /** 生成不含用户信息的缓存字段。 */
    public String field(long dishId, String tagHash) {
        return dishId + ":" + tagHash;
    }

    private String key(LocalDate bizDate, String enumKey) {
        return KEY_PREFIX + enumKey + ":" + bizDate;
    }

}
