package moe.hikari.canvas.demos.score;

import moe.hikari.canvas.api.HikariCanvasAPI;
import moe.hikari.canvas.api.NamespaceInfo;
import moe.hikari.canvas.api.VarType;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * HikariCanvas 0.4.0 Push API 示例：事件 + 命令 push 范型。
 *
 * <p>玩家进服更新 {@code mvp}（PlayerJoinEvent 触发）；通过 {@code /demoscore add red 1}
 * 命令累加红/蓝队比分。所有变化即时 push 到 HikariCanvas，wall 上引用
 * {@code ${var:demo_score/red}} 等占位符即可实时显示。</p>
 *
 * <p>对比 {@link moe.hikari.canvas.demos.train.DemoTrainPlugin} 的"定时器 push"模式，本插件
 * 演示"事件驱动 push"——不开 BukkitTask，仅在比分变化时调 setVariable。</p>
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
                getDescription().getVersion()
        ));

        canvas.declareKey(this, NAMESPACE, "red", VarType.NUMBER, "红队当前比分");
        canvas.declareKey(this, NAMESPACE, "blue", VarType.NUMBER, "蓝队当前比分");
        canvas.declareKey(this, NAMESPACE, "mvp", VarType.STRING, "MVP 玩家名");

        pushScores();  // 初始值

        getServer().getPluginManager().registerEvents(
                new DemoScoreListener(this), this);

        PluginCommand cmd = getCommand("demoscore");
        if (cmd != null) {
            cmd.setExecutor(new DemoScoreCommand(this));
        } else {
            getLogger().warning("/demoscore command not registered (missing paper-plugin.yml entry?)");
        }

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
        // mvp 永久 TTL（直到下一玩家进服覆盖）
        canvas.setVariable(this, NAMESPACE, "mvp", playerName, null);
    }

    private void pushScores() {
        canvas.setVariable(this, NAMESPACE, "red", String.valueOf(redScore), null);
        canvas.setVariable(this, NAMESPACE, "blue", String.valueOf(blueScore), null);
    }
}
