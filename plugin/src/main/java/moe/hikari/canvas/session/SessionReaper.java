package moe.hikari.canvas.session;

import moe.hikari.canvas.deploy.FrameDeployer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;

/**
 * 主线程定时扫描，按 {@link SessionManager#collectExpired} 的规则把超时会话 cancel 掉。
 * 契约见 {@code docs/architecture.md §3.1}。
 *
 * <p>与 {@code /canvas cancel} 命令的行为一致：先快照墙面 world，再 cancel
 * （归还池 + 释放锁 + forget），最后 {@link FrameDeployer#removeForSession} 清物品框。</p>
 *
 * <p><b>主线程约束：</b> {@code cancel} 会触发 {@link moe.hikari.canvas.pool.MapPool#returnToPool}
 * 与 {@code world.getEntitiesByClass}，只能在主线程执行。task 用
 * {@link org.bukkit.scheduler.BukkitScheduler#runTaskTimer} 而不是 async 变体。</p>
 */
public final class SessionReaper {

    private final JavaPlugin plugin;
    private final SessionManager sessions;
    private final FrameDeployer frameDeployer;
    private final Logger log;

    private final long issuedTimeoutMs;
    private final long wsGraceMs;
    private final long activeIdleMs;

    private BukkitTask task;

    public SessionReaper(JavaPlugin plugin,
                         SessionManager sessions,
                         FrameDeployer frameDeployer,
                         Logger log,
                         Duration issuedTimeout,
                         Duration wsGrace,
                         Duration activeIdle) {
        this.plugin = plugin;
        this.sessions = sessions;
        this.frameDeployer = frameDeployer;
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
        long now = System.currentTimeMillis();
        List<SessionManager.ExpiredSession> expired = sessions.collectExpired(
                now, issuedTimeoutMs, wsGraceMs, activeIdleMs);
        if (expired.isEmpty()) return;

        for (SessionManager.ExpiredSession e : expired) {
            // 先快照墙面 world：cancel 会把 session 从 byId 里 forget
            Session s = sessions.byId(e.id());
            World world = (s != null && s.wall() != null) ? s.wall().world() : null;

            sessions.cancel(e.id(), e.reason());

            int frames = 0;
            if (world != null) {
                frames = frameDeployer.removeForSession(e.id(), world);
            }
            log.info("SessionReaper: cancelled " + e.id()
                    + " reason=" + e.reason() + " frames=" + frames);
        }
    }
}
