package moe.hikari.canvas.script.engine;

import moe.hikari.canvas.HikariCanvasConfig;
import moe.hikari.canvas.render.AnimationTicker;
import moe.hikari.canvas.script.Action;
import moe.hikari.canvas.state.StrictNumber;
import moe.hikari.canvas.storage.AuditLog;
import moe.hikari.canvas.storage.WallRepo;
import moe.hikari.canvas.variable.VariableException;
import moe.hikari.canvas.variable.VariableInterpolator;
import moe.hikari.canvas.variable.VariableStore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 脚本动作执行器（T4；{@code docs/scripting.md §2.3 / §3}）。8 动作落地：
 *
 * <ul>
 *   <li>{@code setVariable / incrementVariable / log} → VariableStore / logger（async 安全；
 *       value / message 过 {@code ${var:X}} 插值——复用 {@link VariableInterpolator}，
 *       与 Compositor 渲染期同一实现）</li>
 *   <li>{@code setElementProperty} → {@link ElementPropertyApplier} 双路径</li>
 *   <li>{@code playTimeline} → {@link TickerControl}（AnimationTicker 线程安全入口直调）</li>
 *   <li>{@code playSound} → 同步解析（wall 坐标 / scope / soundId）后主线程 hop
 *       {@code Bukkit.getScheduler().runTask}；plugin == null（测试路径）直跑</li>
 *   <li>{@code runCommand} → 0.7.0-P3 A1：{@link CommandTemplateEngine} 渲染（K13 转义 /
 *       校验全在纯函数侧）→ 主线程 hop {@code Bukkit.dispatchCommand(console, cmd)} +
 *       audit {@code SCRIPT_COMMAND_EXECUTED}；模板未配置 → blocked step，参数校验失败
 *       → error step</li>
 *   <li>{@code log} → {@code logger.info}；<b>不进 audit</b>（玩家级高频会刷库——与
 *       scripting.md §2.3 的偏差已在计划记账，P2 收口改契约）</li>
 *   <li>{@code wait / if} 由 {@link ScriptRunner} 处理；进到这里是防御 → error step</li>
 * </ul>
 *
 * <p><b>三层异常隔离</b>（照 Provider daemon 范式）：单动作 throw → error step +
 * WARNING log，链不断——Runner 收到 error step 继续下一动作。</p>
 *
 * <p>依赖全部可 null（测试容忍）：null 的依赖被动作触到 → error step 不抛。</p>
 */
public final class ActionExecutor implements ActionSink {

    /** near scope 半径平方（墙周 16 格；scripting.md §2.3）。 */
    private static final double NEAR_RANGE_SQ = 16.0 * 16.0;

    private final @Nullable VariableStore store;
    private final @Nullable VariableInterpolator interpolator;
    /** increment 读当前值的 fallback 链（与 ConditionEvaluator 同款）。 */
    private final @Nullable Function<String, String> storeLookup;
    private final @Nullable TickerControl ticker;
    private final @Nullable ElementPropertyApplier applier;
    private final @Nullable WallRepo wallRepo;
    private final @Nullable Plugin plugin;
    /**
     * 0.7.0-P3 A1：命令模板表 supplier（惰性读 volatile config 引用 → reload 热更免接线）。
     * null / 返回空表 → runCommand 恒 blocked（模板未配置）。
     */
    private final @Nullable Supplier<Map<String, HikariCanvasConfig.CommandTemplate>> templates;
    /**
     * 在线玩家名 supplier（online-player 参数校验）。生产 lambda 读
     * {@code Bukkit.getOnlinePlayers()}（CraftBukkit playerView 是 CopyOnWriteArrayList
     * 视图，异步迭代安全——与 SystemVariableProvider server.online 同口径）；
     * null（测试）→ 视作无人在线。
     */
    private final @Nullable Supplier<Collection<String>> onlineNames;
    /** SCRIPT_COMMAND_EXECUTED audit（fire-and-forget；可 null）。 */
    private final @Nullable AuditLog audit;
    private final Logger log;
    /**
     * 0.7.1：setRandomVariable 的随机源。生产默认 {@code ThreadLocalRandom.current()}
     * （runner 单线程，无竞争）；单测经 {@link #setRngForTest} 注 seeded {@code Random} 确定性。
     */
    private volatile java.util.random.RandomGenerator rng =
            java.util.concurrent.ThreadLocalRandom.current();

    /** 兼容旧 6 参形态（既有单测沿用）：无命令模板 / 无 audit → runCommand 恒 blocked。 */
    public ActionExecutor(@Nullable VariableStore store,
                          @Nullable TickerControl ticker,
                          @Nullable ElementPropertyApplier applier,
                          @Nullable WallRepo wallRepo,
                          @Nullable Plugin plugin,
                          Logger log) {
        this(store, ticker, applier, wallRepo, plugin, null, null, null, log);
    }

    public ActionExecutor(@Nullable VariableStore store,
                          @Nullable TickerControl ticker,
                          @Nullable ElementPropertyApplier applier,
                          @Nullable WallRepo wallRepo,
                          @Nullable Plugin plugin,
                          @Nullable Supplier<Map<String, HikariCanvasConfig.CommandTemplate>> templates,
                          @Nullable Supplier<Collection<String>> onlineNames,
                          @Nullable AuditLog audit,
                          Logger log) {
        this.store = store;
        this.interpolator = store == null ? null : new VariableInterpolator(store);
        this.storeLookup = store == null ? null : ConditionEvaluator.storeLookup(store);
        this.ticker = ticker;
        this.applier = applier;
        this.wallRepo = wallRepo;
        this.plugin = plugin;
        this.templates = templates;
        this.onlineNames = onlineNames;
        this.audit = audit;
        this.log = log;
    }

    /** 0.7.1 测试 seam：注入确定性随机源（生产不调用）。 */
    void setRngForTest(java.util.random.RandomGenerator r) {
        this.rng = r;
    }

    @Override
    public TraceStep execute(String wallId, String blockId, Action action) {
        try {
            return switch (action) {
                case Action.SetVariable a -> doSetVariable(wallId, blockId, a);
                case Action.IncrementVariable a -> doIncrement(wallId, blockId, a);
                case Action.SetElementProperty a -> doSetElementProperty(wallId, blockId, a);
                case Action.PlayTimeline a -> doPlayTimeline(wallId, blockId, a);
                case Action.PlaySound a -> doPlaySound(wallId, blockId, a);
                case Action.RunCommand a -> doRunCommand(wallId, blockId, a);
                case Action.Log a -> doLog(wallId, blockId, a);
                // 0.7.1：6 个新 Action 子类
                case Action.SetElementProperties a -> doSetElementProperties(wallId, blockId, a);
                case Action.NudgeElement a -> doNudge(wallId, blockId, a);
                case Action.SendMessage a -> doSendMessage(wallId, blockId, a);
                case Action.SetRandomVariable a -> doSetRandom(wallId, blockId, a);
                case Action.ScaleVariable a -> doScale(wallId, blockId, a);
                // playTimelineAwait：Runner 调本 sink 执行 play（副作用），再据 durationMs 决定挂起。
                // 即本方法做 play、Runner 做"等播完"——故这里真正执行（非防御 error）。
                case Action.PlayTimelineAwait a -> doPlayTimelineAwait(wallId, blockId, a);
                // wait / if / repeat 由 Runner 处理；进到这里是 Runner 实现 bug → 防御 error
                case Action.Wait a -> TraceStep.error(blockId, "wait 应由 ScriptRunner 处理");
                case Action.If a -> TraceStep.error(blockId, "if 应由 ScriptRunner 处理");
                case Action.Repeat a -> TraceStep.error(blockId, "repeat 应由 ScriptRunner 处理");
            };
        } catch (RuntimeException e) {
            // 三层隔离兜底：单动作失败不断链
            log.log(Level.WARNING, "[脚本] 动作执行失败（链继续）: wall=" + wallId
                    + " block=" + blockId + " type=" + action.wireType()
                    + " err=" + e.getMessage(), e);
            return TraceStep.error(blockId, action.wireType() + ": " + e.getMessage());
        }
    }

    // ---------- 变量族（async 安全） ----------

    private TraceStep doSetVariable(String wallId, String blockId, Action.SetVariable a) {
        if (store == null || interpolator == null) {
            return TraceStep.error(blockId, "VariableStore 未装配");
        }
        // value 过 ${var:X} 插值（与 Compositor 渲染期同一 VariableInterpolator 实现）
        String resolved = interpolator.interpolate(a.value(), wallId).text();
        String fullName = VariableInterpolator.resolveFullName(a.fullName(), wallId);
        try {
            // K1：CHAIN_DEPTH 已由 Runner 置位——fireChange 同步发生在本线程，
            // Router（批次 3）listener 读 ThreadLocal 得链深
            store.setValue(fullName, resolved, null);
        } catch (VariableException e) {
            return TraceStep.error(blockId, "setVariable " + fullName + ": " + e.getMessage());
        }
        return TraceStep.ok(blockId, "action", "set " + fullName);
    }

    private TraceStep doIncrement(String wallId, String blockId, Action.IncrementVariable a) {
        if (store == null || storeLookup == null) {
            return TraceStep.error(blockId, "VariableStore 未装配");
        }
        String fullName = VariableInterpolator.resolveFullName(a.fullName(), wallId);
        // 读 cached 当前值（与 ConditionEvaluator.storeLookup 同链：fresh → default → null）；
        // 非数值 / null 按 0 起算（StrictNumber 文法，§2.3）
        double base = StrictNumber.parse(storeLookup.apply(fullName));
        String formatted = formatNumber(base + a.delta());
        try {
            store.setValue(fullName, formatted, null);
        } catch (VariableException e) {
            return TraceStep.error(blockId, "incrementVariable " + fullName
                    + ": " + e.getMessage());
        }
        return TraceStep.ok(blockId, "action", fullName + " = " + formatted);
    }

    /** 整数值输出无小数点（"42" 而非 "42.0"）；否则 {@code String.valueOf}。 */
    static String formatNumber(double v) {
        if (Double.isFinite(v) && v == Math.rint(v)
                && Math.abs(v) <= 9.007199254740992E15) { // 2^53：double 整数精确域
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    // ---------- 元素属性 ----------

    private TraceStep doSetElementProperty(String wallId, String blockId,
                                           Action.SetElementProperty a) {
        if (applier == null) {
            return TraceStep.error(blockId, "ElementPropertyApplier 未装配");
        }
        return applier.apply(wallId, blockId, a.elementId(), a.property(), a.value());
    }

    // ---------- 时间轴 ----------

    private TraceStep doPlayTimeline(String wallId, String blockId, Action.PlayTimeline a) {
        if (ticker == null) {
            return TraceStep.error(blockId, "AnimationTicker 未装配");
        }
        switch (String.valueOf(a.op())) {
            case "play" -> {
                AnimationTicker.Result r = ticker.play(wallId, a.timelineId());
                if (r != AnimationTicker.Result.OK) {
                    return TraceStep.error(blockId, "play 失败: " + r);
                }
                return TraceStep.ok(blockId, "action", "play " + a.timelineId());
            }
            case "pause" -> {
                ticker.pause(wallId); // 幂等，无返回
                return TraceStep.ok(blockId, "action", "pause");
            }
            case "seek" -> {
                if (a.seekMs() == null) {
                    return TraceStep.error(blockId, "seek 缺 seekMs");
                }
                AnimationTicker.Result r = ticker.seek(wallId, a.timelineId(), a.seekMs());
                if (r != AnimationTicker.Result.OK) {
                    return TraceStep.error(blockId, "seek 失败: " + r);
                }
                return TraceStep.ok(blockId, "action", "seek " + a.seekMs() + "ms");
            }
            default -> {
                return TraceStep.error(blockId, "未知 timeline op: " + a.op());
            }
        }
    }

    // ---------- 声音（主线程 hop） ----------

    private TraceStep doPlaySound(String wallId, String blockId, Action.PlaySound a) {
        boolean near = "near".equals(a.scope());
        if (!near && !"all".equals(a.scope())) {
            return TraceStep.error(blockId, "未知 scope: " + a.scope());
        }
        if (wallRepo == null) {
            return TraceStep.error(blockId, "playSound 不可用（WallRepo 未装配）");
        }
        WallRepo.Wall wall = wallRepo.loadById(wallId).orElse(null);
        if (wall == null) {
            return TraceStep.error(blockId, "wall 不存在: " + wallId);
        }
        if (near && wall.key() == null) {
            // near 需要墙坐标（WallKey：world + origin）；DB 行恒有 key，这里是防御
            return TraceStep.error(blockId, "wall 无坐标（key 缺失），near 不可用");
        }
        // soundId 同步解析（Registry 是只读表，任意线程读安全）：查无 → error step 不抛
        Sound sound;
        try {
            NamespacedKey key = NamespacedKey.fromString(
                    a.soundId().toLowerCase(java.util.Locale.ROOT));
            sound = key == null ? null : org.bukkit.Registry.SOUNDS.get(key);
        } catch (RuntimeException | NoClassDefFoundError | ExceptionInInitializerError e) {
            // 无 Bukkit server 环境（纯单测路径）Registry 静态初始化会失败——归为解析失败
            return TraceStep.error(blockId, "声音解析失败: " + a.soundId()
                    + " (" + e.getClass().getSimpleName() + ")");
        }
        if (sound == null) {
            return TraceStep.error(blockId, "声音不存在: " + a.soundId());
        }
        float volume = (float) a.volume();
        float pitch = (float) a.pitch();
        WallRepo.Wall w = wall;
        Runnable work = () -> {
            try {
                if (near) {
                    World world = Bukkit.getWorld(w.key().world());
                    if (world == null) {
                        log.warning("[脚本] playSound 跳过：世界未加载 " + w.key().world());
                        return;
                    }
                    Location origin = new Location(world,
                            w.key().originX(), w.key().originY(), w.key().originZ());
                    for (Player p : world.getPlayers()) {
                        if (p.getLocation().distanceSquared(origin) <= NEAR_RANGE_SQ) {
                            p.playSound(origin, sound, volume, pitch);
                        }
                    }
                } else {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        p.playSound(p.getLocation(), sound, volume, pitch);
                    }
                }
            } catch (Throwable t) {
                // 主线程任务内异常只 log（trace step 已发出，无法回填）
                log.log(Level.WARNING, "[脚本] playSound 执行失败: " + t.getMessage(), t);
            }
        };
        if (plugin == null) {
            work.run(); // 测试路径直跑
        } else {
            Bukkit.getScheduler().runTask(plugin, work); // 主线程 hop（线程纪律 §3.2）
        }
        return TraceStep.ok(blockId, "action",
                "sound " + a.soundId() + " scope=" + a.scope());
    }

    // ---------- 命令（0.7.0-P3 A1：命令模板系统真实化；docs/scripting.md §5.2） ----------

    private TraceStep doRunCommand(String wallId, String blockId, Action.RunCommand a) {
        Map<String, HikariCanvasConfig.CommandTemplate> tpls =
                templates == null ? Map.of() : templates.get();
        Collection<String> names = onlineNames == null ? java.util.List.of() : onlineNames.get();
        CommandTemplateEngine.Result r = CommandTemplateEngine.render(
                a.templateId(), a.params(), tpls, names);
        switch (r) {
            case CommandTemplateEngine.Result.Blocked b -> {
                if (log.isLoggable(Level.FINE)) {
                    log.fine("[脚本] runCommand blocked: wall=" + wallId
                            + " template=" + a.templateId() + " — " + b.reason());
                }
                return TraceStep.blocked(blockId, b.reason());
            }
            case CommandTemplateEngine.Result.Error err -> {
                return TraceStep.error(blockId, "runCommand: " + err.reason());
            }
            case CommandTemplateEngine.Result.Ok ok -> {
                // audit 记"提交执行"（templateId + 替换后全文 + 来源规则；scripting.md §5.2）。
                // ruleKey 从 ScriptRunner ThreadLocal 读（runner 线程恒有；直调路径 null 省略）
                recordCommandAudit(wallId, blockId, a.templateId(), ok.command());
                Runnable work = () -> {
                    try {
                        // 执行身份 = console sender（模板是服主写的，服主授权；§5.2）
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), ok.command());
                    } catch (Throwable t) {
                        // 主线程任务内异常只 log（trace step 已发出，无法回填——同 playSound）
                        log.log(Level.WARNING, "[脚本] runCommand 执行失败: template="
                                + a.templateId() + " err=" + t.getMessage(), t);
                    }
                };
                if (plugin == null) {
                    work.run(); // 测试路径直跑
                } else {
                    Bukkit.getScheduler().runTask(plugin, work); // 主线程 hop（线程纪律 §3.2）
                }
                return TraceStep.ok(blockId, "action", "command " + a.templateId());
            }
        }
    }

    /** SCRIPT_COMMAND_EXECUTED audit（fire-and-forget；audit null 时跳过）。 */
    private void recordCommandAudit(String wallId, String blockId,
                                    String templateId, String command) {
        if (audit == null) return;
        try {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("wall_id", wallId);
            details.put("template_id", templateId);
            details.put("command", command);
            String ruleKey = ScriptRunner.currentRuleKey();
            if (ruleKey != null) details.put("rule_key", ruleKey);
            details.put("block_id", blockId);
            audit.record("SCRIPT_COMMAND_EXECUTED", null, null, null, null, details);
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "[脚本] SCRIPT_COMMAND_EXECUTED audit 失败: "
                    + e.getMessage(), e);
        }
    }

    // ---------- 日志 ----------

    private TraceStep doLog(String wallId, String blockId, Action.Log a) {
        String msg = interpolator == null ? a.message()
                : interpolator.interpolate(a.message(), wallId).text();
        // 不进 audit：log 是玩家级高频动作，进 audit 会刷库（与 scripting.md §2.3 偏差，
        // 收口改契约该行——计划已记账）
        log.info("[脚本 " + wallId + "] " + msg);
        return TraceStep.ok(blockId, "action", "log");
    }

    // ---------- 0.7.1：6 个新动作 ----------

    /** 批量设元素属性（友好积木）→ {@link ElementPropertyApplier#applyMany}（kind 仅前端皮肤，忽略）。 */
    private TraceStep doSetElementProperties(String wallId, String blockId,
                                             Action.SetElementProperties a) {
        if (applier == null) {
            return TraceStep.error(blockId, "ElementPropertyApplier 未装配");
        }
        return applier.applyMany(wallId, blockId, a.elementId(), a.patch());
    }

    /** 相对移动 → {@link ElementPropertyApplier#applyNudge}（运行时读当前 x/y + delta）。 */
    private TraceStep doNudge(String wallId, String blockId, Action.NudgeElement a) {
        if (applier == null) {
            return TraceStep.error(blockId, "ElementPropertyApplier 未装配");
        }
        return applier.applyNudge(wallId, blockId, a.elementId(), a.dx(), a.dy());
    }

    /**
     * 给触发玩家发消息。触发玩家名 = {@link ScriptRunner#currentTriggerDetail}（仅 player*
     * 触发器有值）。无触发玩家 / 离线 → ok step skip（非错误）。text 过 {@code ${var:X}} 插值。
     * 主线程 hop 发（Adventure {@code Component} / {@code Title}，禁 NMS）。
     */
    private TraceStep doSendMessage(String wallId, String blockId, Action.SendMessage a) {
        String channel = a.channel();
        if (!"chat".equals(channel) && !"actionbar".equals(channel) && !"title".equals(channel)) {
            return TraceStep.error(blockId, "未知 channel: " + channel);
        }
        String who = ScriptRunner.currentTriggerDetail();
        if (who == null || who.isBlank()) {
            return TraceStep.ok(blockId, "action", "无触发玩家，跳过");
        }
        String msg = interpolator == null ? a.text()
                : interpolator.interpolate(a.text(), wallId).text();
        Runnable work = () -> {
            try {
                Player p = Bukkit.getPlayerExact(who);
                if (p == null) return; // 离线
                switch (channel) {
                    case "actionbar" -> p.sendActionBar(
                            net.kyori.adventure.text.Component.text(msg));
                    case "title" -> p.showTitle(net.kyori.adventure.title.Title.title(
                            net.kyori.adventure.text.Component.text(msg),
                            net.kyori.adventure.text.Component.empty()));
                    default -> p.sendMessage(net.kyori.adventure.text.Component.text(msg));
                }
            } catch (Throwable t) {
                log.log(Level.WARNING, "[脚本] sendMessage 失败: " + t.getMessage(), t);
            }
        };
        if (plugin == null) {
            work.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, work);
        }
        return TraceStep.ok(blockId, "action", "msg→" + who + " (" + channel + ")");
    }

    /** 随机数 [min,max] 闭区间。min&gt;max → error。两端皆整 → 整数随机（含 max，roll dice 体验）。 */
    private TraceStep doSetRandom(String wallId, String blockId, Action.SetRandomVariable a) {
        if (store == null) {
            return TraceStep.error(blockId, "VariableStore 未装配");
        }
        if (!Double.isFinite(a.min()) || !Double.isFinite(a.max()) || a.min() > a.max()) {
            return TraceStep.error(blockId, "随机区间非法: " + a.min() + ".." + a.max());
        }
        double v = a.min() + rng.nextDouble() * (a.max() - a.min());
        // 两端皆整 + 跨度可装进 int → 取整数随机（含 max）；否则保留上面的连续采样（防 int 溢出）
        if (a.min() == Math.rint(a.min()) && a.max() == Math.rint(a.max())
                && (a.max() - a.min()) <= (double) (Integer.MAX_VALUE - 1)) {
            v = a.min() + rng.nextInt((int) (a.max() - a.min()) + 1);
        }
        String fullName = VariableInterpolator.resolveFullName(a.fullName(), wallId);
        String formatted = formatNumber(v);
        try {
            store.setValue(fullName, formatted, null);
        } catch (VariableException e) {
            return TraceStep.error(blockId, "setRandom " + fullName + ": " + e.getMessage());
        }
        return TraceStep.ok(blockId, "action", fullName + " = " + formatted);
    }

    /** 变量乘 / 除。读当前值（StrictNumber，非数值作 0）→ × / ÷ → 写回。divide by 0 → error。 */
    private TraceStep doScale(String wallId, String blockId, Action.ScaleVariable a) {
        if (store == null || storeLookup == null) {
            return TraceStep.error(blockId, "VariableStore 未装配");
        }
        boolean mul = "multiply".equals(a.op());
        if (!mul && !"divide".equals(a.op())) {
            return TraceStep.error(blockId, "未知 op: " + a.op());
        }
        if (!Double.isFinite(a.factor())) {
            return TraceStep.error(blockId, "factor 必须有限");
        }
        if (!mul && a.factor() == 0.0) {
            return TraceStep.error(blockId, "除数不能为 0");
        }
        String fullName = VariableInterpolator.resolveFullName(a.fullName(), wallId);
        double base = StrictNumber.parse(storeLookup.apply(fullName));
        double result = mul ? base * a.factor() : base / a.factor();
        String formatted = formatNumber(result);
        try {
            store.setValue(fullName, formatted, null);
        } catch (VariableException e) {
            return TraceStep.error(blockId, "scaleVariable " + fullName + ": " + e.getMessage());
        }
        return TraceStep.ok(blockId, "action", fullName + " = " + formatted);
    }

    /** 播时间轴：触发 play（挂起 durationMs 由 Runner 经 {@link #timelineDurationMs} 处理）。 */
    private TraceStep doPlayTimelineAwait(String wallId, String blockId, Action.PlayTimelineAwait a) {
        if (ticker == null) {
            return TraceStep.error(blockId, "AnimationTicker 未装配");
        }
        if (a.timelineId() == null || a.timelineId().isBlank()) {
            return TraceStep.error(blockId, "缺 timelineId");
        }
        AnimationTicker.Result r = ticker.play(wallId, a.timelineId());
        if (r != AnimationTicker.Result.OK) {
            return TraceStep.error(blockId, "play 失败: " + r);
        }
        return TraceStep.ok(blockId, "action", "playAwait " + a.timelineId());
    }

    /**
     * 0.7.1 {@link ActionSink#timelineDurationMs}：从 wallRepo 读 timeline 一轮时长（ms）。
     * 查无 / wallRepo 缺 → 0（Runner 据此不挂起）。封顶 10 分钟防异常 duration 卡死续接。
     */
    @Override
    public long timelineDurationMs(String wallId, String timelineId) {
        if (wallRepo == null || timelineId == null) {
            return 0L;
        }
        WallRepo.Wall wall = wallRepo.loadById(wallId).orElse(null);
        if (wall == null || wall.state() == null) {
            return 0L;
        }
        for (var tl : wall.state().timelines()) {
            if (tl.id().equals(timelineId)) {
                return Math.max(0L, Math.min(tl.durationMs(), 600_000L));
            }
        }
        return 0L;
    }
}
