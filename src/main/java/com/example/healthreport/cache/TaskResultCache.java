package com.example.healthreport.cache;

import com.example.healthreport.support.FailCode;
import com.example.healthreport.support.HealthReportException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 分析结果 Redis 读写边界。
 * <p>只负责固定键和两小时 TTL，不承载任务状态或调度信息。</p>
 */
@Service
public class TaskResultCache {

    static final long RESULT_TTL_HOURS = 2L;
    private static final String RESULT_KEY_PREFIX = "result:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public TaskResultCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /** 写入尚不可见的结果草稿，MySQL 成功 CAS 后才可由接口读取。 */
    public void write(String taskId, AnalysisResult result) {
        try {
            String resultJson = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(key(taskId), resultJson, RESULT_TTL_HOURS, TimeUnit.HOURS);
        } catch (JsonProcessingException exception) {
            throw new HealthReportException(FailCode.SERVER_ERROR, 500, exception);
        }
    }

    /** 读取已经通过 MySQL 可见性判定的结果。 */
    public AnalysisResult read(String taskId) {
        String resultJson = redisTemplate.opsForValue().get(key(taskId));
        if (resultJson == null) {
            return null;
        }
        try {
            return objectMapper.readValue(resultJson, AnalysisResult.class);
        } catch (JsonProcessingException exception) {
            throw new HealthReportException(FailCode.SERVER_ERROR, 500, exception);
        }
    }

    /** 删除结果；删除操作幂等。 */
    public boolean delete(String taskId) {
        return Boolean.TRUE.equals(redisTemplate.delete(key(taskId)));
    }

    private String key(String taskId) {
        return RESULT_KEY_PREFIX + taskId;
    }
}
