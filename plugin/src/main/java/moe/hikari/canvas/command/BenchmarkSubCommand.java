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
import moe.hikari.canvas.benchmark.BenchmarkReport;
import moe.hikari.canvas.benchmark.BenchmarkRunner;
import moe.hikari.canvas.benchmark.BenchmarkScene;
import moe.hikari.canvas.benchmark.EnvInfo;
import moe.hikari.canvas.benchmark.GcSummary;
import moe.hikari.canvas.benchmark.HtmlReportRenderer;
import moe.hikari.canvas.benchmark.PerElementCost;
import moe.hikari.canvas.benchmark.Percentiles;
import moe.hikari.canvas.benchmark.SceneLibrary;
import moe.hikari.canvas.benchmark.SceneResult;
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
import java.util.List;
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

    /** report.json 文件名（{@link BenchmarkReport} 序列化——P2 聚合报告）。 */
    private static final String REPORT_FILE = "report.json";

    /** summary.txt 文件名（人类可读摘要）。 */
    private static final String SUMMARY_FILE = "summary.txt";

    /** report.html 文件名（P3 自包含 HTML 报告：内联 SVG 图 + 50mspt 公式计算器）。 */
    private static final String HTML_FILE = "report.html";

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
            sender.sendMessage(Component.text("还没有定义任何测试场景。", NamedTextColor.GRAY));
            return;
        }
        sender.sendMessage(Component.text(
                "测试场景（共 " + scenes.size() + " 个）：", NamedTextColor.GOLD));
        for (BenchmarkScene s : scenes) {
            sender.sendMessage(Component.text(String.format(
                    "  %s  [%s]  %dx%d 格  %s",
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
                    "已有测试在跑，请等它跑完。",
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
        sender.sendActionBar(Component.text("正在测试…", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text(
                "已开始测试（场景='" + selector + "', "
                        + "正式 " + measured + " 轮 + 预热 " + warm + " 轮）。"
                        + "后台运行中，跑完会出结果…",
                NamedTextColor.GRAY));

        benchExecutor.submit(() -> {
            try {
                runOnWorker(sender, cfg, selector);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "Benchmark run failed", t);
                String msg = t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : ": " + t.getMessage());
                runOnMain(() -> sender.sendMessage(Component.text(
                        "测试失败：" + msg, NamedTextColor.RED)));
            } finally {
                running = false;
            }
        });
    }

    /** worker 线程：构造 compositor → 跑聚合 runner → 写 report.json/summary.txt → 回主线程发摘要。 */
    private void runOnWorker(CommandSender sender, BenchmarkConfig cfg, String selector) throws IOException {
        CanvasCompositor compositor = BenchCompositor.create(plugin.getLogger());
        // 预检：selector 无匹配场景时早退，避免空跑空白基线。
        if (new SceneLibrary().select(selector).isEmpty()) {
            runOnMain(() -> sender.sendMessage(Component.text(
                    "没有匹配 '" + selector
                            + "' 的场景，用 /canvas bench list 看有哪些。", NamedTextColor.RED)));
            return;
        }

        // P2：BenchmarkRunner 内部 选场景 → 测同尺寸空白基线 → 逐场景计时 → 聚合 percentile +
        // per-element + GC/env。generatedAtMillis 由命令层注入（runner 不读时钟保持可测）。
        BenchmarkReport report = new BenchmarkRunner()
                .run(compositor, new SceneLibrary(), cfg, System.currentTimeMillis());

        // 写盘：benchmarks/<generatedAt>/{report.json, summary.txt}
        Path outDir = benchRoot().resolve(Long.toString(report.generatedAtMillis()));
        Files.createDirectories(outDir);
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(outDir.resolve(REPORT_FILE).toFile(), report);
        Files.writeString(outDir.resolve(SUMMARY_FILE), renderReportText(selector, report));
        // P3：自包含 HTML 报告（内联 SVG 图 + 50mspt 公式交互计算器），服主浏览器打开即看。
        Files.writeString(outDir.resolve(HTML_FILE), HtmlReportRenderer.render(report));

        // 回主线程：发彩色摘要 + 保存路径
        runOnMain(() -> {
            sendReportToSender(sender, report);
            sender.sendMessage(Component.text("已保存到：" + outDir, NamedTextColor.GRAY));
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
                        "找不到这份测试报告：" + id, NamedTextColor.RED));
                return;
            }
        } else {
            Optional<Path> latest = latestReportDir();
            if (latest.isEmpty()) {
                sender.sendMessage(Component.text(
                        "还没有任何测试报告，先用 /canvas bench run 跑一次。",
                        NamedTextColor.GRAY));
                return;
            }
            dir = latest.get();
        }
        Path file = dir.resolve(REPORT_FILE);
        if (!Files.isRegularFile(file)) {
            sender.sendMessage(Component.text(
                    "报告缺少 " + REPORT_FILE + "：" + dir.getFileName(), NamedTextColor.RED));
            return;
        }
        BenchmarkReport report;
        try {
            report = mapper.readValue(file.toFile(), BenchmarkReport.class);
        } catch (IOException e) {
            sender.sendMessage(Component.text(
                    "读取报告失败：" + e.getMessage(), NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text(
                "报告 " + dir.getFileName() + "：", NamedTextColor.GOLD));
        sendReportToSender(sender, report);
    }

    // ──────────────────────────────────────────────────────────────────
    //  clear
    // ──────────────────────────────────────────────────────────────────

    private void doClear(CommandSender sender) {
        Path root = benchRoot();
        if (!Files.isDirectory(root)) {
            sender.sendMessage(Component.text(
                    "没有可清除的内容（benchmarks 目录不存在）。", NamedTextColor.GRAY));
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
                    "清除失败：" + e.getMessage(), NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text(
                "已清空 benchmarks 目录（删除了 " + removed[0] + " 项）。",
                NamedTextColor.GREEN));
    }

    // ──────────────────────────────────────────────────────────────────
    //  per-scene 摘要计算（list / run / report 共享）
    // ──────────────────────────────────────────────────────────────────

    /** 给 sender 发彩色聚合摘要：环境 + 逐场景 percentile + per-element + GC。run / report 共享。 */
    private void sendReportToSender(CommandSender sender, BenchmarkReport report) {
        EnvInfo env = report.env();
        sender.sendMessage(Component.text(String.format(
                "测试完成 — %d 个场景  ·  空白画面耗时 %.3f ms",
                report.scenes().size(), report.blankBaselineMs()), NamedTextColor.GOLD));
        sender.sendMessage(Component.text(String.format(
                "环境: Java %s · %d 核 · 最大内存 %s · 内存回收 %s",
                env.javaVersion(), env.availableProcessors(),
                env.maxHeapMb() < 0 ? "?" : env.maxHeapMb() + "MB", env.gcNames()),
                NamedTextColor.GRAY));
        for (SceneResult r : report.scenes()) {
            sendSceneLine(sender, r);
        }
        if (!report.perElement().isEmpty()) {
            sender.sendMessage(Component.text(
                    "每个元素的额外耗时 (ms/个):",
                    NamedTextColor.GOLD));
            for (PerElementCost p : report.perElement()) {
                sender.sendMessage(Component.text(String.format(
                        "  %-10s %.5f ms/个  (共 %d 个)",
                        p.elementType(), p.marginalMsPerElement(), p.elementCount()),
                        NamedTextColor.GRAY));
            }
        }
        GcSummary gc = report.gc();
        sender.sendMessage(Component.text(String.format(
                "测试期间内存回收: %d 次, 共 %d ms (含预热)",
                gc.collectionCount(), gc.collectionTimeMs()), NamedTextColor.GRAY));
    }

    /** 单场景一行：rasterize p50/p95/p99 + palette p50 + alloc MB/it。 */
    private void sendSceneLine(CommandSender sender, SceneResult r) {
        Percentiles ras = r.rasterizeMs();
        String alloc = r.allocSupported()
                ? String.format("%.2f MB/it", r.meanAllocMbPerIter())
                : "n/a";
        sender.sendMessage(Component.text(String.format(
                "  %s  渲染 %.3f/%.3f/%.3f ms (一般/偏慢/最慢)  调色板 %.3f ms  内存 %s",
                r.sceneId(), ras.p50(), ras.p95(), ras.p99(), r.paletteMs().p50(), alloc),
                NamedTextColor.GRAY));
    }

    /** summary.txt 全文：环境 + config + 逐场景 percentile 表 + per-element 表 + GC + 基线。 */
    private String renderReportText(String selector, BenchmarkReport report) {
        EnvInfo env = report.env();
        BenchmarkConfig cfg = report.config();
        StringBuilder sb = new StringBuilder();
        sb.append("HikariCanvas 性能测试报告\n");
        sb.append("测试场景: ").append(selector).append('\n');
        sb.append("环境:     Java ").append(env.javaVersion())
                .append(" / ").append(env.osName()).append(' ').append(env.osArch()).append('\n');
        sb.append("CPU:      ").append(env.availableProcessors()).append(" 核   最大内存 ")
                .append(env.maxHeapMb() < 0 ? "?" : env.maxHeapMb() + "MB").append('\n');
        sb.append("内存回收方式: ").append(env.gcNames()).append('\n');
        sb.append("测试设置: 正式 ").append(cfg.measuredIterations()).append(" 轮 + 预热 ")
                .append(cfg.warmupIterations()).append(" 轮   (帧率=").append(cfg.fpsValues())
                .append(", 观看人数=").append(cfg.viewerCounts())
                .append(")\n");
        sb.append("空白画面耗时: 空场景渲染 ")
                .append(String.format("%.4f", report.blankBaselineMs())).append(" ms\n");
        sb.append("内存回收:  ").append(report.gc().collectionCount()).append(" 次, 共 ")
                .append(report.gc().collectionTimeMs()).append(" ms (含预热)\n");
        sb.append("=================================================================\n");
        sb.append(String.format("%-28s  %-21s  %-21s  %10s%n",
                "场景", "渲染 一般/偏慢/最慢", "调色板 一般/偏慢/最慢", "每轮内存"));
        for (SceneResult r : report.scenes()) {
            Percentiles ra = r.rasterizeMs();
            Percentiles pa = r.paletteMs();
            String alloc = r.allocSupported()
                    ? String.format("%.2fMB", r.meanAllocMbPerIter()) : "n/a";
            sb.append(String.format("%-28s  %6.3f/%6.3f/%6.3f  %6.3f/%6.3f/%6.3f  %10s%n",
                    r.sceneId(), ra.p50(), ra.p95(), ra.p99(),
                    pa.p50(), pa.p95(), pa.p99(), alloc));
        }
        sb.append("-----------------------------------------------------------------\n");
        sb.append("每个元素的额外耗时 (ms/个):\n");
        for (PerElementCost p : report.perElement()) {
            sb.append(String.format("  %-10s x%-4d  %.5f ms/个   (单独测 %.4f ms, 场景 %s)%n",
                    p.elementType(), p.elementCount(), p.marginalMsPerElement(),
                    p.isolationSceneMeanMs(), p.sceneId()));
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
        sender.sendMessage(Component.text("/canvas bench 子命令：", NamedTextColor.GOLD));
        sender.sendMessage(Component.text(
                "  /canvas bench list                          — 列出测试场景",
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text(
                "  /canvas bench run [场景] [轮次] [预热]  — 运行性能测试",
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text(
                "  /canvas bench report [id]                   — 打印最近的或指定的报告",
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text(
                "  /canvas bench clear                         — 清空已保存的报告",
                NamedTextColor.GRAY));
    }
}
