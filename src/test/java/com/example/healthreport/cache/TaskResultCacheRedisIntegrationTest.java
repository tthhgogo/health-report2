package com.example.healthreport.cache;

import com.example.healthreport.task.DegradeAccumulator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Redis 进程上的结果缓存集成测试。
 *
 * <p>用嵌入式 redis-server 而不是 mock：TTL 是否真的写进去、键名在真实服务端长什么样、
 * 结果对象能否原样序列化回来，这三件事 mock 掉 {@code StringRedisTemplate} 之后一件都验不到——
 * mock 只能证明「我们用这些参数调用了它」，不能证明「Redis 那边真是这个结果」。</p>
 *
 * <p>选嵌入式而非 Testcontainers：构建机不保证有 Docker。
 * 【绝不允许在启动失败时 skip】——那等于用放宽断言换构建通过。</p>
 */
class TaskResultCacheRedisIntegrationTest {

    /** 结果缓存约定的存活时间，与 TaskResultCache.RESULT_TTL_HOURS 对齐。 */
    private static final long EXPECTED_TTL_SECONDS = 2L * 60L * 60L;
    /** TTL 断言容差：从写入到读回之间的真实耗时，秒级足够。 */
    private static final long TTL_TOLERANCE_SECONDS = 60L;

    private RedisServer redisServer;
    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private TaskResultCache cache;

    @BeforeEach
    void startRedis() throws Exception {
        int port = freePort();
        redisServer = new RedisServer(port);
        redisServer.start();
        RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration("127.0.0.1", port);
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        cache = new TaskResultCache(redisTemplate, new ObjectMapper());
    }

    @AfterEach
    void stopRedis() throws Exception {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (redisServer != null) {
            redisServer.stop();
        }
    }

    @Test
    void shouldRoundTripResultThroughRealRedisWithTwoHourTtl() {
        String taskId = "9f2c1a4e-0000-4000-8000-000000000001";
        AnalysisResult result = AnalysisResult.create(
                new DegradeAccumulator(), 3, 5, AnalysisModules.empty());

        cache.write(taskId, result);

        AnalysisResult loaded = cache.read(taskId);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getProcessedPages()).isEqualTo(3);
        assertThat(loaded.getTotalPages()).isEqualTo(5);

        Long ttlSeconds = redisTemplate.getExpire("result:" + taskId, TimeUnit.SECONDS);
        assertThat(ttlSeconds).isNotNull();
        assertThat(ttlSeconds)
                .isBetween(EXPECTED_TTL_SECONDS - TTL_TOLERANCE_SECONDS, EXPECTED_TTL_SECONDS);
    }

    @Test
    void missingKeyShouldReadAsNullRatherThanThrow() {
        assertThat(cache.read("9f2c1a4e-0000-4000-8000-00000000ffff")).isNull();
    }

    /** 取一个当前空闲端口，避免与开发机上已有的 Redis 抢 6379。 */
    private int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
