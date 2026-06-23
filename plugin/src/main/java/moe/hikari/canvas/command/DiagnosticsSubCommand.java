package moe.hikari.canvas.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
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
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

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
    private final WallRepo wallRepo;
    private final VariableStore variableStore;
    private final AnimationTicker animationTicker;
    private final TweenScheduler tweenScheduler;
    @SuppressWarnings("unused") // 留作 0.9.2 后续 diagnose 扩展（ItemFrame 在位检测）用
    private final FrameDeployer frameDeployer;
    private final TokenService tokenService;
    private final Database database;
    @SuppressWarnings("unused") // 留作 0.9.2 后续 diagnose 扩展用
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
     * 0.9.2 Task 2：仅供单测的窄构造器——{@link #diagnose} 纯逻辑只依赖 {@link Messages}，其余 10
     * 个子系统在 diagnose 链中由调用方预先快照进 {@link DiagnoseInputs}，故测试无需装配它们（也就
     * 不必引 Bukkit / SQLite）。<b>勿用于生产</b>：用此构造器的实例调 {@link #runStats} /
     * {@link #runDiagnose} / {@link #suggestWallIds} 会 NPE。包级可见，仅测试同包可用。
     */
    DiagnosticsSubCommand(Messages messages) {
        this.mapPool = null;
        this.sessionManager = null;
        this.wallRepo = null;
        this.variableStore = null;
        this.animationTicker = null;
        this.tweenScheduler = null;
        this.frameDeployer = null;
        this.tokenService = null;
        this.database = null;
        this.plugin = null;
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

    // ──────────────────────────────────────────────────────────────────
    //  /canvas diagnose <wallId>（0.9.2 Task 2）
    // ──────────────────────────────────────────────────────────────────

    /**
     * {@code /canvas diagnose <wall_id>}（管理员）的 Brigadier 入口。从 {@link CommandContext}
     * 取 {@code wall_id} + sender，把各子系统的<b>只读</b>快照装进 {@link DiagnoseInputs}，再交给纯
     * 逻辑 {@link #diagnose}（与 Bukkit / Brigadier 解耦，便于单测）。
     *
     * <p><b>诊断链（只读，逐环节查 + 发对应 lang 行）</b>：墙存在 → 地图分配 → 世界加载 →
     * 活跃 session → ProjectState 解析 → 动画态 → 总结。任何环节都<b>不</b>触发修复。</p>
     *
     * <p><b>not-found vs state-corrupt 的区分</b>：{@link WallRepo#loadById} 在 {@code mapRow}
     * 阶段已经反序列化 {@code project_json} → {@link moe.hikari.canvas.state.ProjectState}，且把解析
     * 失败<b>吞成 {@link Optional#empty()}</b>——所以"墙不存在"和"墙存在但工程数据损坏"经 loadById
     * 看起来一模一样。为真正区分二者，本入口额外用已注入的 {@link Database} 做一条<b>只读裸 SQL</b>
     * （仅取 {@code project_json}，不反序列化），判断 walls 行是否物理存在：行缺失 = not-found；
     * 行存在但 loadById 为空（解析失败）= state-corrupt。不新增 WallRepo 方法。</p>
     */
    public int runDiagnose(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String wallId = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "wall_id");

        Optional<WallRepo.Wall> wall = wallRepo.loadById(wallId);
        boolean rawRowExists = rawWallRowExists(wallId);
        MapPool.Stats poolStats = mapPool.stats();
        // 世界加载判定走 Bukkit 主线程 API（diagnose 命令在主线程执行）；用 Predicate seam 让单测
        // 无需 Bukkit 也能注入"世界未加载"场景。
        Predicate<String> worldLoaded = name -> name != null && Bukkit.getWorld(name) != null;
        boolean sessionActive = sessionManager.isWallActive(wallId);
        boolean animating = animationTicker.isWallAnimating(wallId);

        DiagnoseInputs in = new DiagnoseInputs(
                wall, rawRowExists, worldLoaded, sessionActive, animating, poolStats);
        return diagnose(sender, wallId, in);
    }

    /**
     * diagnose 一次的<b>只读</b>输入快照（让纯逻辑 {@link #diagnose} 与 Bukkit / DB 解耦，单测可造）。
     *
     * @param wall          {@link WallRepo#loadById} 结果（解析成功的 Wall；空 = 不存在或工程数据损坏）
     * @param rawRowExists  walls 行是否物理存在（裸 SQL 探测，用于区分 not-found / state-corrupt）
     * @param worldLoaded   按 world 名判断世界是否已加载（生产 = {@code Bukkit.getWorld != null}）
     * @param sessionActive 该 wall 是否有活跃编辑 session
     * @param animating     该 wall 是否在播放时间轴
     * @param poolStats     地图池概况（maps-missing 时附带）
     */
    record DiagnoseInputs(
            Optional<WallRepo.Wall> wall,
            boolean rawRowExists,
            Predicate<String> worldLoaded,
            boolean sessionActive,
            boolean animating,
            MapPool.Stats poolStats) {}

    /**
     * 诊断链纯逻辑——逐环节发 lang 行，OK/WARN/ERROR/INFO 由 lang 行自带颜色标签。返回首个
     * ERROR/WARN 作为总结；全 OK 发 summary-ok。任何环节都不修改状态。
     */
    int diagnose(CommandSender sender, String wallId, DiagnoseInputs in) {
        messages.send(sender, "command.diagnose.header",
                Placeholder.unparsed("wall_id", wallId));

        // ── 1. 墙存在 ──
        if (in.wall().isEmpty()) {
            if (in.rawRowExists()) {
                // 行物理存在但 loadById 为空 = project_json 解析失败 → state-corrupt，停。
                messages.send(sender, "command.diagnose.state-corrupt");
                summaryIssues(sender, "command.diagnose.state-corrupt");
            } else {
                messages.send(sender, "command.diagnose.not-found",
                        Placeholder.unparsed("wall_id", wallId));
                summaryIssues(sender, "command.diagnose.not-found");
            }
            return Command.SINGLE_SUCCESS;
        }
        WallRepo.Wall w = in.wall().get();

        // 第一个 ERROR/WARN 的 lang key 记下来用于总结；后续环节继续跑完（一次看全部问题）。
        String firstIssueKey = null;

        // ── 2. 地图分配 ──
        int expectedMaps = w.widthMaps() * w.heightMaps();
        int assignedMaps = w.mapIds() == null ? 0 : w.mapIds().size();
        if (assignedMaps == 0 || assignedMaps != expectedMaps) {
            messages.send(sender, "command.diagnose.maps-missing",
                    Placeholder.unparsed("map_count", String.valueOf(assignedMaps)),
                    Placeholder.unparsed("expected", String.valueOf(expectedMaps)),
                    Placeholder.unparsed("width", String.valueOf(w.widthMaps())),
                    Placeholder.unparsed("height", String.valueOf(w.heightMaps())),
                    Placeholder.unparsed("pool_total", String.valueOf(in.poolStats().total())),
                    Placeholder.unparsed("pool_free", String.valueOf(in.poolStats().free())),
                    Placeholder.unparsed("pool_reserved", String.valueOf(in.poolStats().reserved())));
            if (firstIssueKey == null) {
                firstIssueKey = "command.diagnose.maps-missing";
            }
        } else {
            messages.send(sender, "command.diagnose.maps-ok",
                    Placeholder.unparsed("map_count", String.valueOf(assignedMaps)),
                    Placeholder.unparsed("expected", String.valueOf(expectedMaps)),
                    Placeholder.unparsed("width", String.valueOf(w.widthMaps())),
                    Placeholder.unparsed("height", String.valueOf(w.heightMaps())));
        }

        // ── 3. 世界加载 ──
        String worldName = w.key().world();
        if (!in.worldLoaded().test(worldName)) {
            messages.send(sender, "command.diagnose.world-unloaded",
                    Placeholder.unparsed("world", worldName));
            if (firstIssueKey == null) {
                firstIssueKey = "command.diagnose.world-unloaded";
            }
        } else {
            messages.send(sender, "command.diagnose.world-loaded",
                    Placeholder.unparsed("world", worldName));
        }

        // ── 4. 活跃 session（INFO，非错误）──
        messages.send(sender, in.sessionActive()
                ? "command.diagnose.session-active" : "command.diagnose.session-none");

        // ── 5. ProjectState 解析（loadById 成功 = 已解析；附图层数）──
        //    走到这里 wall 非空 → project_json 已成功反序列化（loadById/mapRow 保证），故为 OK。
        int layerCount = w.state() == null || w.state().layers() == null
                ? 0 : w.state().layers().size();
        messages.send(sender, "command.diagnose.state-ok",
                Placeholder.unparsed("layers", String.valueOf(layerCount)));

        // ── 6. 动画态（INFO）──
        messages.send(sender, in.animating()
                ? "command.diagnose.anim-playing" : "command.diagnose.anim-static");

        // ── 7. 总结 ──
        if (firstIssueKey == null) {
            messages.send(sender, "command.diagnose.summary-ok");
        } else {
            summaryIssues(sender, firstIssueKey);
        }
        return Command.SINGLE_SUCCESS;
    }

    /**
     * 发 summary-issues 行：把首个问题环节的 lang 文本（去掉 MiniMessage 标签 / 占位符 / 缩进）作为
     * {@code <issue>} 注入，避免再嵌一层颜色标签。文本取自调用者 locale 的同一 lang 行。
     */
    private void summaryIssues(CommandSender sender, String issueKey) {
        String localeId = messages.localeId(sender);
        String raw = messages.rawOrNull(localeId, issueKey);
        String issueText = raw == null ? issueKey : stripTags(raw);
        messages.send(sender, "command.diagnose.summary-issues",
                Placeholder.unparsed("issue", issueText));
    }

    /** 去掉 MiniMessage {@code <...>} 标签 + 首尾空白 / 前缀符号，让 summary 行不再嵌标签。 */
    private static String stripTags(String s) {
        // 去掉 <tag> / </tag>；保留占位符如 <wall_id> 也一并去掉（summary 仅作摘要，不再填值）。
        return s.replaceAll("<[^>]*>", "").trim();
    }

    /**
     * 裸 SQL 探测：walls 行是否物理存在（仅 SELECT project_json，不反序列化 ProjectState）。
     * 与 {@link #runStats} 一样走已注入的 {@link Database#jdbi()} 只读查询。出错（罕见）按"不存在"
     * 处理——diagnose 是诊断工具，宁可报 not-found 也不抛。
     */
    private boolean rawWallRowExists(String wallId) {
        try {
            return database.jdbi().withHandle(h -> h.createQuery(
                    "SELECT 1 FROM walls WHERE wall_id = :id")
                    .bind("id", wallId)
                    .mapTo(Integer.class)
                    .findOne()
                    .isPresent());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * {@code /canvas diagnose <wall_id>} 的 wallId tab 补全：照
     * {@link VariableSubCommand} 范式从 {@link WallRepo#loadAll()} 列 wallId。前缀大小写不敏感匹配。
     */
    public CompletableFuture<Suggestions> suggestWallIds(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder b) {
        String rem = b.getRemaining();
        String remLower = rem == null ? "" : rem.toLowerCase(Locale.ROOT);
        for (WallRepo.Wall w : wallRepo.loadAll()) {
            String id = w.wallId();
            if (remLower.isEmpty() || id.toLowerCase(Locale.ROOT).startsWith(remLower)) {
                b.suggest(id);
            }
        }
        return b.buildFuture();
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
