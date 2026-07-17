package ac.haru.hikaricanvas.session;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;

/**
 * 主线程定时扫描，按 {@link SessionManager#collectExpired} 的规则把超时会话 cancel 掉。
 * 契约见 {@code docs/architecture.md §3.1}。
 *
 * <p>cancel 不拆 ItemFrames、不归还池——wall 数据生命周期与 session 解耦。
 * 玩家断线 / 超时仅释放编辑锁，墙上画照常显示，玩家可后续 `/canvas open` 接管。</p>
 */
public final class SessionReaper {

    private final JavaPlugin plugin;
    private final SessionManager sessions;
    private final Logger log;

    private final long issuedTimeoutMs;
    private final long wsGraceMs;
    private final long activeIdleMs;

    private BukkitTask task;

    public SessionReaper(JavaPlugin plugin,
                         SessionManager sessions,
                         Logger log,
                         Duration issuedTimeout,
                         Duration wsGrace,
                         Duration activeIdle) {
        this.plugin = plugin;
        this.sessions = sessions;
        this.log = log;
        this.issuedTimeoutMs = issuedTimeout.toMillis();
        this.wsGraceMs = wsGrace.toMillis();
        this.activeIdleMs = activeIdle.toMillis();
    }

    /** 启动定时扫描。{@code periodTicks} 建议 600（30 秒）。 */
    public void start(long periodTicks) {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(
                plugin, this::sweep, periodTicks, periodTicks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void sweep() {
        // 每次 sweep 顺手清各 session 的 stale brush stroke buffer
        // （用户永久离开后 EditSession 内 strokes Map 不会自动清）
        sessions.purgeAllStaleStrokes();

        long now = System.currentTimeMillis();
        List<SessionManager.ExpiredSession> expired = sessions.collectExpired(
                now, issuedTimeoutMs, wsGraceMs, activeIdleMs);
        if (expired.isEmpty()) return;

        for (SessionManager.ExpiredSession e : expired) {
            sessions.cancel(e.id(), e.reason());
            log.info("SessionReaper: cancelled " + e.id() + " reason=" + e.reason());
        }
    }
}
