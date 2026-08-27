package com.example.healthreport.infra;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 三个外部集成占位符的显式失败测试，防止空实现返回假数据。
 */
class PlaceholderFailureTest {

    private final S3FileStorage fileStorage = new S3FileStorage() {
    };
    private final CurrentUserProvider currentUserProvider = new CurrentUserProvider() {
    };
    private final DishQueryService dishQueryService = new DishQueryService() {
    };

    @Test
    void everyPlaceholderMethodShouldFailExplicitly() {
        assertThrows(UnsupportedOperationException.class,
                () -> fileStorage.write("test-object", new byte[0]));
        assertThrows(UnsupportedOperationException.class,
                () -> fileStorage.read("test-object"));
        assertThrows(UnsupportedOperationException.class,
                () -> fileStorage.delete("test-object"));
        assertThrows(UnsupportedOperationException.class,
                currentUserProvider::currentUserId);
        assertThrows(UnsupportedOperationException.class,
                () -> dishQueryService.queryOnShelfDishes(LocalDate.of(2026, 1, 1)));
    }
}
