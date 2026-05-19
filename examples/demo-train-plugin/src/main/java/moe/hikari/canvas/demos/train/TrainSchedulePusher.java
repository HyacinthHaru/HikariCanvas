package moe.hikari.canvas.demos.train;

import moe.hikari.canvas.api.HikariCanvasAPI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.LocalTime;

/**
 * 每 5s 模拟两条线路的下一班车 ETA。
 *
 * <p>真实插件这里应接铁路 plugin / DB / 计算系统；此 demo 仅用 {@link LocalTime#now()}
 * 算"下一个 5~15 分钟边界"作为占位。每次 push 三个变量（next_departure / next_destination
 * / eta_minutes），TTL 10 分钟兜底——若插件挂掉超 10 分钟，HikariCanvas 走 fallback 链
 * （详见 {@code docs/dynamic-data.md §5.3}）。</p>
 */
public final class TrainSchedulePusher {
    /** 5 秒 = 100 ticks。 */
    private static final long REFRESH_TICKS = 20L * 5;

    /** TTL 10 分钟：插件挂掉后变量这段时间内仍可见，再之后 fallback。 */
    private static final Duration VALUE_TTL = Duration.ofMinutes(10);

    private final JavaPlugin plugin;
    private final HikariCanvasAPI canvas;
    private final String namespace;
    private BukkitTask task;

    public TrainSchedulePusher(JavaPlugin plugin, HikariCanvasAPI canvas, String namespace) {
        this.plugin = plugin;
        this.canvas = canvas;
        this.namespace = namespace;
    }

    public void start() {
        if (task != null) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::push, 0L, REFRESH_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void push() {
        LocalTime now = LocalTime.now();
        // 模拟两条线，错开 7 分钟避免完全同步
        pushLine("line1", computeNextDeparture(now, 0), "终点：东站");
        pushLine("line2", computeNextDeparture(now, 7), "终点：西站");
    }

    private void pushLine(String linePrefix, LocalTime departure, String destination) {
        long etaMin = Duration.between(LocalTime.now(), departure).toMinutes();
        if (etaMin < 0) {
            etaMin += 1440;  // 过零点
        }

        canvas.setVariable(plugin, namespace, linePrefix + ".next_departure",
                String.format("%02d:%02d", departure.getHour(), departure.getMinute()),
                VALUE_TTL);
        canvas.setVariable(plugin, namespace, linePrefix + ".next_destination",
                destination,
                VALUE_TTL);
        canvas.setVariable(plugin, namespace, linePrefix + ".eta_minutes",
                String.valueOf(etaMin),
                VALUE_TTL);
    }

    /**
     * 模拟 next_departure：当前时刻 + 5~15 min 的下一班车（按分钟网格 + offset 错开线路）。
     */
    private LocalTime computeNextDeparture(LocalTime now, int offsetMin) {
        int minutesUntilNext = 5 + ((now.getMinute() + offsetMin) % 11);  // 5~15 min
        return now.plusMinutes(minutesUntilNext).withSecond(0).withNano(0);
    }
}
