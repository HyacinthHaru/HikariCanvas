package moe.hikari.canvas.storage;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.logging.Logger;

/**
 * 0.9.1 迁移 fixture 测试基建（data-model.md §6.6.3）。
 * 子类指定：目标迁移版本 N + baseline（N-1）+ fixture 名。流程：
 * runUpTo(baseline) 建 schema → 灌 {@code migration-fixtures/<name>/before.sql} 种子数据 →
 * runUpTo(N) 应用目标迁移 → 子类断言数据无损 / 新结构正确。
 */
abstract class MigrationFixtureTestBase {

    private Path tmpDir;
    protected Database database;
    protected Jdbi jdbi;

    /** 目标迁移版本（应用它，断言其转换正确）。 */
    protected abstract int targetVersion();

    /** fixture 目录名（{@code migration-fixtures/<name>/before.sql}）。 */
    protected abstract String fixtureName();

    @BeforeEach
    void setUpFixture() throws Exception {
        tmpDir = Files.createTempDirectory("hikari-fixture-");
        Logger log = Logger.getLogger("test");
        database = new Database(log, tmpDir.resolve("data.db"));
        jdbi = database.jdbi();

        // 1) 建 baseline schema（target-1）
        new MigrationRunner(jdbi, log).runUpTo(targetVersion() - 1);
        // 2) 灌 fixture 种子数据
        String fixture = "migration-fixtures/" + fixtureName() + "/before.sql";
        var in = getClass().getClassLoader().getResourceAsStream(fixture);
        if (in == null) throw new IllegalStateException("fixture not found: " + fixture);
        String sql;
        try (in) { sql = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        jdbi.useHandle(h -> {
            for (String stmt : MigrationRunner.splitSqlStatements(sql)) {
                String t = stmt.trim();
                if (!t.isEmpty()) h.execute(t);
            }
        });
        // 3) 应用目标迁移
        new MigrationRunner(jdbi, log).runUpTo(targetVersion());
    }

    @AfterEach
    void tearDownFixture() throws Exception {
        if (database != null) database.close();
        if (tmpDir != null && Files.exists(tmpDir)) {
            try (var s = Files.walk(tmpDir)) {
                s.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }
}
