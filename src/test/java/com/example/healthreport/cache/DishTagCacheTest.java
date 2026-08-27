package com.example.healthreport.cache;

import com.example.healthreport.dish.TagState;
import com.example.healthreport.dish.TagValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 菜品标签缓存结构的精确契约测试。 */
class DishTagCacheTest {

    @Test
    void shouldUseDimensionThenBusinessDateInRedisKey() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.multiGet(eq("dish:tag:LOW_FAT:2026-08-26"),
                eq(Collections.<Object>singletonList("1001:hash"))))
                .thenReturn(Collections.<Object>singletonList(
                        "{\"verdict\":\"NEUTRAL\",\"matchedIngredients\":[]}"));
        DishTagCache cache = new DishTagCache(redisTemplate, new ObjectMapper());

        List<String> fieldList = Collections.singletonList("1001:hash");
        TagValue value = cache.getAll(LocalDate.of(2026, 8, 26), "LOW_FAT", fieldList)
                .get("1001:hash");

        assertThat(value.getState()).isEqualTo(TagState.NEUTRAL);
        verify(hashOperations).multiGet(eq("dish:tag:LOW_FAT:2026-08-26"),
                eq(Collections.<Object>singletonList("1001:hash")));
    }
}
