package com.example.healthreport.dish;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.healthreport.cache.DishTagCache;
import com.example.healthreport.llm.dishtag.DishTagProperties;
import com.example.healthreport.persistence.CtDishTagEntity;
import com.example.healthreport.persistence.CtDishTagService;
import com.example.healthreport.parse.segment.TextNormalizer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Redis 未命中回源 MySQL，仍缺失才产生 TAG_MISSING。 */
class DishTagReadServiceTest {

    @Test
    void cacheMissShouldUseDatabaseAndKeepRealMissingDistinct() {
        DishTagCache cache = mock(DishTagCache.class);
        CtDishTagService persistence = mock(CtDishTagService.class);
        DishTagService offlineService = mock(DishTagService.class);
        DishTagProperties properties = new DishTagProperties();
        properties.setModelVersionDishtag("model-b");
        TagHashCalculator calculator = new TagHashCalculator(new TextNormalizer());
        Dish first = dish(1L);
        Dish second = dish(2L);
        LocalDate date = LocalDate.of(2026, 8, 26);
        String firstHash = calculator.calculate(
                com.example.healthreport.constants.TagRuleVersion.VALUE,
                com.example.healthreport.constants.PromptVersions.DISH_TAG, "model-b", first);
        CtDishTagEntity databaseEntity = new CtDishTagEntity();
        databaseEntity.setDishId(1L);
        databaseEntity.setTagHash(firstHash);
        databaseEntity.setEnumKey("LOW_FAT");
        databaseEntity.setVerdict(TagState.NEUTRAL.name());
        when(cache.field(eq(1L), anyString())).thenAnswer(invocation ->
                invocation.getArgument(0) + ":" + invocation.getArgument(1));
        when(cache.field(eq(2L), anyString())).thenAnswer(invocation ->
                invocation.getArgument(0) + ":" + invocation.getArgument(1));
        when(cache.getAll(eq(date), eq("LOW_FAT"), anyList()))
                .thenReturn(Collections.<String, TagValue>emptyMap());
        when(persistence.findCandidates(anySet(), anySet(), anySet()))
                .thenReturn(Collections.singletonList(databaseEntity));
        when(offlineService.toTagValue(databaseEntity)).thenReturn(TagValue.of(TagState.NEUTRAL));
        DishTagReadService service = new DishTagReadService(calculator, properties, cache,
                persistence, offlineService);
        Logger logger = (Logger) LoggerFactory.getLogger(DishTagReadService.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);

        try {
            Map<String, Map<Long, TagValue>> result = service.read(date, Arrays.asList(first, second),
                    new LinkedHashSet<String>(Collections.singletonList("LOW_FAT")));

            assertThat(result.get("LOW_FAT").get(1L).getState()).isEqualTo(TagState.NEUTRAL);
            assertThat(result.get("LOW_FAT").get(2L).getState()).isEqualTo(TagState.TAG_MISSING);
            verify(persistence).findCandidates(anySet(), anySet(), anySet());
            assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("数据库回源完成", "待回源标签数=2", "数据库命中行数=1")
                    .doesNotContain("LOW_FAT"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    private Dish dish(long id) {
        return new Dish(id, "测试菜" + id,
                Collections.singletonList(new DishIngredient("食材", null)));
    }
}
