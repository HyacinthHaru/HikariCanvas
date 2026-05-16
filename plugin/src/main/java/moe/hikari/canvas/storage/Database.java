package moe.hikari.canvas.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlite3.SQLitePlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * SQLite + HikariCP + JDBI 的封装。按 {@code docs/data-model.md §2.1} 的约定：
 * <ul>
 *   <li>文件：{@code plugins/HikariCanvas/data.db}（传入的 {@link Path} 决定实际位置）</li>
 *   <li>连接池：HikariCP，最大 4 连接</li>
 *   <li>访问层：JDBI 3</li>
 *   <li>SQLite 打开 WAL 模式 + 外键约束</li>
 * </ul>
 *
 * <p>启动时先确保父目录存在、{@code schema_version} 表存在，然后由
 * {@link MigrationRunner} 应用 classpath 下 {@code db-migrations/} 的增量脚本。</p>
 */
public final class Database implements AutoCloseable {

    private final Logger log;
    private final HikariDataSource dataSource;
    private final Jdbi jdbi;

    public Database(Logger log, Path dbFile) {
        this.log = log;
        try {
            Files.createDirectories(dbFile.getParent());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create DB parent dir: " + dbFile.getParent(), e);
        }

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        cfg.setMaximumPoolSize(4);
        cfg.setPoolName("HikariCanvas-SQLite");
        // M16 P5.2：连接租借超 30s 未还视为泄漏，HikariCP log WARN + 栈跟踪。
        // dev/prod 均启用——SQLite 单写场景下任何超 30s 的连接都几乎必是 bug
        // （editor op / migration / wall query 均亚秒级），早暴露胜过线上锁死。
        cfg.setLeakDetectionThreshold(30_000);
        // SQLite 特定：WAL 模式（读写并发友好）+ 外键约束
        cfg.addDataSourceProperty("journal_mode", "WAL");
        cfg.addDataSourceProperty("foreign_keys", "true");
        // M15.1 P0-31：WAL 下并发写撞 BUSY 会立即抛 SQLITE_BUSY；5s busy_timeout
        // 让 SQLite 内部自旋重试，配合 HikariCP 4 连接消除竞争异常。
        cfg.addDataSourceProperty("busy_timeout", "5000");

        this.dataSource = new HikariDataSource(cfg);
        this.jdbi = Jdbi.create(dataSource).installPlugin(new SQLitePlugin());

        log.info("Database initialized: " + dbFile);
    }

    public Jdbi jdbi() {
        return jdbi;
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("Database closed");
        }
    }
}
