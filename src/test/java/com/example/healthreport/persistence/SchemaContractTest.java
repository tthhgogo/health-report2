package com.example.healthreport.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产 DDL 与文档中正式 DDL 的逐字契约测试：列、索引与表属性逐项比对。
 */
class SchemaContractTest {

    private static final Pattern FORBIDDEN_DDL_PATTERN = Pattern.compile(
            "(?i)\\b(CONSTRAINT|FOREIGN\\s+KEY|CHECK|TRIGGER)\\b");

    @Test
    void schemaShouldExactlyMatchDocumentedDdl() throws IOException {
        String document = readUtf8(Paths.get("AI体检报告分析-开发方案V1.md"));
        String expectedDdl = extractFirstSqlBlockAfter(document, "### 3.1 DDL");
        String schemaSql = readUtf8(Paths.get("sql/schema.sql"));
        String actualDdl = stripHeaderComment(schemaSql)
                .replace("CREATE TABLE IF NOT EXISTS", "CREATE TABLE");

        assertEquals(normalizeLineEndings(expectedDdl).trim(),
                normalizeLineEndings(actualDdl).trim());
    }

    @Test
    void schemaShouldBeIdempotentAndContainOnlyAllowedIndexDeclarations() throws IOException {
        String schemaSql = readUtf8(Paths.get("sql/schema.sql"));

        assertTrue(schemaSql.startsWith("-- 本文件是当前完整结构，可在空库重复执行；升级已有环境请走 sql/alter/。"));
        assertEquals(3, countMatches(schemaSql, "CREATE TABLE IF NOT EXISTS"));
        assertTrue(schemaSql.contains("CREATE TABLE IF NOT EXISTS ct_health_report_task"));
        assertTrue(schemaSql.contains("CREATE TABLE IF NOT EXISTS ct_health_report_file"));
        assertTrue(schemaSql.contains("CREATE TABLE IF NOT EXISTS ct_dish_tag"));
        assertFalse(FORBIDDEN_DDL_PATTERN.matcher(stripSqlLineComments(schemaSql)).find());
    }

    private static String extractFirstSqlBlockAfter(String document, String heading) {
        int headingIndex = document.indexOf(heading);
        if (headingIndex < 0) {
            throw new AssertionError("文档中缺少 DDL 章节");
        }
        int blockStart = document.indexOf("```sql", headingIndex);
        int contentStart = document.indexOf('\n', blockStart) + 1;
        int blockEnd = document.indexOf("```", contentStart);
        return document.substring(contentStart, blockEnd);
    }

    private static String stripHeaderComment(String sql) {
        int firstLineEnd = sql.indexOf('\n');
        return sql.substring(firstLineEnd + 1);
    }

    private static String stripSqlLineComments(String sql) {
        return sql.replaceAll("(?m)^\\s*--.*$", "");
    }

    private static int countMatches(String source, String target) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(target, index)) >= 0) {
            count++;
            index += target.length();
        }
        return count;
    }

    private static String normalizeLineEndings(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String readUtf8(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
