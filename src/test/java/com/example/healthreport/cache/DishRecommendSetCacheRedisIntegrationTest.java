package com.example.healthreport.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Redis 进程上的企业方向集合往返测试。
 *
 * <p>用嵌入式 redis-server 而不是 mock：{@code publish} 与 {@code read} 都是 Lua 脚本，
 * mock 掉 {@code StringRedisTemplate} 之后，脚本语法是否合法、{@code SMEMBERS} 的嵌套返回
 * 能不能反序列化成 {@code List<List<String>>}、返回顺序与 {@code KEYS} 顺序是否对齐、
 * rename 后 TTL 有没有真的落上——这几件事一件都验不到，而它们正是 2026-09-05
 * 「集群下 pipeline 不可用」那次故障改法所依赖的前提。</p>
 *
 * <p>单机 redis 验不出跨 slot 与集群限制（`AGENTS.md` §6「Redis SDK 使用约束」），
 * 那部分靠键的 hash tag 断言守（见 {@link DishRecommendSetContractTest}）。
 * 【绝不允许在启动失败时 skip】——那等于用放宽断言换构建通过。</p>
 */
class DishRecommendSetCacheRedisIntegrationTest {

    /** 企业标识刻意带冒号：顺带验证经 codec 编码后不会破坏 Key 分段与 hash tag。 */
    private static final String COMPANY_ID = "企业:A";

    /** 固定业务日，与键名里的日期段一一对应。 */
    private static final LocalDate BIZ_DATE = LocalDate.of(2026, 8, 28);

    /** 正式集合存活三天，与 DishRecommendSetCache.FORMAL_TTL 对齐。 */
    private static final long EXPECTED_FORMAL_TTL_SECONDS = 3L * 24L * 60L * 60L;

    /** TTL 断言容差：从写入到读回之间的真实耗时，秒级足够。 */
    private static final long TTL_TOLERANCE_SECONDS = 60L;

    private RedisServer redisServer;
    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private DishRecommendSetKeyFactory keyFactory;
    private DishSetMemberCodec memberCodec;
    private DishRecommendSetCache cache;

    private final DishTagSetRef allergenRef = new DishTagSetRef(DishTagSetRef.Category.ALLERGEN,
            DishTagSetRef.Direction.REJECT, "SHRIMP_CRAB");

    private final DishTagSetRef nutritionRef = new DishTagSetRef(DishTagSetRef.Category.NUTRITION,
            DishTagSetRef.Direction.RECOMMEND, "IRON");

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
        keyFactory = new DishRecommendSetKeyFactory(new CompanyRedisKeyCodec());
        memberCodec = new DishSetMemberCodec();
        cache = new DishRecommendSetCache(redisTemplate, keyFactory);
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
    void shouldPublishStagingSetsAndReadThemBackInOneScript() {
        String buildId = "build-1";
        List<DishTagSetRef> refList = Arrays.asList(allergenRef, nutritionRef);
        cache.append(COMPANY_ID, BIZ_DATE, buildId, allergenRef,
                Arrays.asList(memberCodec.encode(1001L, "香煎带鱼"), memberCodec.encode(1002L, "蒜蓉虾")));
        cache.append(COMPANY_ID, BIZ_DATE, buildId, nutritionRef,
                Collections.singletonList(memberCodec.encode(2001L, "菠菜猪肝汤")));

        cache.publish(COMPANY_ID, BIZ_DATE, buildId, refList);
        Map<DishTagSetRef, Set<String>> resultMap = cache.read(COMPANY_ID, BIZ_DATE, refList);

        // 顺序对齐是 read 的核心契约：脚本按 KEYS 顺序返回，错位会把过敏集合当成营养集合用。
        assertThat(resultMap.keySet()).containsExactly(allergenRef, nutritionRef);
        assertThat(resultMap.get(allergenRef)).containsExactlyInAnyOrder(
                memberCodec.encode(1001L, "香煎带鱼"), memberCodec.encode(1002L, "蒜蓉虾"));
        assertThat(resultMap.get(nutritionRef))
                .containsExactly(memberCodec.encode(2001L, "菠菜猪肝汤"));

        Long formalTtlSeconds = redisTemplate
                .getExpire(keyFactory.formalKey(COMPANY_ID, BIZ_DATE, allergenRef), TimeUnit.SECONDS);
        assertThat(formalTtlSeconds).isNotNull();
        assertThat(formalTtlSeconds)
                .isBetween(EXPECTED_FORMAL_TTL_SECONDS - TTL_TOLERANCE_SECONDS, EXPECTED_FORMAL_TTL_SECONDS);
        // staging 被 rename 走，不是复制：留着会在下一次构建里变成脏数据来源。
        assertThat(redisTemplate.hasKey(keyFactory.stagingKey(COMPANY_ID, BIZ_DATE, buildId, allergenRef)))
                .isFalse();
    }

    /** 某方向当天没有任何菜品时，读回空集合而不是缺键或异常——模块四据此走空态而非降级。 */
    @Test
    void readShouldReturnEmptySetForDirectionWithoutMembers() {
        String buildId = "build-2";
        List<DishTagSetRef> refList = Arrays.asList(allergenRef, nutritionRef);
        cache.append(COMPANY_ID, BIZ_DATE, buildId, allergenRef,
                Collections.singletonList(memberCodec.encode(1001L, "香煎带鱼")));

        cache.publish(COMPANY_ID, BIZ_DATE, buildId, refList);
        Map<DishTagSetRef, Set<String>> resultMap = cache.read(COMPANY_ID, BIZ_DATE, refList);

        assertThat(resultMap).hasSize(2);
        assertThat(resultMap.get(allergenRef)).hasSize(1);
        assertThat(resultMap.get(nutritionRef)).isEmpty();
    }

    /** 构建失败时 discard 只清本次 buildId 的 staging，当天已发布的快照必须原样还在。 */
    @Test
    void discardShouldDropStagingWithoutTouchingPublishedSnapshot() {
        List<DishTagSetRef> refList = Collections.singletonList(allergenRef);
        cache.append(COMPANY_ID, BIZ_DATE, "build-3", allergenRef,
                Collections.singletonList(memberCodec.encode(1001L, "香煎带鱼")));
        cache.publish(COMPANY_ID, BIZ_DATE, "build-3", refList);
        cache.append(COMPANY_ID, BIZ_DATE, "build-4", allergenRef,
                Collections.singletonList(memberCodec.encode(9009L, "半成品菜")));

        cache.discard(COMPANY_ID, BIZ_DATE, "build-4", refList);

        assertThat(redisTemplate.hasKey(keyFactory.stagingKey(COMPANY_ID, BIZ_DATE, "build-4", allergenRef)))
                .isFalse();
        assertThat(cache.read(COMPANY_ID, BIZ_DATE, refList).get(allergenRef))
                .containsExactly(memberCodec.encode(1001L, "香煎带鱼"));
    }

    /** 取一个当前空闲端口，避免与开发机上已有的 Redis 抢 6379。 */
    private int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
