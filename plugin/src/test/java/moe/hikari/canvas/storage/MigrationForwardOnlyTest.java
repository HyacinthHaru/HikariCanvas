package moe.hikari.canvas.storage;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.9.1 数据契约闸：1.0 起 forward-only。schema 自 V018 冻结——
 * 新迁移禁 DROP TABLE / DROP COLUMN / ALTER COLUMN（删列走逻辑删除、删表走 _archive 重命名）。
 * V001-V017 是 pre-release 激进期产物，grandfathered。
 * 确需破坏性变更时在该 .sql 顶部加注释 {@code -- @forward-only-exempt: <理由>} 显式豁免（需 code review）。
 */
class MigrationForwardOnlyTest {

    /** schema 冻结起始版本：>= 此版本的迁移强制 forward-only。 */
    static final int FREEZE_FROM = 18;

    /** 返回该迁移内的 forward-only 违规项（空 = 合规）。 */
    static List<String> violations(int version, String rawSql, int freezeFrom) {
        if (version < freezeFrom) return List.of();                 // grandfathered
        if (rawSql.contains("@forward-only-exempt")) return List.of(); // 显式豁免（已 review）
        List<String> out = new ArrayList<>();
        for (String stmt : MigrationRunner.splitSqlStatements(rawSql)) {
            String s = stmt.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (s.isEmpty()) continue;
            if (s.startsWith("DROP TABLE")) {
                out.add("DROP TABLE 禁用: " + stmt.trim());
            } else if (s.startsWith("ALTER TABLE") && s.contains(" DROP COLUMN ")) {
                out.add("DROP COLUMN 禁用: " + stmt.trim());
            } else if (s.startsWith("ALTER TABLE") && s.contains(" ALTER COLUMN ")) {
                out.add("ALTER COLUMN 禁用: " + stmt.trim());
            }
        }
        return out;
    }

    @Test
    void forbidsDropTableFromFreeze() {
        assertFalse(violations(18, "DROP TABLE foo;", FREEZE_FROM).isEmpty());
        assertFalse(violations(18, "DROP TABLE IF EXISTS foo;", FREEZE_FROM).isEmpty());
    }

    @Test
    void forbidsDropAndAlterColumnFromFreeze() {
        assertFalse(violations(18, "ALTER TABLE x DROP COLUMN y;", FREEZE_FROM).isEmpty());
        assertFalse(violations(18, "ALTER TABLE x ALTER COLUMN y TYPE TEXT;", FREEZE_FROM).isEmpty());
    }

    @Test
    void allowsAdditiveDdlFromFreeze() {
        assertTrue(violations(18, "ALTER TABLE x ADD COLUMN y TEXT DEFAULT '';", FREEZE_FROM).isEmpty());
        assertTrue(violations(18, "CREATE TABLE z (id INTEGER PRIMARY KEY);", FREEZE_FROM).isEmpty());
        assertTrue(violations(18, "CREATE INDEX ix ON z(id);", FREEZE_FROM).isEmpty());
        assertTrue(violations(18, "ALTER TABLE x RENAME TO x_v018_archive;", FREEZE_FROM).isEmpty());
    }

    @Test
    void grandfathersPreFreezeMigrations() {
        assertTrue(violations(5, "DROP TABLE sign_records;", FREEZE_FROM).isEmpty());
        assertTrue(violations(10, "ALTER TABLE walls DROP COLUMN refcount;", FREEZE_FROM).isEmpty());
    }

    @Test
    void ignoresStringLiteralsAndComments() {
        // 字符串字面量里的 DROP TABLE 不算（语句以 INSERT 起头）。
        assertTrue(violations(18, "INSERT INTO x(a) VALUES ('DROP TABLE y');", FREEZE_FROM).isEmpty());
    }

    @Test
    void respectsExemptionMarker() {
        assertTrue(violations(18,
                "-- @forward-only-exempt: legit table rebuild\nDROP TABLE x;", FREEZE_FROM).isEmpty());
    }

    @Test
    void allActualMigrationsAreForwardOnly() throws IOException {
        Path dir = Path.of("src/main/resources/db-migrations");
        assertTrue(Files.isDirectory(dir), "db-migrations 目录应存在: " + dir.toAbsolutePath());
        Pattern vp = Pattern.compile("^V(\\d+)__.*\\.sql$");
        List<String> allViolations = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.sorted().toList()) {
                Matcher m = vp.matcher(f.getFileName().toString());
                if (!m.matches()) continue;
                scanned++;
                int version = Integer.parseInt(m.group(1));
                String sql = Files.readString(f, StandardCharsets.UTF_8);
                for (String v : violations(version, sql, FREEZE_FROM)) {
                    allViolations.add("V" + String.format("%03d", version) + ": " + v);
                }
            }
        }
        // sanity：确保真正扫到了文件，防止路径未解析时 vacuous 通过。
        assertTrue(scanned >= 16,
                "应至少扫到 16 个迁移文件（实际: " + scanned + "）——路径解析或文件列举可能有问题");
        assertTrue(allViolations.isEmpty(),
                "发现 forward-only 违规（V" + FREEZE_FROM + "+ 禁破坏性 DDL）:\n"
                        + String.join("\n", allViolations));
    }
}
