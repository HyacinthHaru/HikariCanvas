package moe.hikari.canvas.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 清理过期的 migration 备份文件（{@code data.db.pre-V<NNN>.bak[-wal|-shm]}）。
 * 启动期 migration 后跑一次（不做周期 scheduler）。只动本插件产的备份文件。
 */
public final class BackupReaper {

    private final Logger log;

    public BackupReaper(Logger log) { this.log = log; }

    /**
     * 删除 {@code dir} 下早于 {@code retentionDays} 天的备份文件。
     *
     * @param dir           插件数据目录（通常 {@code plugins/HikariCanvas/}）
     * @param dbBaseName    data.db 文件名（匹配 {@code <name>.pre-V<NNN>.bak[-wal|-shm]}）
     * @param retentionDays ≤0 时禁用（保留全部）
     * @param nowMillis     当前时间毫秒（测试注入，避免 System.currentTimeMillis()）
     * @return 删除的文件数
     */
    public int reap(Path dir, String dbBaseName, int retentionDays, long nowMillis) {
        if (retentionDays <= 0) return 0;
        if (dir == null || !Files.isDirectory(dir)) return 0;

        // 只匹配本插件备份：<dbBaseName>.pre-V<digits>.bak 可带 -wal/-shm 后缀
        Pattern p = Pattern.compile(
                Pattern.quote(dbBaseName) + "\\.pre-V\\d+\\.bak(-wal|-shm)?");
        long cutoff = nowMillis - (long) retentionDays * 24L * 60L * 60L * 1000L;

        List<Path> toDelete = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                if (!p.matcher(f.getFileName().toString()).matches()) continue;
                long mtime;
                try {
                    mtime = Files.getLastModifiedTime(f).toMillis();
                } catch (IOException e) {
                    continue;
                }
                if (mtime < cutoff) toDelete.add(f);
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "Backup reaper: failed to list " + dir, e);
            return 0;
        }

        int reaped = 0;
        for (Path f : toDelete) {
            try {
                Files.deleteIfExists(f);
                reaped++;
            } catch (IOException e) {
                log.log(Level.WARNING, "Backup reaper: failed to delete " + f, e);
            }
        }
        if (reaped > 0) {
            log.info("Backup reaper: deleted " + reaped + " expired backup file(s) (older than "
                    + retentionDays + " days)");
        }
        return reaped;
    }
}
