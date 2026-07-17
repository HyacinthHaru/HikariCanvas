package ac.haru.hikaricanvas.storage;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 按 {@code docs/data-model.md §6.2} 的约定维护 schema 版本。
 *
 * <p>新增迁移时：
 * <ol>
 *   <li>在 {@code src/main/resources/db-migrations/} 新增 {@code V<NNN>__<name>.sql}</li>
 *   <li>在本类的 {@link #MIGRATIONS} 列表末尾追加条目</li>
 * </ol>
 * 不做 classpath 目录扫描——jar 内 resource 扫描在 shadow jar 下不稳定，显式声明更安全。
 *
 * <p>SQL 拆分识别字符串字面量 + 每个 migration 包事务 + 可选自动备份 data.db。</p>
 */
public final class MigrationRunner {

    /** 按序号递增排列；每个条目 = (version, classpath 资源路径)。 */
    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "db-migrations/V001__initial.sql"),
            new Migration(2, "db-migrations/V002__drafts.sql"),
            new Migration(3, "db-migrations/V003__drafts_add_maps.sql"),
            new Migration(4, "db-migrations/V004__drafts_wall_id_alias.sql"),
            new Migration(5, "db-migrations/V005__walls_unified.sql"),
            new Migration(6, "db-migrations/V006__walls_protocol_version.sql"),
            new Migration(7, "db-migrations/V007__image_uploads.sql"),
            new Migration(8, "db-migrations/V008__templates.sql"),
            // V009 跳号（迭代过程中预留，未落地脚本）。
            new Migration(10, "db-migrations/V010__remove_refcount.sql"),
            new Migration(11, "db-migrations/V011__user_variables.sql"),
            new Migration(12, "db-migrations/V012__wall_schedules.sql"),
            // per-wall schedule 精度（minute / second）
            new Migration(13, "db-migrations/V013__schedule_precision.sql"),
            // 变量别名（per-wall，全 namespace 通用）
            new Migration(14, "db-migrations/V014__variable_aliases.sql"),
            // 全局用户变量（userglobal/* namespace；name 全服唯一）
            //
            // V015 / V016 必须显式登记在此列表——否则 SQL 文件存在但永远不会被运行，
            // 服务器启动后代码 INSERT 这两表会抛 SQLITE_ERROR "no such table"。
            // 这是显式声明而非目录扫描的代价。
            new Migration(15, "db-migrations/V015__user_global_variables.sql"),
            // 铁路网络（线路 / 站点 / 车次 / 时刻表 / wall 绑定）
            new Migration(16, "db-migrations/V016__rail_network.sql"),
            // 墙脚本（视觉运行时；rule_json 整体存 ScriptRule）
            new Migration(17, "db-migrations/V017__wall_scripts.sql")
    );

    private final Jdbi jdbi;
    private final Logger log;
    private final boolean autoBackup;
    private final Path dbFilePath;

    /** 兼容老 API：默认关闭自动备份。 */
    public MigrationRunner(Jdbi jdbi, Logger log) {
        this(jdbi, log, false, null);
    }

    /**
     * @param autoBackup 跑每个待执行 migration 前是否先备份 db 文件
     * @param dbFilePath data.db 文件绝对路径；autoBackup=false 时可传 null
     */
    public MigrationRunner(Jdbi jdbi, Logger log, boolean autoBackup, Path dbFilePath) {
        this.jdbi = jdbi;
        this.log = log;
        this.autoBackup = autoBackup;
        this.dbFilePath = dbFilePath;
    }

    public void run() {
        runUpTo(Integer.MAX_VALUE);
    }

    /** 应用 version ≤ maxVersion 的待执行迁移（测试 seam：fixture / 备份测试按版本分段跑）。 */
    public void runUpTo(int maxVersion) {
        jdbi.useHandle(h -> {
            ensureSchemaVersionTable(h);
            int currentVersion = h.createQuery(
                            "SELECT COALESCE(MAX(version), 0) AS v FROM schema_version")
                    .mapTo(Integer.class)
                    .one();
            log.info("DB schema current version: " + currentVersion);

            for (Migration m : MIGRATIONS) {
                if (m.version <= currentVersion) continue;
                if (m.version > maxVersion) break; // MIGRATIONS 按版本升序，可提前 break
                log.info("Applying migration V" + String.format("%03d", m.version) + " ...");
                // 可选自动备份（WAL 安全）：在 per-migration 事务之前、用同一连接 checkpoint。
                if (autoBackup && dbFilePath != null) {
                    tryBackup(h, m.version);
                }
                // 每个 migration 包事务；DDL 失败时回滚不留半态。
                // 注：SQLite 大多数 DDL 也是事务安全的；JDBI 的 useTransaction 在已有
                // handle 上开 savepoint 即可，无需独立 handle。
                h.useTransaction(txHandle -> {
                    applyMigrationTx(txHandle, m);
                    txHandle.execute(
                            "INSERT INTO schema_version (version, applied_at) VALUES (?, ?)",
                            m.version, System.currentTimeMillis());
                });
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

    /**
     * 备份 data.db 到 {@code data.db.pre-V<NNN>.bak}。WAL 安全：
     * 先在迁移连接上 {@code PRAGMA wal_checkpoint(TRUNCATE)} 把已提交事务从 -wal 刷进主库
     * 并截断 -wal，再 copy 主库 + 仍存在的 -wal/-shm（备份成完整文件集）。
     *
     * <p>迁移在 onEnable 单线程跑、此时无其它连接写入，故 checkpoint 后主库即一致快照。
     * 失败不抛——备份只是兜底，主链路仍让 migration 跑。</p>
     */
    private void tryBackup(Handle h, int version) {
        try {
            // WAL → 主库：TRUNCATE 把所有已提交帧落主库并清空 -wal。
            h.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            String base = dbFilePath.getFileName().toString();
            Path dir = dbFilePath.getParent();
            String suffix = ".pre-V" + String.format("%03d", version) + ".bak";
            Path mainBak = dir.resolve(base + suffix);
            Files.copy(dbFilePath, mainBak, StandardCopyOption.REPLACE_EXISTING);
            // 连同 -wal/-shm 一起备份（checkpoint TRUNCATE 后通常已空，但完整文件集让恢复更稳妥）。
            for (String sidecar : new String[]{"-wal", "-shm"}) {
                Path src = dir.resolve(base + sidecar);
                if (Files.exists(src)) {
                    Files.copy(src, dir.resolve(base + suffix + sidecar),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
            log.info("DB backed up (WAL-checkpointed) to " + mainBak);
        } catch (Exception e) {
            log.log(Level.WARNING, "DB backup failed (continuing anyway)", e);
        }
    }

    private void applyMigrationTx(Handle h, Migration m) {
        String sql = loadResource(m.resourcePath);
        // SQLite JDBC 不支持一次 execute 多条语句，需要按 ; 拆分
        for (String stmt : splitSqlStatements(sql)) {
            String trimmed = stmt.trim();
            if (trimmed.isEmpty()) continue;
            h.execute(trimmed);
        }
    }

    /**
     * SQL 拆分识别单引号字符串字面量 + 行内 {@code --} 注释。
     *
     * <p>朴素 {@code sql.split(";")} 会把 {@code INSERT VALUES ('a;b')} 这种含分号的
     * 字符串字面量截断；同理 {@code 'It''s'} 这种 SQL escape 也得保住。SQLite 标准其实
     * 不支持双引号字符串字面量（双引号是 identifier quote），但这里一并兼容处理一下，
     * 老 DDL 偶尔会用到。</p>
     *
     * <p>不做完整 SQL parser，只识别这三个状态：单引号串内 / 双引号串内 / 行内 -- 注释。
     * 多行 {@code /\* ... *\/} 注释暂不处理——我们的 V001..V008 都不用。</p>
     */
    static List<String> splitSqlStatements(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inLineComment = false;
        int i = 0, n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    current.append(c);
                }
                i++;
                continue;
            }
            if (inSingle) {
                current.append(c);
                if (c == '\'') {
                    // SQL escape '' → 仍在字符串内
                    if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                        current.append('\'');
                        i += 2;
                        continue;
                    }
                    inSingle = false;
                }
                i++;
                continue;
            }
            if (inDouble) {
                current.append(c);
                if (c == '"') inDouble = false;
                i++;
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                inLineComment = true;
                i += 2;
                continue;
            }
            if (c == '\'') { inSingle = true; current.append(c); i++; continue; }
            if (c == '"')  { inDouble = true; current.append(c); i++; continue; }
            if (c == ';') {
                out.add(current.toString());
                current.setLength(0);
                i++;
                continue;
            }
            current.append(c);
            i++;
        }
        String last = current.toString().trim();
        if (!last.isEmpty()) out.add(last);
        return out;
    }

    /**
     * 读取 classpath 资源并**先行剥离 {@code --} 注释行**。
     *
     * <p>双重保险：splitSqlStatements 内已识别行内 -- 注释，这里再把整行注释剥掉，
     * 既缩短 split 工作量，也对老逻辑兼容。</p>
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
