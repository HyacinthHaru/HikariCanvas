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
 * 脚本动作执行器（{@code docs/scripting.md §2.3 / §3}）。8 动作落地：
 *
 * <ul>
 *   <li>{@code setVariable / incrementVariable / log} → VariableStore / logger（async 安全；
 *       value / message 过 {@code ${var:X}} 插值——复用 {@link VariableInterpolator}，
 *       与 Compositor 渲染期同一实现）</li>
 *   <li>{@code setElementProperty} → {@link ElementPropertyApplier} 双路径</li>
 *   <li>{@code playTimeline} → {@link TickerControl}（AnimationTicker 线程安全入口直调）</li>
 *   <li>{@code playSound} → 同步解析（wall 坐标 / scope / soundId）后主线程 hop
 *       {@code Bukkit.getScheduler().runTask}；plugin == null（测试路径）直跑</li>
 *   <li>{@code runCommand} → {@link CommandTemplateEngine} 渲染（转义 /
 *       校验全在纯函数侧）→ 主线程 hop {@code Bukkit.dispatchCommand(console, cmd)} +
 *       audit {@code SCRIPT_COMMAND_EXECUTED}；模板未配置 → blocked step，参数校验失败
 *       → error step</li>
 *   <li>{@code log} → {@code logger.info}；<b>不进 audit</b>（玩家级高频会刷库）</li>
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
     * 命令模板表 supplier（惰性读 volatile config 引用 → reload 热更免接线）。
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
     * setRandomVariable 的随机源。生产默认 {@code ThreadLocalRandom.current()}
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

    /** 测试 seam：注入确定性随机源（生产不调用）。 */
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
                case Action.SetElementProperties a -> doSetElementProperties(wallId, blockId, a);
                case Action.NudgeElement a -> doNudge(wallId, blockId, a);
                case Action.SendMessage a -> doSendMessage(wallId, blockId, a);
                case Action.SetRandomVariable a -> doSetRandom(wallId, blockId, a);
                case Action.ScaleVariable a -> doScale(wallId, blockId, a);
                // playTimelineAwait：Runner 调本 sink 执行 play（副作用），再据 durationMs 决定挂起。
                // 即本方法做 play、Runner 做"等播完"——故这里真正执行（非防御 error）。
                case Action.PlayTimelineAwait a -> doPlayTimelineAwait(wallId, blockId, a);
                // 粒子真正执行（主线程 hop + 墙坐标，同 playSound 范式）
                case Action.PlayParticle a -> doPlayParticle(wallId, blockId, a);
                // 变量积木 + 元素积木（克隆/删除）真实现
                case Action.CopyVariable a -> doCopyVariable(wallId, blockId, a);
                case Action.AppendVariable a -> doAppendVariable(wallId, blockId, a);
                case Action.CloneElement a -> doCloneElement(wallId, blockId, a);
                case Action.DeleteElement a -> doDeleteElement(wallId, blockId, a);
                // 置顶置底 / 取整 / 标题弹窗（真实现）
                case Action.SetElementLayer a -> doSetElementLayer(wallId, blockId, a);
                case Action.RoundVariable a -> doRoundVariable(wallId, blockId, a);
                case Action.ShowTitle a -> doShowTitle(wallId, blockId, a);
                // wait / if / repeat / stopScript / waitUntil 由 Runner 处理；进到这里是 Runner
                // 实现 bug → 防御 error（stopScript/waitUntil 真实现在 Runner）
                case Action.Wait a -> TraceStep.error(blockId, "wait must be handled by ScriptRunner");
                case Action.If a -> TraceStep.error(blockId, "if must be handled by ScriptRunner");
                case Action.Repeat a -> TraceStep.error(blockId, "repeat must be handled by ScriptRunner");
                case Action.StopScript a -> TraceStep.error(blockId, "stopScript must be handled by ScriptRunner");
                case Action.WaitUntil a -> TraceStep.error(blockId, "waitUntil must be handled by ScriptRunner");
                // repeatUntil 真实现在 ScriptRunner（动态循环 + RunState 轮数）；
                // 进到这里是 Runner 实现 bug → 防御 error。
                case Action.RepeatUntil a -> TraceStep.error(blockId, "repeatUntil is handled by ScriptRunner (placeholder)");
                // TweenBlock 由 ScriptRunner / TweenScheduler 处理；
                // 进到这里是 Runner 实现 bug → 防御 error。
                case Action.TweenBlock a -> TraceStep.error(blockId, "tweenBlock is handled by ScriptRunner (replaced by TweenScheduler in P2)");
                // RandomBranch 由 ScriptRunner 处理（控制流）；进到这里是 Runner 实现 bug。
                case Action.RandomBranch a -> TraceStep.error(blockId, "randomBranch must be handled by ScriptRunner");
            };
        } catch (RuntimeException e) {
            // 三层隔离兜底：单动作失败不断链
            log.log(Level.WARNING, "[script] action execution failed (chain continues): wall=" + wallId
                    + " block=" + blockId + " type=" + action.wireType()
                    + " err=" + e.getMessage(), e);
            return TraceStep.error(blockId, action.wireType() + ": " + e.getMessage());
        }
    }

    // ---------- 变量族（async 安全） ----------

    private TraceStep doSetVariable(String wallId, String blockId, Action.SetVariable a) {
        if (store == null || interpolator == null) {
            return TraceStep.error(blockId, "VariableStore not wired");
        }
        // value 过 ${var:X} 插值（与 Compositor 渲染期同一 VariableInterpolator 实现）
        String resolved = interpolator.interpolate(a.value(), wallId).text();
        String fullName = VariableInterpolator.resolveFullName(a.fullName(), wallId);
        try {
            // CHAIN_DEPTH 已由 Runner 置位——fireChange 同步发生在本线程，
            // Router listener 读 ThreadLocal 得链深
            store.setValue(fullName, resolved, null);
        } catch (VariableException e) {
            return TraceStep.error(blockId, "setVariable " + fullName + ": " + e.getMessage());
        }
        return TraceStep.ok(blockId, "action", "set " + fullName);
    }

    private TraceStep doIncrement(String wallId, String blockId, Action.IncrementVariable a) {
        if (store == null || storeLookup == null) {
            return TraceStep.error(blockId, "VariableStore not wired");
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

    /**
     * 把 source 变量的当前值复制到 target 变量（照 {@link #doSetVariable} 范式：
     * store.get 取值 + store.setValue 写入；async 安全无主线程 hop）。源变量缺失 → error；
     * source.currentValue 为 null 时退 defaultValue，再退空串。
     */
    private TraceStep doCopyVariable(String wallId, String blockId, Action.CopyVariable a) {
        if (store == null) {
            return TraceStep.error(blockId, "VariableStore not wired");
        }
        String src = VariableInterpolator.resolveFullName(a.source(), wallId);
        String dst = VariableInterpolator.resolveFullName(a.target(), wallId);
        var srcVar = store.get(src).orElse(null);
        if (srcVar == null) {
            return TraceStep.error(blockId, "source variable not found: " + src);
        }
        String value = srcVar.currentValue() != null ? srcVar.currentValue()
                : (srcVar.defaultValue() != null ? srcVar.defaultValue() : "");
        try {
            store.setValue(dst, value, null);
        } catch (VariableException e) {
            return TraceStep.error(blockId, "copyVariable " + dst + ": " + e.getMessage());
        }
        return TraceStep.ok(blockId, "action", dst + " ← " + src);
    }

    /**
     * 把 text（过 {@code ${var:X}} 插值）追加到 fullName 变量末尾（读当前值 +
     * 拼接 + 写回；currentValue 缺失从空串起拼）。async 安全。
     */
    private TraceStep doAppendVariable(String wallId, String blockId, Action.AppendVariable a) {
        if (store == null || interpolator == null) {
            return TraceStep.error(blockId, "VariableStore not wired");
        }
        String dst = VariableInterpolator.resolveFullName(a.fullName(), wallId);
        String add = interpolator.interpolate(a.text(), wallId).text();
        String cur = store.get(dst)
                .map(v -> v.currentValue() != null ? v.currentValue() : "")
                .orElse("");
        try {
            store.setValue(dst, cur + add, null);
        } catch (VariableException e) {
            return TraceStep.error(blockId, "appendVariable " + dst + ": " + e.getMessage());
        }
        return TraceStep.ok(blockId, "action", dst + " += ...");
    }

    /** 整数值输出无小数点（"42" 而非 "42.0"）；否则 {@code String.valueOf}。
     * 非有限数值（NaN / Infinity / -Infinity）一律返 "0"，避免污染变量库和墙上渲染。 */
    static String formatNumber(double v) {
        if (!Double.isFinite(v)) return "0";
        if (v == Math.rint(v) && Math.abs(v) <= 9.007199254740992E15) { // 2^53：double 整数精确域
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    // ---------- 元素属性 ----------

    private TraceStep doSetElementProperty(String wallId, String blockId,
                                           Action.SetElementProperty a) {
        if (applier == null) {
            return TraceStep.error(blockId, "ElementPropertyApplier not wired");
        }
        return applier.apply(wallId, blockId, a.elementId(), a.property(), a.value());
    }

    // ---------- 时间轴 ----------

    private TraceStep doPlayTimeline(String wallId, String blockId, Action.PlayTimeline a) {
        if (ticker == null) {
            return TraceStep.error(blockId, "AnimationTicker not wired");
        }
        switch (String.valueOf(a.op())) {
            case "play" -> {
                AnimationTicker.Result r = ticker.play(wallId, a.timelineId());
                if (r != AnimationTicker.Result.OK) {
                    return TraceStep.error(blockId, "play failed: " + r);
                }
                return TraceStep.ok(blockId, "action", "play " + a.timelineId());
            }
            case "pause" -> {
                ticker.pause(wallId); // 幂等，无返回
                return TraceStep.ok(blockId, "action", "pause");
            }
            case "seek" -> {
                if (a.seekMs() == null) {
                    return TraceStep.error(blockId, "seek missing seekMs");
                }
                AnimationTicker.Result r = ticker.seek(wallId, a.timelineId(), a.seekMs());
                if (r != AnimationTicker.Result.OK) {
                    return TraceStep.error(blockId, "seek failed: " + r);
                }
                return TraceStep.ok(blockId, "action", "seek " + a.seekMs() + "ms");
            }
            default -> {
                return TraceStep.error(blockId, "unknown timeline op: " + a.op());
            }
        }
    }

    // ---------- 声音（主线程 hop） ----------

    private TraceStep doPlaySound(String wallId, String blockId, Action.PlaySound a) {
        boolean near = "near".equals(a.scope());
        if (!near && !"all".equals(a.scope())) {
            return TraceStep.error(blockId, "unknown scope: " + a.scope());
        }
        if (wallRepo == null) {
            return TraceStep.error(blockId, "playSound unavailable (WallRepo not wired)");
        }
        WallRepo.Wall wall = wallRepo.loadById(wallId).orElse(null);
        if (wall == null) {
            return TraceStep.error(blockId, "wall not found: " + wallId);
        }
        if (near && wall.key() == null) {
            // near 需要墙坐标（WallKey：world + origin）；DB 行恒有 key，这里是防御
            return TraceStep.error(blockId, "wall has no coordinates (key missing); near unavailable");
        }
        // soundId 同步解析（Registry 是只读表，任意线程读安全）：查无 → error step 不抛
        Sound sound;
        try {
            NamespacedKey key = NamespacedKey.fromString(
                    a.soundId().toLowerCase(java.util.Locale.ROOT));
            sound = key == null ? null : org.bukkit.Registry.SOUNDS.get(key);
        } catch (RuntimeException | NoClassDefFoundError | ExceptionInInitializerError e) {
            // 无 Bukkit server 环境（纯单测路径）Registry 静态初始化会失败——归为解析失败
            return TraceStep.error(blockId, "sound parse failed: " + a.soundId()
                    + " (" + e.getClass().getSimpleName() + ")");
        }
        if (sound == null) {
            return TraceStep.error(blockId, "sound not found: " + a.soundId());
        }
        float volume = (float) a.volume();
        float pitch = (float) a.pitch();
        WallRepo.Wall w = wall;
        Runnable work = () -> {
            try {
                if (near) {
                    World world = Bukkit.getWorld(w.key().world());
                    if (world == null) {
                        log.warning("[script] playSound skipped: world not loaded " + w.key().world());
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
                log.log(Level.WARNING, "[script] playSound failed: " + t.getMessage(), t);
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

    // ---------- 粒子（主线程 hop + 墙坐标，照 doPlaySound 范式） ----------

    private TraceStep doPlayParticle(String wallId, String blockId, Action.PlayParticle a) {
        if (wallRepo == null) {
            return TraceStep.error(blockId, "playParticle unavailable (WallRepo not wired)");
        }
        WallRepo.Wall wall = wallRepo.loadById(wallId).orElse(null);
        if (wall == null || wall.key() == null) {
            return TraceStep.error(blockId, "wall not found: " + wallId);
        }
        // 解析 particle → Particle（Registry 只读表，任意线程读安全；同 playSound 的 Registry.SOUNDS 范式）
        org.bukkit.Particle particle;
        try {
            NamespacedKey key = NamespacedKey.fromString(
                    a.particle().toLowerCase(java.util.Locale.ROOT));
            particle = key == null ? null : org.bukkit.Registry.PARTICLE_TYPE.get(key);
        } catch (RuntimeException | NoClassDefFoundError | ExceptionInInitializerError e) {
            // 无 Bukkit server 环境（纯单测路径）Registry 静态初始化会失败——归为解析失败
            return TraceStep.error(blockId, "particle parse failed: " + a.particle()
                    + " (" + e.getClass().getSimpleName() + ")");
        }
        if (particle == null) {
            return TraceStep.error(blockId, "particle not found: " + a.particle());
        }
        WallRepo.Wall w = wall;
        org.bukkit.Particle p = particle;
        Runnable work = () -> {
            try {
                World world = Bukkit.getWorld(w.key().world());
                if (world == null) {
                    log.warning("[script] playParticle skipped: world not loaded " + w.key().world());
                    return;
                }
                Location origin = new Location(world,
                        w.key().originX() + a.offsetX(),
                        w.key().originY() + a.offsetY(),
                        w.key().originZ() + a.offsetZ());
                world.spawnParticle(p, origin, a.count());
            } catch (Throwable t) {
                // 主线程任务内异常只 log（trace step 已发出，无法回填——同 playSound）
                log.log(Level.WARNING, "[script] playParticle failed: " + t.getMessage(), t);
            }
        };
        if (plugin == null) {
            work.run(); // 测试路径直跑
        } else {
            Bukkit.getScheduler().runTask(plugin, work); // 主线程 hop（线程纪律 §3.2）
        }
        return TraceStep.ok(blockId, "action", "particle " + a.particle() + " x" + a.count());
    }

    // ---------- 命令（命令模板系统；docs/scripting.md §5.2） ----------

    private TraceStep doRunCommand(String wallId, String blockId, Action.RunCommand a) {
        Map<String, HikariCanvasConfig.CommandTemplate> tpls =
                templates == null ? Map.of() : templates.get();
        Collection<String> names = onlineNames == null ? java.util.List.of() : onlineNames.get();
        CommandTemplateEngine.Result r = CommandTemplateEngine.render(
                a.templateId(), a.params(), tpls, names);
        switch (r) {
            case CommandTemplateEngine.Result.Blocked b -> {
                if (log.isLoggable(Level.FINE)) {
                    log.fine("[script] runCommand blocked: wall=" + wallId
                            + " template=" + a.templateId() + " — " + b.reason());
                }
                return TraceStep.blocked(blockId, b.reason());
            }
            case CommandTemplateEngine.Result.Error err -> {
                return TraceStep.error(blockId, "runCommand: " + err.reason());
            }
            case CommandTemplateEngine.Result.Ok ok -> {
                // audit 记"提交执行"（templateId + 替换后全文 + 来源规则；scripting.md §5.2）。
                // ruleKey 从 ScriptRunner ThreadLocal 读（runner 线程恒有；直调路径 null 省略）。
                recordCommandAudit(wallId, blockId, a.templateId(), ok.command());
                Runnable work = () -> {
                    try {
                        // 执行身份 = console sender（模板是服主写的，服主授权；§5.2）
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), ok.command());
                    } catch (Throwable t) {
                        // 主线程任务内异常只 log（trace step 已发出，无法回填——同 playSound）
                        log.log(Level.WARNING, "[script] runCommand failed: template="
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
            log.log(Level.WARNING, "[script] SCRIPT_COMMAND_EXECUTED audit failed: "
                    + e.getMessage(), e);
        }
    }

    // ---------- 日志 ----------

    private TraceStep doLog(String wallId, String blockId, Action.Log a) {
        String msg = interpolator == null ? a.message()
                : interpolator.interpolate(a.message(), wallId).text();
        // 不进 audit：log 是玩家级高频动作，进 audit 会刷库
        log.info("[script " + wallId + "] " + msg);
        return TraceStep.ok(blockId, "action", "log");
    }

    // ---------- 友好积木动作 ----------

    /** 批量设元素属性（友好积木）→ {@link ElementPropertyApplier#applyMany}（kind 仅前端皮肤，忽略）。 */
    private TraceStep doSetElementProperties(String wallId, String blockId,
                                             Action.SetElementProperties a) {
        if (applier == null) {
            return TraceStep.error(blockId, "ElementPropertyApplier not wired");
        }
        return applier.applyMany(wallId, blockId, a.elementId(), a.patch());
    }

    /** 相对移动 → {@link ElementPropertyApplier#applyNudge}（运行时读当前 x/y + delta）。 */
    private TraceStep doNudge(String wallId, String blockId, Action.NudgeElement a) {
        if (applier == null) {
            return TraceStep.error(blockId, "ElementPropertyApplier not wired");
        }
        return applier.applyNudge(wallId, blockId, a.elementId(), a.dx(), a.dy());
    }

    /** 克隆元素 → {@link ElementPropertyApplier#applyClone}（双路径 + 元素数配额）。 */
    private TraceStep doCloneElement(String wallId, String blockId, Action.CloneElement a) {
        if (applier == null) {
            return TraceStep.error(blockId, "ElementPropertyApplier not wired");
        }
        return applier.applyClone(wallId, blockId, a.elementId(), a.offsetX(), a.offsetY());
    }

    /** 删除元素 → {@link ElementPropertyApplier#applyDelete}（双路径）。 */
    private TraceStep doDeleteElement(String wallId, String blockId, Action.DeleteElement a) {
        if (applier == null) {
            return TraceStep.error(blockId, "ElementPropertyApplier not wired");
        }
        return applier.applyDelete(wallId, blockId, a.elementId());
    }

    /**
     * 发消息。按 {@link Action.SendMessage#target()} 分流——
     * {@code "all"} → 全服广播（{@code Bukkit.getOnlinePlayers()} 逐个发，不依赖触发玩家）；
     * 否则（{@code "trigger"}）→ 触发玩家名 = {@link ScriptRunner#currentTriggerDetail}（仅
     * player* 触发器有值），无触发玩家 / 离线 → ok step skip（非错误）。text 过
     * {@code ${var:X}} 插值。主线程 hop 发（Adventure {@code Component} / {@code Title}，禁 NMS）。
     */
    private TraceStep doSendMessage(String wallId, String blockId, Action.SendMessage a) {
        String channel = a.channel();
        if (!"chat".equals(channel) && !"actionbar".equals(channel) && !"title".equals(channel)) {
            return TraceStep.error(blockId, "unknown channel: " + channel);
        }
        String msg = interpolator == null ? a.text()
                : interpolator.interpolate(a.text(), wallId).text();
        if ("all".equals(a.target())) {
            // 全服广播：不读触发玩家，主线程 hop 内遍历在线玩家逐个发
            Runnable work = () -> {
                try {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        sendTo(p, channel, msg);
                    }
                } catch (Throwable t) {
                    log.log(Level.WARNING, "[script] sendMessage broadcast failed: " + t.getMessage(), t);
                }
            };
            if (plugin == null) {
                work.run();
            } else {
                Bukkit.getScheduler().runTask(plugin, work);
            }
            return TraceStep.ok(blockId, "action", "msg -> broadcast (" + channel + ")");
        }
        // target=trigger（默认）：发给触发该脚本的玩家
        String who = ScriptRunner.currentTriggerDetail();
        if (who == null || who.isBlank()) {
            return TraceStep.ok(blockId, "action", "no trigger player; skipped");
        }
        Runnable work = () -> {
            try {
                Player p = Bukkit.getPlayerExact(who);
                if (p == null) return; // 离线
                sendTo(p, channel, msg);
            } catch (Throwable t) {
                log.log(Level.WARNING, "[script] sendMessage failed: " + t.getMessage(), t);
            }
        };
        if (plugin == null) {
            work.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, work);
        }
        return TraceStep.ok(blockId, "action", "msg -> " + who + " (" + channel + ")");
    }

    /** 按 channel 把已插值的 msg 发给单个玩家（chat/actionbar/title）。须在主线程调用。 */
    private static void sendTo(Player p, String channel, String msg) {
        switch (channel) {
            case "actionbar" -> p.sendActionBar(net.kyori.adventure.text.Component.text(msg));
            case "title" -> p.showTitle(net.kyori.adventure.title.Title.title(
                    net.kyori.adventure.text.Component.text(msg),
                    net.kyori.adventure.text.Component.empty()));
            default -> p.sendMessage(net.kyori.adventure.text.Component.text(msg));
        }
    }

    /** 随机数 [min,max] 闭区间。min&gt;max → error。两端皆整 → 整数随机（含 max，roll dice 体验）。 */
    private TraceStep doSetRandom(String wallId, String blockId, Action.SetRandomVariable a) {
        if (store == null) {
            return TraceStep.error(blockId, "VariableStore not wired");
        }
        if (!Double.isFinite(a.min()) || !Double.isFinite(a.max()) || a.min() > a.max()) {
            return TraceStep.error(blockId, "invalid random range: " + a.min() + ".." + a.max());
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
            return TraceStep.error(blockId, "VariableStore not wired");
        }
        boolean mul = "multiply".equals(a.op());
        if (!mul && !"divide".equals(a.op())) {
            return TraceStep.error(blockId, "unknown op: " + a.op());
        }
        if (!Double.isFinite(a.factor())) {
            return TraceStep.error(blockId, "factor must be finite");
        }
        if (!mul && a.factor() == 0.0) {
            return TraceStep.error(blockId, "divisor must not be 0");
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
            return TraceStep.error(blockId, "AnimationTicker not wired");
        }
        if (a.timelineId() == null || a.timelineId().isBlank()) {
            return TraceStep.error(blockId, "missing timelineId");
        }
        AnimationTicker.Result r = ticker.play(wallId, a.timelineId());
        if (r != AnimationTicker.Result.OK) {
            return TraceStep.error(blockId, "play failed: " + r);
        }
        return TraceStep.ok(blockId, "action", "playAwait " + a.timelineId());
    }

    // ---------- 置顶置底 / 取整 / 标题弹窗 ----------

    /** 元素置顶/置底 → {@link ElementPropertyApplier#applySetElementLayer}（双路径）。 */
    private TraceStep doSetElementLayer(String wallId, String blockId, Action.SetElementLayer a) {
        if (applier == null) {
            return TraceStep.error(blockId, "ElementPropertyApplier not wired");
        }
        return applier.applySetElementLayer(wallId, blockId, a.elementId(), a.mode());
    }

    /**
     * 变量取整（async，读当前值 → 严格文法校验 → 取整 → setValue）。
     * 非严格数值（null / "abc" / "NaN" / "Infinity" / "0x1p4" / "5d" 等）→ error step：
     * 取整一个非数值变量无合理意义，应让用户在 trace 看到（区别于 doIncrement / doScale
     * 的「空变量从 0 起算」语义）。用 {@link StrictNumber#PATTERN} 严格判定（禁
     * Double.parseDouble，后者接受 NaN / Infinity / 0x1p4 / 5d 等非法形式）。
     */
    private TraceStep doRoundVariable(String wallId, String blockId, Action.RoundVariable a) {
        if (store == null || storeLookup == null) {
            return TraceStep.error(blockId, "VariableStore not wired");
        }
        String fullName = VariableInterpolator.resolveFullName(a.fullName(), wallId);
        String raw = storeLookup.apply(fullName);
        // 非严格数值（abc / 0x1p4 / 5d / Infinity / NaN）→ error step（区别于 doIncrement/doScale
        // 的「按 0」：取整非数值无意义，让用户在 trace 看到）。StrictNumber.PATTERN 严格判定。
        if (raw == null || !StrictNumber.PATTERN.matcher(raw.trim()).matches()) {
            return TraceStep.error(blockId, "roundVariable " + fullName + ": not numeric, cannot round");
        }
        double parsed = StrictNumber.parse(raw);
        double result = switch (a.mode()) {
            case "floor" -> Math.floor(parsed);
            case "ceil" -> Math.ceil(parsed);
            default -> (double) Math.round(parsed);  // "round"（validator 已校验白名单）
        };
        String formatted = formatNumber(result);
        try {
            store.setValue(fullName, formatted, null);
        } catch (VariableException e) {
            return TraceStep.error(blockId, "roundVariable " + fullName + ": " + e.getMessage());
        }
        return TraceStep.ok(blockId, "action", fullName + " = " + formatted
                + " (" + a.mode() + ")");
    }

    /**
     * 标题弹窗（主线程 hop + target 分流，照 {@link #doSendMessage}）。
     * title / subtitle 过 {@code ${var:X}} 插值；ms → tick = ms/50（整除，向下截断）。
     * target=all → 全服广播；target=trigger → 触发玩家（{@link ScriptRunner#TRIGGER_DETAIL}）。
     */
    private TraceStep doShowTitle(String wallId, String blockId, Action.ShowTitle a) {
        String titleText = interpolator == null ? (a.title() == null ? "" : a.title())
                : interpolator.interpolate(a.title() == null ? "" : a.title(), wallId).text();
        String subtitleText = interpolator == null ? (a.subtitle() == null ? "" : a.subtitle())
                : interpolator.interpolate(a.subtitle() == null ? "" : a.subtitle(), wallId).text();
        int fadeInTicks = a.fadeInMs() / 50;
        int stayTicks = a.stayMs() / 50;
        int fadeOutTicks = a.fadeOutMs() / 50;

        if ("all".equals(a.target())) {
            Runnable work = () -> {
                try {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        p.sendTitle(titleText, subtitleText, fadeInTicks, stayTicks, fadeOutTicks);
                    }
                } catch (Throwable t) {
                    log.log(Level.WARNING, "[script] showTitle broadcast failed: " + t.getMessage(), t);
                }
            };
            if (plugin == null) {
                work.run();
            } else {
                Bukkit.getScheduler().runTask(plugin, work);
            }
            return TraceStep.ok(blockId, "action", "title -> broadcast");
        }
        // target=trigger：发给触发玩家
        String who = ScriptRunner.currentTriggerDetail();
        if (who == null || who.isBlank()) {
            return TraceStep.ok(blockId, "action", "no trigger player; showTitle skipped");
        }
        Runnable work = () -> {
            try {
                Player p = Bukkit.getPlayerExact(who);
                if (p == null) return; // 离线
                p.sendTitle(titleText, subtitleText, fadeInTicks, stayTicks, fadeOutTicks);
            } catch (Throwable t) {
                log.log(Level.WARNING, "[script] showTitle failed: " + t.getMessage(), t);
            }
        };
        if (plugin == null) {
            work.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, work);
        }
        return TraceStep.ok(blockId, "action", "title -> " + who);
    }

    /**
     * {@link ActionSink#timelineDurationMs}：从 wallRepo 读 timeline 一轮时长（ms）。
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
