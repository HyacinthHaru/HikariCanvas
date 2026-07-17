package ac.haru.hikaricanvas.demos.train;

import ac.haru.hikaricanvas.api.HikariCanvasAPI;
import ac.haru.hikaricanvas.api.NamespaceInfo;
import ac.haru.hikaricanvas.api.VarType;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * HikariCanvas 0.4.0 Push API 示例：定时器 push 范型。
 *
 * <p>每 5 秒模拟两条线路的下一班车 ETA，通过 {@link HikariCanvasAPI#setVariable} 推送给
 * HikariCanvas；wall 上引用 {@code ${var:demo_train/line1.next_departure}} 等占位符即可
 * 在游戏内地图渲染实时倒计时。</p>
 *
 * <h2>关键约定</h2>
 * <ul>
 *   <li>使用 {@code Bukkit.getServicesManager().load(HikariCanvasAPI.class)} 获取 API
 *       （零编译耦合，HikariCanvas 主类无需 import）</li>
 *   <li>{@code paper-plugin.yml} 声明 {@code HikariCanvas} 为 required + load BEFORE</li>
 *   <li>onDisable 无需手动 unregister namespace —— HikariCanvas 主插件监听
 *       PluginDisableEvent 自动清理（30s grace）</li>
 * </ul>
 *
 * @see ac.haru.hikaricanvas.api.HikariCanvasAPI
 * @see TrainSchedulePusher
 */
public final class DemoTrainPlugin extends JavaPlugin {
    private static final String NAMESPACE = "demo_train";

    private HikariCanvasAPI canvas;
    private TrainSchedulePusher pusher;

    @Override
    public void onEnable() {
        // 1. 拿 HikariCanvasAPI（推荐 ServicesManager 路径；零编译耦合）
        canvas = Bukkit.getServicesManager().load(HikariCanvasAPI.class);
        if (canvas == null) {
            getLogger().severe("HikariCanvas not available; this plugin requires HikariCanvas to run");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 2. 注册 namespace
        canvas.registerNamespace(this, NAMESPACE, new NamespaceInfo(
                "Demo 列车时刻表",
                "DemoTrainPlugin",
                getDescription().getVersion()
        ));

        // 3. 声明可用 key（让编辑器 Picker 自动补全显示）
        canvas.declareKey(this, NAMESPACE, "line1.next_departure", VarType.STRING, "1 号线下一班车出发时间 HH:mm");
        canvas.declareKey(this, NAMESPACE, "line1.next_destination", VarType.STRING, "1 号线下一班车终点");
        canvas.declareKey(this, NAMESPACE, "line1.eta_minutes", VarType.NUMBER, "1 号线距下一班车分钟");
        canvas.declareKey(this, NAMESPACE, "line2.next_departure", VarType.STRING, "2 号线下一班车出发时间 HH:mm");
        canvas.declareKey(this, NAMESPACE, "line2.next_destination", VarType.STRING, "2 号线下一班车终点");
        canvas.declareKey(this, NAMESPACE, "line2.eta_minutes", VarType.NUMBER, "2 号线距下一班车分钟");

        // 4. 启动定时 push（每 5s 模拟 ETA 倒数）
        this.pusher = new TrainSchedulePusher(this, canvas, NAMESPACE);
        pusher.start();

        getLogger().info("DemoTrainPlugin enabled; pushing schedule to HikariCanvas every 5s");
    }

    @Override
    public void onDisable() {
        if (pusher != null) {
            pusher.stop();
            pusher = null;
        }
        // namespace cleanup 由 HikariCanvas PluginDisableEvent listener 自动做（30s grace）
        getLogger().info("DemoTrainPlugin disabled");
    }
}
