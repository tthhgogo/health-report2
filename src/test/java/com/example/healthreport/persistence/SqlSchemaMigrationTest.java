package com.example.healthreport.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * schema.sql 重复执行与 R54a 两条建库路径的一致性测试。
 * <p>测试使用 H2 MySQL 模式；仅为执行兼容去掉 MySQL 的字符集、注释和索引装饰，
 * 原始生产语句的完整性由 {@link SchemaContractTest} 逐字校验。</p>
 */
class SqlSchemaMigrationTest {

    @Test
    void schemaShouldBeRepeatableAndMatchCurrentMigrationRoute() throws Exception {
        Class.forName("org.h2.Driver");
        String schemaSql = readUtf8(Paths.get("sql/schema.sql"));

        List<String> directSnapshotList;
        try (Connection connection = newConnection("direct")) {
            executeScript(connection, schemaSql);
            executeScript(connection, schemaSql);
            directSnapshotList = schemaSnapshot(connection);
        }

        List<String> migrationSnapshotList;
        try (Connection connection = newConnection("migration")) {
            executeScript(connection, schemaSql);
            for (Path alterPath : sortedAlterScriptList()) {
                executeScript(connection, readUtf8(alterPath));
            }
            migrationSnapshotList = schemaSnapshot(connection);
        }

        assertEquals(directSnapshotList, migrationSnapshotList);
        assertEquals(3, countTableEntries(directSnapshotList));
    }

    private static Connection newConnection(String route) throws SQLException {
        String databaseName = "schema_" + route + "_" + System.nanoTime();
        return DriverManager.getConnection("jdbc:h2:mem:" + databaseName + ";MODE=MySQL", "sa", "");
    }

    private static void executeScript(Connection connection, String sourceSql) throws SQLException {
        String executableSql = toH2CompatibleSql(sourceSql);
        for (String statementSql : executableSql.split(";")) {
            String trimmedSql = statementSql.trim();
            if (trimmedSql.length() > 0) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(trimmedSql);
                }
            }
        }
    }

    private static String toH2CompatibleSql(String sourceSql) {
        String sql = sourceSql.replaceAll("(?m)^\\s*--.*$", "");
        sql = sql.replaceAll("(?i)\\)\\s*ENGINE=InnoDB\\s+DEFAULT\\s+CHARSET=utf8mb4"
                + "\\s+COLLATE=utf8mb4_general_ci\\s+COMMENT='[^']*'", ")");
        sql = sql.replaceAll("(?i)\\s+CHARACTER\\s+SET\\s+utf8mb4"
                + "\\s+COLLATE\\s+utf8mb4_general_ci", "");
        sql = sql.replaceAll("(?i)\\s+COMMENT\\s+'[^']*'", "");
        sql = sql.replaceAll("(?i)TINYINT\\(1\\)", "TINYINT");
        sql = sql.replaceAll("(?im)^\\s*(?:UNIQUE\\s+)?KEY\\s+[^\\r\\n]+(?:\\r?\\n|$)", "");
        sql = sql.replaceAll(",\\s*\\)", "\n)");
        return sql;
    }

    private static List<Path> sortedAlterScriptList() throws IOException {
        List<Path> alterPathList = new ArrayList<>();
        Path alterDirectory = Paths.get("sql/alter");
        if (Files.exists(alterDirectory)) {
            try (java.nio.file.DirectoryStream<Path> pathStream = Files.newDirectoryStream(alterDirectory,
                    "*.sql")) {
                for (Path path : pathStream) {
                    alterPathList.add(path);
                }
            }
        }
        Collections.sort(alterPathList, Comparator.comparing(Path::toString));
        return alterPathList;
    }

    private static List<String> schemaSnapshot(Connection connection) throws SQLException {
        List<String> snapshotList = new ArrayList<>();
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet tableSet = metadata.getTables(null, "PUBLIC", "CT\\_%", new String[]{"TABLE"})) {
            while (tableSet.next()) {
                String tableName = tableSet.getString("TABLE_NAME").toUpperCase(Locale.ROOT);
                snapshotList.add("TABLE|" + tableName);
                appendColumnSnapshot(metadata, tableName, snapshotList);
            }
        }
        Collections.sort(snapshotList);
        return snapshotList;
    }

    private static void appendColumnSnapshot(DatabaseMetaData metadata, String tableName,
                                             List<String> snapshotList) throws SQLException {
        try (ResultSet columnSet = metadata.getColumns(null, "PUBLIC", tableName, null)) {
            while (columnSet.next()) {
                snapshotList.add("COLUMN|" + tableName + "|" + columnSet.getString("COLUMN_NAME")
                        + "|" + columnSet.getString("TYPE_NAME")
                        + "|" + columnSet.getInt("NULLABLE")
                        + "|" + columnSet.getString("COLUMN_DEF"));
            }
        }
    }

    private static int countTableEntries(List<String> snapshotList) {
        int tableCount = 0;
        for (String entry : snapshotList) {
            if (entry.startsWith("TABLE|")) {
                tableCount++;
            }
        }
        return tableCount;
    }

    private static String readUtf8(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
