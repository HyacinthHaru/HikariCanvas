# 示例插件

仓库 `examples/` 目录下有两个可直接参考的完整示例，覆盖两种最常见的推送范型。先读完[Plugin Push API](/api/index) 再来看代码会更顺。

| 示例 | 范型 | 目录 |
|---|---|---|
| Demo 列车时刻表 | 定时推送——每隔几秒推一批值 | `examples/demo-train-plugin/` |
| Demo 比分牌 | 事件推送——数据一变就立刻推 | `examples/demo-score-plugin/` |

两个示例都用 `Bukkit.getServicesManager().load(...)` 拿 API，`paper-plugin.yml` 都把 HikariCanvas 声明为 `load: BEFORE` 依赖，且都不在 `onDisable` 做清理。

## 定时推送（demo-train）

列车到站屏的典型形态：数据本身一直在变（倒计时每分钟少一），所以开一个定时任务，每 5 秒算一遍、推一遍。

**注册阶段**（`DemoTrainPlugin.onEnable`）拿 API、注册命名空间、声明 6 个 key，然后启动定时任务：

```java
public final class DemoTrainPlugin extends JavaPlugin {
    private static final String NAMESPACE = "demo_train";
    private HikariCanvasAPI canvas;
    private TrainSchedulePusher pusher;

    @Override
    public void onEnable() {
        canvas = Bukkit.getServicesManager().load(HikariCanvasAPI.class);
        if (canvas == null) {
            getLogger().severe("HikariCanvas not available; this plugin requires it");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        canvas.registerNamespace(this, NAMESPACE, new NamespaceInfo(
                "Demo 列车时刻表", "DemoTrainPlugin", getDescription().getVersion()));

        canvas.declareKey(this, NAMESPACE, "line1.next_departure", VarType.STRING, "1 号线下一班车出发时间 HH:mm");
        canvas.declareKey(this, NAMESPACE, "line1.next_destination", VarType.STRING, "1 号线下一班车终点");
        canvas.declareKey(this, NAMESPACE, "line1.eta_minutes", VarType.NUMBER, "1 号线距下一班车分钟");
        // ... line2 同理 ...

        this.pusher = new TrainSchedulePusher(this, canvas, NAMESPACE);
        pusher.start();
    }

    @Override
    public void onDisable() {
        if (pusher != null) { pusher.stop(); pusher = null; }
        // 命名空间清理由 HikariCanvas 监听 PluginDisableEvent 自动做（30s 宽限）
    }
}
```

注意这个示例把 HikariCanvas 设成**强依赖**（`paper-plugin.yml` 里 `required: true`），拿不到 API 就直接禁用自己。如果你希望插件在没装 HikariCanvas 时也能跑，改成 `required: false` 并在 API 为 null 时优雅降级。

**推送阶段**（`TrainSchedulePusher`）用 Bukkit 调度器每 5 秒（100 ticks）跑一次 `push`：

```java
public final class TrainSchedulePusher {
    private static final long REFRESH_TICKS = 20L * 5;          // 5 秒
    private static final Duration VALUE_TTL = Duration.ofMinutes(10);

    private final JavaPlugin plugin;
    private final HikariCanvasAPI canvas;
    private final String namespace;
    private BukkitTask task;

    public void start() {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::push, 0L, REFRESH_TICKS);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
    }

    private void push() {
        LocalTime now = LocalTime.now();
        pushLine("line1", computeNextDeparture(now, 0), "终点：东站");
        pushLine("line2", computeNextDeparture(now, 7), "终点：西站");
    }

    private void pushLine(String linePrefix, LocalTime departure, String destination) {
        long etaMin = Duration.between(LocalTime.now(), departure).toMinutes();
        if (etaMin < 0) etaMin += 1440;  // 过零点

        canvas.setVariable(plugin, namespace, linePrefix + ".next_departure",
                String.format("%02d:%02d", departure.getHour(), departure.getMinute()), VALUE_TTL);
        canvas.setVariable(plugin, namespace, linePrefix + ".next_destination",
                destination, VALUE_TTL);
        canvas.setVariable(plugin, namespace, linePrefix + ".eta_minutes",
                String.valueOf(etaMin), VALUE_TTL);
    }
}
```

要点：

- **TTL 比推送间隔大很多**：每 5 秒推一次，但 TTL 给 10 分钟。这样万一插件挂掉，招牌还能显示 10 分钟旧值再走 fallback，不会因为一次推送失败就立刻显示 `???`。
- **数值也是字符串**：`eta_minutes` 是 `NUMBER` 类型，但推送时仍 `String.valueOf(etaMin)`。
- **`stop()` 里 cancel 任务**就够了，命名空间和变量清理交给 HikariCanvas 自动做。

真实插件把 `computeNextDeparture` 那段换成你接铁路插件 / 数据库 / 真实时刻表的逻辑即可。

招牌上这样引用：

```
1 号线 ${var:demo_train/line1.next_destination}
${var:demo_train/line1.eta_minutes} 分钟后到站
```

## 事件推送（demo-score）

比分牌的典型形态：数据只在"发生了某件事"时才变（有人得分、有人进服），所以**不开定时器**，只在事件回调里推。

**注册阶段**（`DemoScorePlugin.onEnable`）和列车示例一样拿 API、注册、声明 key，额外注册了一个监听器和一条命令：

```java
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
                "Demo 比分", "DemoScorePlugin", getDescription().getVersion()));

        canvas.declareKey(this, NAMESPACE, "red", VarType.NUMBER, "红队当前比分");
        canvas.declareKey(this, NAMESPACE, "blue", VarType.NUMBER, "蓝队当前比分");
        canvas.declareKey(this, NAMESPACE, "mvp", VarType.STRING, "MVP 玩家名");

        pushScores();  // 先推一次初始值

        getServer().getPluginManager().registerEvents(new DemoScoreListener(this), this);
        getCommand("demoscore").setExecutor(new DemoScoreCommand(this));
    }

    // 比分变化的入口：改完内存值，立刻推
    public void addRed(int delta)  { redScore += delta; pushScores(); }
    public void addBlue(int delta) { blueScore += delta; pushScores(); }

    public void setMvp(String playerName) {
        canvas.setVariable(this, NAMESPACE, "mvp", playerName, null);  // null = 沿用原 TTL
    }

    private void pushScores() {
        canvas.setVariable(this, NAMESPACE, "red", String.valueOf(redScore), null);
        canvas.setVariable(this, NAMESPACE, "blue", String.valueOf(blueScore), null);
    }
}
```

**事件触发**——玩家进服就把他设为 MVP（真实插件会基于击杀/助攻统计算）：

```java
public final class DemoScoreListener implements Listener {
    private final DemoScorePlugin plugin;
    public DemoScoreListener(DemoScorePlugin plugin) { this.plugin = plugin; }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent ev) {
        plugin.setMvp(ev.getPlayer().getName());
    }
}
```

**命令触发**——管理员用 `/demoscore add red 1` 改比分，每次改完都会 `pushScores()`：

```java
// DemoScoreCommand 摘录：/demoscore <add|set|reset> [team] [value]
case "add" -> {
    int n = Integer.parseInt(args[2]);
    if (args[1].equalsIgnoreCase("red"))  plugin.addRed(n);
    else if (args[1].equalsIgnoreCase("blue")) plugin.addBlue(n);
    // ...
}
```

要点：

- **推送是事件驱动的，不是轮询。** 比分没变就不推——省限流配额，也少触发招牌重画。这正是 push 模式相比"定时查"的优势。
- **比分用 `null` TTL（沿用原 TTL，初始即永久）**，因为它只在有人得分时才变，没有"过期"概念；列车 ETA 则要 TTL 兜底，因为它本质上是时间敏感的。两种 TTL 策略对应两种数据语义。
- **改内存值和推送绑在一起**（`addRed` 里改完 `redScore` 就 `pushScores()`），保证招牌永远反映最新状态。

招牌上这样引用：

```
红 ${var:demo_score/red} : ${var:demo_score/blue} 蓝
MVP: ${var:demo_score/mvp}
```

## 怎么选

| 你的数据 | 用哪种 |
|---|---|
| 本身随时间变（倒计时、时钟、进度条） | 定时推送（demo-train） |
| 只在事件发生时变（比分、状态、库存） | 事件推送（demo-score） |

两者也能混用：比分用事件推，同时开个低频定时任务推"在线人数"之类的周期性数据。
