package com.example.healthreport.support;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ID 生成与入口断言测试。
 */
class IdCanonicalizerTest {

    private final IdCanonicalizer idCanonicalizer = new IdCanonicalizer();

    @Test
    void shouldGenerateCanonicalLowerCaseUuid() {
        String taskId = idCanonicalizer.newTaskId();
        String fileId = idCanonicalizer.newFileId();

        assertEquals(UUID.fromString(taskId).toString(), taskId);
        assertEquals(taskId.toLowerCase(Locale.ROOT), taskId);
        assertEquals(UUID.fromString(fileId).toString(), fileId);
        assertEquals(fileId.toLowerCase(Locale.ROOT), fileId);
        assertNotEquals(taskId, fileId);
    }

    @Test
    void shouldReturnAlreadyCanonicalValueUnchanged() {
        String canonicalId = "123e4567-e89b-12d3-a456-426614174000";

        assertEquals(canonicalId, idCanonicalizer.canonicalize(canonicalId));
    }

    @Test
    void shouldRejectUpperCaseWithoutCorrectingIt() {
        String upperCaseId = "123E4567-E89B-12D3-A456-426614174000";

        assertThrows(IllegalArgumentException.class,
                () -> idCanonicalizer.canonicalize(upperCaseId));
    }

    @Test
    void shouldRejectNullEmptyAndNonCanonicalValues() {
        assertThrows(IllegalArgumentException.class, () -> idCanonicalizer.canonicalize(null));
        assertThrows(IllegalArgumentException.class, () -> idCanonicalizer.canonicalize(""));
        assertThrows(IllegalArgumentException.class, () -> idCanonicalizer.canonicalize("not-an-id"));
        assertThrows(IllegalArgumentException.class,
                () -> idCanonicalizer.canonicalize("123e4567e89b12d3a456426614174000"));
    }
}
