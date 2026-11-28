package ac.haru.hikaricanvas.demos.score;

import ac.haru.hikaricanvas.api.HikariCanvasAPI;
import ac.haru.hikaricanvas.api.NamespaceInfo;
import ac.haru.hikaricanvas.api.VarType;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;

/**
 * HikariCanvas Push API 示例：事件与命令驱动推送范型。
 *
 * <p>玩家进服更新 {@code mvp}（PlayerJoinEvent 触发）；通过 {@code /demoscore add red 1}
 * 命令累加红/蓝队比分。所有变化即时推送到 HikariCanvas，画板上引用
 * {@code ${var:demo_score/red}} 等占位符即可实时显示。</p>
 *
 * <p>对比 {@code DemoTrainPlugin} 的定时批量推送模式，本插件演示事件驱动单条推送——
 * 不起定时任务，仅在比分或状态变化时调用 {@link HikariCanvasAPI#setVariable}。</p>
 */
public final class DemoScorePlugin extends JavaPlugin {
    private static final String NAMESPACE = "demo_score";

    private HikariCanvasAPI canvas;
    private int redScore = 0;
    private int blueScore = 0;

    @Override
    public void onEnable() {
        canvas = Bukkit.getServicesManager().load(HikariCanvasAPI.class);
        if (canvas == null) {
            getLogger().severe("HikariCanvas not available; disabling");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        canvas.registerNamespace(this, NAMESPACE, new NamespaceInfo(
                "Demo 比分",
                "DemoScorePlugin",
                getPluginMeta().getVersion()
        ));

        canvas.declareKey(this, NAMESPACE, "red", VarType.NUMBER, "红队当前比分");
        canvas.declareKey(this, NAMESPACE, "blue", VarType.NUMBER, "蓝队当前比分");
        canvas.declareKey(this, NAMESPACE, "mvp", VarType.STRING, "MVP 玩家名");

        pushScores();  // 初始值

        getServer().getPluginManager().registerEvents(
                new DemoScoreListener(this), this);

        registerCommand("demoscore", "调整示例比分（add / set / reset）", new DemoScoreCommand(this));

        getLogger().info("DemoScorePlugin enabled");
    }

    @Override
    public void onDisable() {
        // namespace cleanup 由 HikariCanvas PluginDisableEvent listener 自动做（30s grace）
        getLogger().info("DemoScorePlugin disabled");
    }

    // ---- 公开给 listener / command 的 mutation API ----

    public void addRed(int delta) {
        redScore += delta;
        pushScores();
    }

    public void addBlue(int delta) {
        blueScore += delta;
        pushScores();
    }

    public void setRed(int value) {
        redScore = value;
        pushScores();
    }

    public void setBlue(int value) {
        blueScore = value;
        pushScores();
    }

    public void reset() {
        redScore = 0;
        blueScore = 0;
        pushScores();
    }

    public void setMvp(String playerName) {
        // 显式指定永久有效（直到下一名玩家进服覆盖）
        canvas.setVariable(this, NAMESPACE, "mvp", playerName, Duration.ZERO);
    }

    private void pushScores() {
        canvas.setVariable(this, NAMESPACE, "red", String.valueOf(redScore), Duration.ZERO);
        canvas.setVariable(this, NAMESPACE, "blue", String.valueOf(blueScore), Duration.ZERO);
    }
}
