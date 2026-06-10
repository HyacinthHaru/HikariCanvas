package moe.hikari.canvas.script.engine;

import moe.hikari.canvas.render.AnimationTicker;
import moe.hikari.canvas.script.Action;
import moe.hikari.canvas.state.StrictNumber;
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

import java.util.function.Function;
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
 *   <li>{@code runCommand} → K4：P2 固定 blocked step（命令模板系统 0.7.0-P3）</li>
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
    private final Logger log;

    public ActionExecutor(@Nullable VariableStore store,
                          @Nullable TickerControl ticker,
                          @Nullable ElementPropertyApplier applier,
                          @Nullable WallRepo wallRepo,
                          @Nullable Plugin plugin,
                          Logger log) {
        this.store = store;
        this.interpolator = store == null ? null : new VariableInterpolator(store);
        this.storeLookup = store == null ? null : ConditionEvaluator.storeLookup(store);
        this.ticker = ticker;
        this.applier = applier;
        this.wallRepo = wallRepo;
        this.plugin = plugin;
        this.log = log;
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
                // wait / if 由 Runner 处理；进到这里是 Runner 实现 bug → 防御 error
                case Action.Wait a -> TraceStep.error(blockId, "wait 应由 ScriptRunner 处理");
                case Action.If a -> TraceStep.error(blockId, "if 应由 ScriptRunner 处理");
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

    // ---------- 命令（K4：P2 blocked） ----------

    private TraceStep doRunCommand(String wallId, String blockId, Action.RunCommand a) {
        if (log.isLoggable(Level.FINE)) {
            log.fine("[脚本] runCommand 被拦（命令模板系统 0.7.0-P3）: wall=" + wallId
                    + " template=" + a.templateId());
        }
        return TraceStep.blocked(blockId, "命令模板系统 0.7.0-P3");
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
}
