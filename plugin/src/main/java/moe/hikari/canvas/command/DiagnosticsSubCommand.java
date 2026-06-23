package moe.hikari.canvas.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import moe.hikari.canvas.deploy.FrameDeployer;
import moe.hikari.canvas.i18n.Messages;
import moe.hikari.canvas.pool.MapPool;
import moe.hikari.canvas.render.AnimationTicker;
import moe.hikari.canvas.script.engine.TweenScheduler;
import moe.hikari.canvas.session.SessionManager;
import moe.hikari.canvas.session.TokenService;
import moe.hikari.canvas.storage.Database;
import moe.hikari.canvas.storage.WallRepo;
import moe.hikari.canvas.variable.VariableStore;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Objects;

/**
 * 0.9.2 可观测性：{@code /canvas stats} 的实现 + 后续 {@code diagnose} 子命令族（Task 2 接入）。
 *
 * <p>从 {@link CanvasCommand} 迁出原 {@code runStats}（信息太少：仅地图池 total/free/reserved
 * + 墙 + 会话 + token）并增强为多行输出：</p>
 * <ul>
 *   <li><b>池行</b>：total/free/reserved + 上限 + 画布数（锁定数）+ 会话数 + 令牌数</li>
 *   <li><b>分世界行</b>（多世界 / 非空时才发）：每 world 的 FREE map 数</li>
 *   <li><b>变量行</b>：变量总数 + 按 namespace 前缀摘要（如 {@code user=12 system=8 …}）</li>
 *   <li><b>动画行</b>：注册中的时间轴墙数 + 活跃补间数</li>
 * </ul>
 *
 * <h2>架构（照 {@link VariableSubCommand} / {@link BenchmarkSubCommand} 范式）</h2>
 *
 * <p>构造注入各子系统的只读 accessor，纯逻辑 {@link #runStats}；Brigadier 注册由
 * {@link CanvasCommand}.build 直接 {@code .executes(diagnostics::runStats)}（{@code stats}
 * 节点 requires {@code canvas.admin} 不变）。多注几个依赖（{@link FrameDeployer} /
 * {@link JavaPlugin} / {@link WallRepo}）供 0.9.2 Task 2 的 {@code diagnose} 子命令族复用。</p>
 *
 * <h2>线程</h2>
 *
 * <p>命令在 Bukkit 主线程执行：{@link MapPool} 读走 {@code synchronized}（与主线程
 * reserve/bind 互斥）；{@link VariableStore#statsByNamespace} / {@link TweenScheduler#activeCount}
 * / {@link AnimationTicker#registeredCount} 读 CHM 无锁；walls/locked 两条只读 SQL（照搬旧
 * runStats）。</p>
 */
public final class DiagnosticsSubCommand {

    private final MapPool mapPool;
    private final SessionManager sessionManager;
    @SuppressWarnings("unused") // Task 2 diagnose 用
    private final WallRepo wallRepo;
    private final VariableStore variableStore;
    private final AnimationTicker animationTicker;
    private final TweenScheduler tweenScheduler;
    @SuppressWarnings("unused") // Task 2 diagnose 用
    private final FrameDeployer frameDeployer;
    private final TokenService tokenService;
    private final Database database;
    @SuppressWarnings("unused") // Task 2 diagnose 用
    private final JavaPlugin plugin;
    private final Messages messages;

    public DiagnosticsSubCommand(MapPool mapPool,
                                 SessionManager sessionManager,
                                 WallRepo wallRepo,
                                 VariableStore variableStore,
                                 AnimationTicker animationTicker,
                                 TweenScheduler tweenScheduler,
                                 FrameDeployer frameDeployer,
                                 TokenService tokenService,
                                 Database database,
                                 JavaPlugin plugin,
                                 Messages messages) {
        this.mapPool = Objects.requireNonNull(mapPool, "mapPool");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        this.wallRepo = Objects.requireNonNull(wallRepo, "wallRepo");
        this.variableStore = Objects.requireNonNull(variableStore, "variableStore");
        this.animationTicker = Objects.requireNonNull(animationTicker, "animationTicker");
        this.tweenScheduler = Objects.requireNonNull(tweenScheduler, "tweenScheduler");
        this.frameDeployer = Objects.requireNonNull(frameDeployer, "frameDeployer");
        this.tokenService = Objects.requireNonNull(tokenService, "tokenService");
        this.database = Objects.requireNonNull(database, "database");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    /**
     * {@code /canvas stats}（管理员）。从 {@link CanvasCommand} 迁出并增强为多行输出。
     */
    public int runStats(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();

        // ── 池行 ──
        MapPool.Stats ps = mapPool.stats();
        int poolMax = mapPool.maxSize();
        int wallsCount = database.jdbi().withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM walls").mapTo(Integer.class).one());
        // P3-99: published_at 非 null = 已锁定；统计标签术语对齐为 locked（DB 列名不变）。
        int locked = database.jdbi().withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM walls WHERE published_at IS NOT NULL")
                .mapTo(Integer.class).one());
        messages.send(sender, "command.stats.output",
                Placeholder.unparsed("pool_total", String.valueOf(ps.total())),
                Placeholder.unparsed("pool_free", String.valueOf(ps.free())),
                Placeholder.unparsed("pool_reserved", String.valueOf(ps.reserved())),
                Placeholder.unparsed("pool_max", String.valueOf(poolMax)),
                Placeholder.unparsed("walls", String.valueOf(wallsCount)),
                Placeholder.unparsed("walls_locked", String.valueOf(locked)),
                Placeholder.unparsed("sessions", String.valueOf(sessionManager.size())),
                Placeholder.unparsed("tokens", String.valueOf(tokenService.activeCount())));

        // ── 分世界行（仅多 world 或非空时发；单 world 时信息冗余，省略）──
        Map<String, Integer> byWorld = mapPool.byWorldStats();
        if (byWorld.size() > 1) {
            messages.send(sender, "command.stats.by-world",
                    Placeholder.unparsed("world_breakdown", joinBreakdown(byWorld)));
        }

        // ── 变量行 ──
        Map<String, Integer> byNs = variableStore.statsByNamespace();
        String varBreakdown = byNs.isEmpty() ? "-" : joinBreakdown(byNs);
        messages.send(sender, "command.stats.variables",
                Placeholder.unparsed("var_total", String.valueOf(variableStore.size())),
                Placeholder.unparsed("var_breakdown", varBreakdown));

        // ── 动画行 ──
        messages.send(sender, "command.stats.animation",
                Placeholder.unparsed("timeline_walls",
                        String.valueOf(animationTicker.registeredCount())),
                Placeholder.unparsed("tween_active",
                        String.valueOf(tweenScheduler.activeCount())));

        return Command.SINGLE_SUCCESS;
    }

    /**
     * 把 {@code key=count} 对拼成单行空格分隔字符串（如 {@code world=32 world_nether=8}）。
     * 用 {@code unparsed} placeholder 注入——避免 world 名 / namespace 含 MiniMessage 特殊字符
     * 被解析。保持枚举顺序（{@link java.util.LinkedHashMap}）。
     */
    private static String joinBreakdown(Map<String, Integer> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
