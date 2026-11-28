package ac.haru.hikaricanvas.demos.train;

import ac.haru.hikaricanvas.api.HikariCanvasAPI;
import ac.haru.hikaricanvas.api.VariableUpdate;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 每 5s 模拟两条线路的下一班车到发时刻，并通过 {@link HikariCanvasAPI#setVariables} 批量推送。
 *
 * <p>真实插件这里应接铁路业务或外部数据库；此示例用 {@link LocalTime#now()} 计算下一个
 * 5~15 分钟边界作为演示数据。每次将两条线路共 6 个变量组装为一个 Map 一次提交，有效期设为
 * 10 分钟兜底——若插件停摆超过 10 分钟，画布自动按兜底链回退（详见 {@code docs/dynamic-data.md §5.3}）。</p>
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
        Map<String, VariableUpdate> batch = new LinkedHashMap<>(6);
        // 模拟两条线，错开 7 分钟避免完全同步，合并为一次批量推送
        collectLineUpdates(batch, "line1", now, computeNextDeparture(now, 0), "终点：东站");
        collectLineUpdates(batch, "line2", now, computeNextDeparture(now, 7), "终点：西站");
        canvas.setVariables(plugin, namespace, batch);
    }

    private static void collectLineUpdates(Map<String, VariableUpdate> batch, String linePrefix,
                                           LocalTime now, LocalTime departure, String destination) {
        long etaMin = Duration.between(now, departure).toMinutes();
        if (etaMin < 0) {
            etaMin += 1440;  // 过零点
        }
        batch.put(linePrefix + ".next_departure", new VariableUpdate(
                String.format("%02d:%02d", departure.getHour(), departure.getMinute()), VALUE_TTL));
        batch.put(linePrefix + ".next_destination", new VariableUpdate(destination, VALUE_TTL));
        batch.put(linePrefix + ".eta_minutes", new VariableUpdate(String.valueOf(etaMin), VALUE_TTL));
    }

    /**
     * 模拟 next_departure：当前时刻 + 5~15 min 的下一班车（按分钟网格 + offset 错开线路）。
     */
    private LocalTime computeNextDeparture(LocalTime now, int offsetMin) {
        int minutesUntilNext = 5 + ((now.getMinute() + offsetMin) % 11);  // 5~15 min
        return now.plusMinutes(minutesUntilNext).withSecond(0).withNano(0);
    }
}
