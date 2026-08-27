package com.example.healthreport.dish;

import com.example.healthreport.persistence.CtDishTagMapper;
import com.example.healthreport.persistence.CtDishTagService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.Scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** R18a~R18c：单 Handler、共享业务日、先打标后清理与分批循环。 */
class DishTagJobAndCleanupTest {

    @Test
    void jobShouldPassOneBizDateAndRunCleanupAfterTagging() {
        DishTagService tagService = mock(DishTagService.class);
        DishTagCleanupService cleanupService = mock(DishTagCleanupService.class);
        DishTagJob job = new DishTagJob(tagService, cleanupService);

        job.execute();

        ArgumentCaptor<LocalDate> tagDateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> cleanupDateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        InOrder order = inOrder(tagService, cleanupService);
        order.verify(tagService).run(tagDateCaptor.capture());
        order.verify(cleanupService).run(cleanupDateCaptor.capture());
        assertThat(cleanupDateCaptor.getValue()).isEqualTo(tagDateCaptor.getValue());
    }

    @Test
    void cleanupShouldLoopUntilAffectedRowsBecomeZero() {
        CtDishTagService persistenceService = mock(CtDishTagService.class);
        LocalDate bizDate = LocalDate.of(2026, 8, 26);
        when(persistenceService.deleteExpiredBatch(bizDate)).thenReturn(5000, 12, 0);

        new DishTagCleanupService(persistenceService).run(bizDate);

        verify(persistenceService, times(3)).deleteExpiredBatch(bizDate);
    }

    @Test
    void cleanupSqlShouldUseStrictThirtyDayBoundaryAndLimit() throws Exception {
        InputStream mapperXmlStream = CtDishTagMapper.class.getResourceAsStream(
                "/mapper/CtDishTagMapper.xml");
        assertThat(mapperXmlStream).isNotNull();
        String mapperXml;
        try (Scanner mapperXmlScanner = new Scanner(mapperXmlStream, "UTF-8")) {
            mapperXmlScanner.useDelimiter("\\A");
            mapperXml = mapperXmlScanner.hasNext() ? mapperXmlScanner.next() : "";
        }

        assertThat(mapperXml).contains("last_seen_date &lt; #{cutoffDate}")
                .contains("LIMIT 5000")
                .doesNotContain("CURRENT_DATE")
                .doesNotContain("now()");
    }
}
