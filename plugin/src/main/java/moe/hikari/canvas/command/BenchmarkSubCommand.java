package moe.hikari.canvas.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import moe.hikari.canvas.benchmark.BenchCompositor;
import moe.hikari.canvas.benchmark.BenchmarkConfig;
import moe.hikari.canvas.benchmark.BenchmarkScene;
import moe.hikari.canvas.benchmark.RasterizeSample;
import moe.hikari.canvas.benchmark.SceneLibrary;
import moe.hikari.canvas.benchmark.SceneTimer;
import moe.hikari.canvas.render.CanvasCompositor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * 0.5.0-P1：{@code /canvas bench} 命令族——纯服务端渲染成本 benchmark 的运行 / 报告入口。
 *
 * <p>本类是<b>命令适配层</b>，可以引用 Bukkit；真正的压测核心（{@link SceneLibrary} /
 * {@link SceneTimer} / {@link BenchCompositor} / {@code Instrumentation}）全部 headless，
 * 零 Bukkit / Player / PacketEvents 依赖（见 {@code PROPOSAL.md §5.2.7}「Benchmark 4 原则」+
 * {@code docs/dynamic-data.md §13.3}）。</p>
 *
 * <h2>子命令</h2>
 *
 * <ul>
 *   <li>{@code list} — 列出 {@link SceneLibrary#generate()} 的全部场景（同步，廉价）</li>
 *   <li>{@code run [selector] [iterations] [warmup]} — 异步在 bench 线程跑压测，写
 *       {@code benchmarks/<ts>/{raw.json,summary.txt}}，回主线程给 sender 彩色摘要</li>
 *   <li>{@code report [id]} — 读最近（或指定 ts）{@code raw.json} 重算摘要打印</li>
 *   <li>{@code clear} — 清空 {@code benchmarks/} 目录内容</li>
 * </ul>
 *
 * <h2>哲学纪律（不可越界）</h2>
 *
 * <p>「工具不是保姆」——只测 CPU / 内存（rasterize + palette quantize），<b>绝不碰网络</b>，
 * 不模拟 viewer 带宽 / 压缩比；不写世界 / 地图池 / ItemFrame。rasterize 是 stateless +
 * 线程安全的，故 {@code run} 整段在守护线程跑，仅最后回主线程发消息（Bukkit Scheduler
 * 契约要求消息 / 调度类 API 在主线程调）。</p>
 *
 * <h2>消息颜色约定</h2>
 *
 * <ul>
 *   <li>{@code GOLD} 标题 / 状态高亮</li>
 *   <li>{@code GREEN} 成功</li>
 *   <li>{@code RED} 失败</li>
 *   <li>{@code GRAY} info / 提示 / usage</li>
 *   <li>{@code YELLOW} 警告 / 进行中</li>
 * </ul>
 */
public final class BenchmarkSubCommand {

    /** 本命令族权限节点（主插件会写进 paper-plugin.yml）。 */
    public static final String PERMISSION = "canvas.bench";

    /** {@code plugins/HikariCanvas/} 下存放报告的子目录名。 */
    private static final String BENCH_DIR = "benchmarks";

    /** raw.json 文件名（List&lt;RasterizeSample&gt; 序列化）。 */
    private static final String RAW_FILE = "raw.json";

    /** summary.txt 文件名（人类可读摘要）。 */
    private static final String SUMMARY_FILE = "summary.txt";

    private static final double BYTES_PER_MB = 1024.0 * 1024.0;

    private final JavaPlugin plugin;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 单线程守护执行器。rasterize 线程安全，但同一时刻只允许一个 benchmark（{@link #running}
     * 守卫）——多 bench 并发会互相争抢 CPU 干扰各自计时，且无业务意义。
     */
    private final ExecutorService benchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hikari-canvas-bench");
        t.setDaemon(true);
        return t;
    });

    /** 是否有 benchmark 正在跑。worker 线程读 / 写，主线程读，故 volatile。 */
    private volatile boolean running = false;

    public BenchmarkSubCommand(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /** HikariCanvas.onDisable 调：关停 bench 线程。 */
    public void shutdown() {
        benchExecutor.shutdownNow();
    }

    // ──────────────────────────────────────────────────────────────────
    //  Brigadier 注册
    // ──────────────────────────────────────────────────────────────────

    /**
     * 构造 {@code bench} literal 节点，挂到 {@link CanvasCommand}.build 的 {@code canvas} 根。
     *
     * <p>形态：{@code /canvas bench <list|run|report|clear> [args...]}。</p>
     */
    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("bench")
                .requires(src -> src.getSender().hasPermission(PERMISSION))
                // list
                .then(Commands.literal("list")
                        .executes(ctx -> {
                            doList(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                // run [selector] [iterations] [warmup]
                .then(Commands.literal("run")
                        .executes(ctx -> {
                            doRun(ctx.getSource().getSender(), "all", -1, -1);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("selector", StringArgumentType.word())
                                .suggests(this::suggestSelectors)
                                .executes(ctx -> {
                                    doRun(ctx.getSource().getSender(),
                                            StringArgumentType.getString(ctx, "selector"), -1, -1);
                                    return Command.SINGLE_SUCCESS;
                                })
                                .then(Commands.argument("iterations", IntegerArgumentType.integer(1))
                                        .executes(ctx -> {
                                            doRun(ctx.getSource().getSender(),
                                                    StringArgumentType.getString(ctx, "selector"),
                                                    IntegerArgumentType.getInteger(ctx, "iterations"),
                                                    -1);
                                            return Command.SINGLE_SUCCESS;
                                        })
                                        .then(Commands.argument("warmup", IntegerArgumentType.integer(0))
                                                .executes(ctx -> {
                                                    doRun(ctx.getSource().getSender(),
                                                            StringArgumentType.getString(ctx, "selector"),
                                                            IntegerArgumentType.getInteger(ctx, "iterations"),
                                                            IntegerArgumentType.getInteger(ctx, "warmup"));
                                                    return Command.SINGLE_SUCCESS;
                                                })))))
                // report [id]
                .then(Commands.literal("report")
                        .executes(ctx -> {
                            doReport(ctx.getSource().getSender(), null);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests(this::suggestReportDirs)
                                .executes(ctx -> {
                                    doReport(ctx.getSource().getSender(),
                                            StringArgumentType.getString(ctx, "id"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                // clear
                .then(Commands.literal("clear")
                        .executes(ctx -> {
                            doClear(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                // /canvas bench (no subcommand) → usage
                .executes(ctx -> {
                    sendUsage(ctx.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    // ──────────────────────────────────────────────────────────────────
    //  list
    // ──────────────────────────────────────────────────────────────────

    private void doList(CommandSender sender) {
        List<BenchmarkScene> scenes = new SceneLibrary().generate();
        if (scenes.isEmpty()) {
            sender.sendMessage(Component.text("No benchmark scenes defined.", NamedTextColor.GRAY));
            return;
        }
        sender.sendMessage(Component.text(
                "Benchmark scenes (" + scenes.size() + "):", NamedTextColor.GOLD));
        for (BenchmarkScene s : scenes) {
            sender.sendMessage(Component.text(String.format(
                    "  %s  [%s]  %dx%d tiles  %s",
                    s.id(), s.category(), s.tilesWide(), s.tilesHigh(), s.dominantElementType()),
                    NamedTextColor.GRAY));
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  run（异步）
    // ──────────────────────────────────────────────────────────────────

    /**
     * 启动一次 benchmark。{@code iterations} / {@code warmup} 为 {@code <0} 时取
     * {@link BenchmarkConfig#quick()} 默认。整段在 {@link #benchExecutor} 守护线程跑，
     * 结束回主线程发摘要。
     */
    private void doRun(CommandSender sender, String selector, int iterations, int warmup) {
        if (running) {
            sender.sendMessage(Component.text(
                    "A benchmark is already running. Please wait for it to finish.",
                    NamedTextColor.RED));
            return;
        }
        BenchmarkConfig quick = BenchmarkConfig.quick();
        int measured = iterations < 0 ? quick.measuredIterations() : iterations;
        int warm = warmup < 0 ? quick.warmupIterations() : warmup;
        BenchmarkConfig cfg = new BenchmarkConfig(
                warm, measured, quick.fpsValues(), quick.viewerCounts(), selector);

        running = true;
        // sendActionBar：CommandSender 实现 Audience；非玩家 sender（console）会无视 ActionBar
        sender.sendActionBar(Component.text("Running benchmark…", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text(
                "Benchmark started (selector='" + selector + "', "
                        + measured + " measured + " + warm + " warmup iters). "
                        + "Running on a background thread…",
                NamedTextColor.GRAY));

        benchExecutor.submit(() -> {
            try {
                runOnWorker(sender, cfg, selector);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "Benchmark run failed", t);
                String msg = t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : ": " + t.getMessage());
                runOnMain(() -> sender.sendMessage(Component.text(
                        "Benchmark failed: " + msg, NamedTextColor.RED)));
            } finally {
                running = false;
            }
        });
    }

    /** worker 线程：构造 compositor → 选场景 → 计时 → 写盘 → 回主线程发摘要。 */
    private void runOnWorker(CommandSender sender, BenchmarkConfig cfg, String selector) throws IOException {
        CanvasCompositor compositor = BenchCompositor.create(plugin.getLogger());
        List<BenchmarkScene> scenes = new SceneLibrary().select(selector);
        if (scenes.isEmpty()) {
            runOnMain(() -> sender.sendMessage(Component.text(
                    "No scenes matched selector '" + selector
                            + "'. Try /canvas bench list.", NamedTextColor.RED)));
            return;
        }

        List<RasterizeSample> allSamples = new ArrayList<>();
        SceneTimer timer = new SceneTimer();
        for (BenchmarkScene scene : scenes) {
            allSamples.addAll(timer.time(compositor, scene, cfg));
        }

        // 写盘：benchmarks/<currentTimeMillis>/
        long ts = System.currentTimeMillis();
        Path outDir = benchRoot().resolve(Long.toString(ts));
        Files.createDirectories(outDir);
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(outDir.resolve(RAW_FILE).toFile(), allSamples);

        List<SceneSummary> summaries = summarize(allSamples);
        Files.writeString(outDir.resolve(SUMMARY_FILE), renderSummaryText(selector, summaries));

        // 回主线程：发彩色摘要 + 保存路径
        runOnMain(() -> {
            sender.sendMessage(Component.text(
                    "Benchmark done — " + scenes.size() + " scene(s), "
                            + allSamples.size() + " sample(s):", NamedTextColor.GOLD));
            for (SceneSummary s : summaries) sendSummaryLine(sender, s);
            sender.sendMessage(Component.text(
                    "Saved to: " + outDir, NamedTextColor.GRAY));
        });
    }

    // ──────────────────────────────────────────────────────────────────
    //  report
    // ──────────────────────────────────────────────────────────────────

    private void doReport(CommandSender sender, String id) {
        Path dir;
        if (id != null && !id.isBlank()) {
            dir = benchRoot().resolve(id);
            if (!Files.isDirectory(dir)) {
                sender.sendMessage(Component.text(
                        "No benchmark report: " + id, NamedTextColor.RED));
                return;
            }
        } else {
            Optional<Path> latest = latestReportDir();
            if (latest.isEmpty()) {
                sender.sendMessage(Component.text(
                        "No benchmark reports yet. Run /canvas bench run first.",
                        NamedTextColor.GRAY));
                return;
            }
            dir = latest.get();
        }
        Path raw = dir.resolve(RAW_FILE);
        if (!Files.isRegularFile(raw)) {
            sender.sendMessage(Component.text(
                    "Report missing " + RAW_FILE + ": " + dir.getFileName(), NamedTextColor.RED));
            return;
        }
        List<RasterizeSample> samples;
        try {
            samples = mapper.readValue(raw.toFile(),
                    mapper.getTypeFactory().constructCollectionType(List.class, RasterizeSample.class));
        } catch (IOException e) {
            sender.sendMessage(Component.text(
                    "Failed to read report: " + e.getMessage(), NamedTextColor.RED));
            return;
        }
        List<SceneSummary> summaries = summarize(samples);
        sender.sendMessage(Component.text(
                "Report " + dir.getFileName() + " — " + summaries.size()
                        + " scene(s), " + samples.size() + " sample(s):", NamedTextColor.GOLD));
        for (SceneSummary s : summaries) sendSummaryLine(sender, s);
    }

    // ──────────────────────────────────────────────────────────────────
    //  clear
    // ──────────────────────────────────────────────────────────────────

    private void doClear(CommandSender sender) {
        Path root = benchRoot();
        if (!Files.isDirectory(root)) {
            sender.sendMessage(Component.text(
                    "Nothing to clear (no benchmarks directory).", NamedTextColor.GRAY));
            return;
        }
        int[] removed = {0};
        try {
            // 仅删 benchmarks/ 内部内容，绝不越出该目录（深度优先，文件先于目录）。
            try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder())
                        .filter(p -> !p.equals(root))
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                                removed[0]++;
                            } catch (IOException e) {
                                plugin.getLogger().log(Level.WARNING,
                                        "bench clear: failed to delete " + p, e);
                            }
                        });
            }
        } catch (IOException e) {
            sender.sendMessage(Component.text(
                    "Clear failed: " + e.getMessage(), NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text(
                "Cleared benchmarks directory (" + removed[0] + " entries removed).",
                NamedTextColor.GREEN));
    }

    // ──────────────────────────────────────────────────────────────────
    //  per-scene 摘要计算（list / run / report 共享）
    // ──────────────────────────────────────────────────────────────────

    /** 单场景聚合摘要（rasterize min/mean/max + palette mean + alloc MB/iter）。 */
    private record SceneSummary(
            String sceneId,
            int samples,
            double rasterizeMeanMs,
            double rasterizeMinMs,
            double rasterizeMaxMs,
            double paletteMeanMs,
            double allocMbPerIter
    ) {}

    /**
     * 把原始样本按 sceneId 分组（保留首次出现顺序），逐组算 rasterizeMillis 的
     * min/mean/max + paletteMillis 均值 + allocatedBytes 均值（→ MB；{@code <0} 的不支持
     * 计数器样本排除在 alloc 均值外）。
     */
    private List<SceneSummary> summarize(List<RasterizeSample> samples) {
        // LinkedHashMap 保插入顺序 → 摘要顺序与 generate() 场景顺序一致
        Map<String, List<RasterizeSample>> byScene = new LinkedHashMap<>();
        for (RasterizeSample s : samples) {
            byScene.computeIfAbsent(s.sceneId(), k -> new ArrayList<>()).add(s);
        }
        List<SceneSummary> out = new ArrayList<>(byScene.size());
        for (var e : byScene.entrySet()) {
            List<RasterizeSample> list = e.getValue();
            double sumR = 0, minR = Double.MAX_VALUE, maxR = -Double.MAX_VALUE, sumP = 0;
            double sumAlloc = 0;
            int allocCount = 0;
            for (RasterizeSample s : list) {
                double r = s.rasterizeMillis();
                sumR += r;
                if (r < minR) minR = r;
                if (r > maxR) maxR = r;
                sumP += s.paletteMillis();
                if (s.hasAllocation()) {
                    sumAlloc += s.allocatedBytes();
                    allocCount++;
                }
            }
            int n = list.size();
            double allocMb = allocCount == 0 ? -1.0 : (sumAlloc / allocCount) / BYTES_PER_MB;
            out.add(new SceneSummary(
                    e.getKey(), n,
                    n == 0 ? 0 : sumR / n,
                    n == 0 ? 0 : minR,
                    n == 0 ? 0 : maxR,
                    n == 0 ? 0 : sumP / n,
                    allocMb));
        }
        return out;
    }

    private void sendSummaryLine(CommandSender sender, SceneSummary s) {
        String alloc = s.allocMbPerIter() < 0
                ? "n/a"
                : String.format("%.2f MB/it", s.allocMbPerIter());
        sender.sendMessage(Component.text(String.format(
                "  %s  raster %.3f/%.3f/%.3f ms (mean/min/max)  palette %.3f ms  alloc %s",
                s.sceneId(),
                s.rasterizeMeanMs(), s.rasterizeMinMs(), s.rasterizeMaxMs(),
                s.paletteMeanMs(), alloc),
                NamedTextColor.GRAY));
    }

    private String renderSummaryText(String selector, List<SceneSummary> summaries) {
        StringBuilder sb = new StringBuilder();
        sb.append("HikariCanvas benchmark summary\n");
        sb.append("selector: ").append(selector).append('\n');
        sb.append("scenes:   ").append(summaries.size()).append('\n');
        sb.append("---------------------------------------------------------------\n");
        sb.append(String.format(
                "%-28s %8s %8s %8s %8s %12s%n",
                "scene", "raster", "min", "max", "palette", "alloc/it"));
        for (SceneSummary s : summaries) {
            String alloc = s.allocMbPerIter() < 0
                    ? "n/a"
                    : String.format("%.2fMB", s.allocMbPerIter());
            sb.append(String.format(
                    "%-28s %7.3f %7.3f %7.3f %7.3f %12s%n",
                    s.sceneId(),
                    s.rasterizeMeanMs(), s.rasterizeMinMs(), s.rasterizeMaxMs(),
                    s.paletteMeanMs(), alloc));
        }
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────
    //  路径 / 调度 helpers
    // ──────────────────────────────────────────────────────────────────

    /** {@code plugins/HikariCanvas/benchmarks/}。 */
    private Path benchRoot() {
        return plugin.getDataFolder().toPath().resolve(BENCH_DIR);
    }

    /** 找出最新的 {@code benchmarks/<ts>/} 目录（按目录名 ts 数值降序）。 */
    private Optional<Path> latestReportDir() {
        Path root = benchRoot();
        if (!Files.isDirectory(root)) return Optional.empty();
        try (var children = Files.list(root)) {
            return children
                    .filter(Files::isDirectory)
                    .max(Comparator.comparingLong(p -> parseTsOrZero(p.getFileName().toString())));
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "latestReportDir scan failed", e);
            return Optional.empty();
        }
    }

    private static long parseTsOrZero(String name) {
        try {
            return Long.parseLong(name);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 在主线程执行（Bukkit 消息 / 调度 API 契约要求）。若插件已禁用导致调度失败，
     * 退化为直接执行（best-effort，至少不丢异常报告）。
     */
    private void runOnMain(Runnable r) {
        try {
            Bukkit.getScheduler().runTask(plugin, r);
        } catch (IllegalPluginAccessException | IllegalStateException ex) {
            // 插件正在禁用 / scheduler 已关 → best-effort 直接跑
            r.run();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  tab completion
    // ──────────────────────────────────────────────────────────────────

    /** run 的 selector：scene id + category 名 + "all"。 */
    private CompletableFuture<Suggestions> suggestSelectors(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder b) {
        SceneLibrary lib = new SceneLibrary();
        List<String> all = new ArrayList<>();
        all.add("all");
        all.addAll(lib.categories());
        for (BenchmarkScene s : lib.generate()) all.add(s.id());
        suggestMatching(b, all);
        return b.buildFuture();
    }

    /** report 的 id：现有 benchmarks/<ts>/ 目录名。 */
    private CompletableFuture<Suggestions> suggestReportDirs(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder b) {
        Path root = benchRoot();
        if (Files.isDirectory(root)) {
            try (var children = Files.list(root)) {
                List<String> names = children
                        .filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .sorted(Comparator.comparingLong(BenchmarkSubCommand::parseTsOrZero).reversed())
                        .toList();
                suggestMatching(b, names);
            } catch (IOException ignored) {
                // best-effort completion
            }
        }
        return b.buildFuture();
    }

    private static void suggestMatching(SuggestionsBuilder b, List<String> candidates) {
        String rem = b.getRemaining();
        String p = rem == null ? "" : rem.toLowerCase();
        for (String c : candidates) {
            if (p.isEmpty() || c.toLowerCase().startsWith(p)) b.suggest(c);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  usage
    // ──────────────────────────────────────────────────────────────────

    private static void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text("/canvas bench subcommands:", NamedTextColor.GOLD));
        sender.sendMessage(Component.text(
                "  /canvas bench list                          — list benchmark scenes",
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text(
                "  /canvas bench run [selector] [iters] [warm]  — run benchmark (async)",
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text(
                "  /canvas bench report [id]                   — print latest/given report",
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text(
                "  /canvas bench clear                         — wipe saved reports",
                NamedTextColor.GRAY));
    }
}
