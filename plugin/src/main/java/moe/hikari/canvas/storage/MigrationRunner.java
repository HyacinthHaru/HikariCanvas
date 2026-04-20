package moe.hikari.canvas.storage;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Logger;

/**
 * 按 {@code docs/data-model.md §6.2} 的约定维护 schema 版本。
 *
 * <p>M2 阶段只有 V001 一个迁移脚本。未来新增迁移时：
 * <ol>
 *   <li>在 {@code src/main/resources/db-migrations/} 新增 {@code V<NNN>__<name>.sql}</li>
 *   <li>在本类的 {@link #MIGRATIONS} 列表末尾追加条目</li>
 * </ol>
 * 不做 classpath 目录扫描——jar 内 resource 扫描在 shadow jar 下不稳定，显式声明更安全。
 */
public final class MigrationRunner {

    /** 按序号递增排列；每个条目 = (version, classpath 资源路径)。 */
    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "db-migrations/V001__initial.sql")
    );

    private final Jdbi jdbi;
    private final Logger log;

    public MigrationRunner(Jdbi jdbi, Logger log) {
        this.jdbi = jdbi;
        this.log = log;
    }

    public void run() {
        jdbi.useHandle(h -> {
            ensureSchemaVersionTable(h);
            int currentVersion = h.createQuery(
                            "SELECT COALESCE(MAX(version), 0) AS v FROM schema_version")
                    .mapTo(Integer.class)
                    .one();
            log.info("DB schema current version: " + currentVersion);

            for (Migration m : MIGRATIONS) {
                if (m.version <= currentVersion) continue;
                log.info("Applying migration V" + String.format("%03d", m.version) + " ...");
                applyMigration(h, m);
                h.execute(
                        "INSERT INTO schema_version (version, applied_at) VALUES (?, ?)",
                        m.version, System.currentTimeMillis());
                log.info("  ✓ V" + String.format("%03d", m.version) + " applied");
            }
        });
    }

    private void ensureSchemaVersionTable(Handle h) {
        h.execute("""
                CREATE TABLE IF NOT EXISTS schema_version (
                    version    INTEGER PRIMARY KEY,
                    applied_at INTEGER NOT NULL
                )
                """);
    }

    private void applyMigration(Handle h, Migration m) {
        String sql = loadResource(m.resourcePath);
        // SQLite JDBC 不支持一次 execute 多条语句，需要按 ; 拆分
        for (String stmt : sql.split(";")) {
            String trimmed = stmt.trim();
            if (trimmed.isEmpty()) continue;
            h.execute(trimmed);
        }
    }

    /**
     * 读取 classpath 资源并**先行剥离 {@code --} 注释行**。
     *
     * <p>不能在 split 之后再用 {@code startsWith("--")} 判断——
     * 因为一段注释可能紧挨着一条 DDL（如 V001 开头的文件级注释接
     * 第一个 {@code CREATE TABLE}），split 得到的片段以 {@code "--"}
     * 开头但里面其实藏着真 SQL，整段会被误跳过。</p>
     */
    private String loadResource(String path) {
        InputStream in = getClass().getClassLoader().getResourceAsStream(path);
        if (in == null) {
            throw new IllegalStateException("Migration resource not found: " + path);
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("--")) continue;
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read migration: " + path, e);
        }
    }

    private record Migration(int version, String resourcePath) {}
}
