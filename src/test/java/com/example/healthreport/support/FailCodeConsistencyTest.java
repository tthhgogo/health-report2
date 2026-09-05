package com.example.healthreport.support;

import com.example.healthreport.support.FailCode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 错误码枚举与错误码文档表的一致性测试：两处必须无多无少且顺序相同。
 */
class FailCodeConsistencyTest {

    private static final Pattern CODE_ROW_PATTERN =
            Pattern.compile("(?m)^\\| `([A-Z_]+)` \\|");

    @Test
    void shouldExactlyMatchDocumentedFailCodeTable() throws IOException {
        String document = readUtf8(Paths.get("AI体检报告分析-开发方案V1.md"));
        int sectionStart = document.indexOf("### 9.1 错误码");
        int sectionEnd = document.indexOf("### 9.2", sectionStart);
        String failCodeSection = document.substring(sectionStart, sectionEnd);

        List<String> documentedCodeList = new ArrayList<>();
        Matcher matcher = CODE_ROW_PATTERN.matcher(failCodeSection);
        while (matcher.find()) {
            documentedCodeList.add(matcher.group(1));
        }
        List<String> enumCodeList = new ArrayList<>();
        for (FailCode failCode : FailCode.values()) {
            enumCodeList.add(failCode.name());
        }

        assertEquals(Arrays.asList(
                "UNSUPPORTED_FORMAT", "FILE_TOO_LARGE", "FILE_UNREADABLE", "MALFORMED_REQUEST",
                "FILE_ALREADY_BOUND", "FILE_EXPIRED", "PAGE_LIMIT_EXCEEDED",
                "TASK_NOT_FINISHED", "NOT_HEALTH_REPORT", "UNREADABLE",
                "IMAGE_TOO_LARGE", "IDENTITY_MISMATCH", "EXECUTION_TIMEOUT",
                "SERVER_ERROR", "RESULT_EXPIRED"), documentedCodeList);
        assertEquals(documentedCodeList, enumCodeList);
    }

    private static String readUtf8(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
