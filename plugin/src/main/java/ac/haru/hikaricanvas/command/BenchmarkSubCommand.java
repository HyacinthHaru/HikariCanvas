package ac.haru.hikaricanvas.command;

import ac.haru.hikaricanvas.benchmark.BenchCompositor;
import ac.haru.hikaricanvas.benchmark.BenchmarkConfig;
import ac.haru.hikaricanvas.benchmark.BenchmarkReport;
import ac.haru.hikaricanvas.benchmark.BenchmarkReport.BenchRow;
import ac.haru.hikaricanvas.benchmark.BenchmarkReport.EnvInfo;
import ac.haru.hikaricanvas.benchmark.BenchmarkReport.GcSummary;
import ac.haru.hikaricanvas.benchmark.BenchmarkReport.PerElementCost;
import ac.haru.hikaricanvas.benchmark.BenchmarkReport.Percentiles;
import ac.haru.hikaricanvas.benchmark.BenchmarkReport.SceneResult;
import ac.haru.hikaricanvas.benchmark.BenchmarkRunner;
import ac.haru.hikaricanvas.benchmark.HtmlReportRenderer;
import ac.haru.hikaricanvas.benchmark.SceneLibrary;
import ac.haru.hikaricanvas.benchmark.SceneLibrary.BenchmarkScene;
import ac.haru.hikaricanvas.benchmark.ScriptBenchmarkDriver;
import ac.haru.hikaricanvas.benchmark.TweenBenchmarkDriver;
import ac.haru.hikaricanvas.i18n.Messages;
import ac.haru.hikaricanvas.render.CanvasCompositor;
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
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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
import java.util.function.Function;
import java.util.logging.Level;

/**
 * {@code /canvas bench} 命令族——服务端渲染、补间与脚本 benchmark 的运行与报告入口
 * （详见 {@code docs/benchmark.md}）。
 *
 * <p>本类为 Bukkit 命令适配层；压测核心（{@link SceneLibrary} / {@link BenchmarkRunner} /
 * {@link BenchCompositor} / {@link TweenBenchmarkDriver} / {@link ScriptBenchmarkDriver}）全部为无头纯函数，
 * 在独立守护线程上执行，完成后回到主线程发送摘要消息。</p>
 *
 * <h2>子命令</h2>
 * <ul>
 *   <li>{@code list} — 列出 {@link SceneLibrary#generate()} 的全部场景</li>
 *   <li>{@code run [selector] [iterations] [warmup]} — 异步跑渲染压测，产出
 *       {@code benchmarks/<ts>/{report.json,summary.txt,report.html}} 并发彩色摘要</li>
 *   <li>{@code report [id]} — 读取最近（或指定时间戳）的 {@code report.json} 重新打印摘要</li>
 *   <li>{@code clear} — 清空 {@code benchmarks/} 目录内容</li>
 *   <li>{@code run-tween [iterations] [warmup]} — 异步跑补间帧率压测</li>
 *   <li>{@code run-script [iterations] [warmup]} — 异步跑脚本动作链压测</li>
 * </ul>
 */
public final class BenchmarkSubCommand {

    /** 本命令族权限节点（主插件声明于 paper-plugin.yml）。 */
    public static final String PERMISSION = "canvas.bench";

    /** {@code plugins/HikariCanvas/} 下存放报告的子目录名。 */
    private static final String BENCH_DIR = "benchmarks";

    /** report.json 文件名（{@link BenchmarkReport} 序列化产物）。 */
    private static final String REPORT_FILE = "report.json";

    /** summary.txt 文件名（纯文本可读摘要）。 */
    private static final String SUMMARY_FILE = "summary.txt";

    /** report.html 文件名（自包含 HTML5 报告：内联 SVG 图与预算计算器）。 */
    private static final String HTML_FILE = "report.html";

    private final JavaPlugin plugin;
    private final Messages messages;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 单线程守护执行器；同一时刻仅允许一个压测任务运行（{@link #running} 守卫），
     * 避免并发争抢 CPU 污染计时。
     */
    private final ExecutorService benchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hikari-canvas-bench");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running = false;

    public BenchmarkSubCommand(JavaPlugin plugin, Messages messages) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    /** HikariCanvas.onDisable 调用：关停压测后台线程。 */
    public void shutdown() {
        benchExecutor.shutdownNow();
    }

    // ──────────────────────────────────────────────────────────────────
    //  Brigadier 注册
    // ──────────────────────────────────────────────────────────────────

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
                // run-tween [iterations] [warmup]
                .then(Commands.literal("run-tween")
                        .executes(ctx -> {
                            doRunTween(ctx.getSource().getSender(), -1, -1);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("iterations", IntegerArgumentType.integer(1))
                                .executes(ctx -> {
                                    doRunTween(ctx.getSource().getSender(),
                                            IntegerArgumentType.getInteger(ctx, "iterations"), -1);
                                    return Command.SINGLE_SUCCESS;
                                })
                                .then(Commands.argument("warmup", IntegerArgumentType.integer(0))
                                        .executes(ctx -> {
                                            doRunTween(ctx.getSource().getSender(),
                                                    IntegerArgumentType.getInteger(ctx, "iterations"),
                                                    IntegerArgumentType.getInteger(ctx, "warmup"));
                                            return Command.SINGLE_SUCCESS;
                                        }))))
                // run-script [iterations] [warmup]
                .then(Commands.literal("run-script")
                        .executes(ctx -> {
                            doRunScript(ctx.getSource().getSender(), -1, -1);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("iterations", IntegerArgumentType.integer(1))
                                .executes(ctx -> {
                                    doRunScript(ctx.getSource().getSender(),
                                            IntegerArgumentType.getInteger(ctx, "iterations"), -1);
                                    return Command.SINGLE_SUCCESS;
                                })
                                .then(Commands.argument("warmup", IntegerArgumentType.integer(0))
                                        .executes(ctx -> {
                                            doRunScript(ctx.getSource().getSender(),
                                                    IntegerArgumentType.getInteger(ctx, "iterations"),
                                                    IntegerArgumentType.getInteger(ctx, "warmup"));
                                            return Command.SINGLE_SUCCESS;
                                        }))))
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
            messages.send(sender, "command.bench.list.no-scenes");
            return;
        }
        messages.send(sender, "command.bench.list.header",
                Placeholder.unparsed("count", String.valueOf(scenes.size())));
        for (BenchmarkScene s : scenes) {
            messages.send(sender, "command.bench.list.scene-line",
                    Placeholder.unparsed("scene_id", s.id()),
                    Placeholder.unparsed("category", s.category()),
                    Placeholder.unparsed("tiles_wide", String.valueOf(s.tilesWide())),
                    Placeholder.unparsed("tiles_high", String.valueOf(s.tilesHigh())),
                    Placeholder.unparsed("element_type", s.dominantElementType()));
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  run（异步）
    // ──────────────────────────────────────────────────────────────────

    private void doRun(CommandSender sender, String selector, int iterations, int warmup) {
        if (running) {
            messages.send(sender, "command.bench.already-running");
            return;
        }
        BenchmarkConfig quick = BenchmarkConfig.quick();
        int measured = iterations < 0 ? quick.measuredIterations() : iterations;
        int warm = warmup < 0 ? quick.warmupIterations() : warmup;
        BenchmarkConfig cfg = new BenchmarkConfig(
                warm, measured, quick.fpsValues(), quick.viewerCounts(), selector);

        running = true;
        sender.sendActionBar(messages.get(messages.localeId(sender), "command.bench.running"));
        messages.send(sender, "command.bench.run.started",
                Placeholder.unparsed("selector", selector),
                Placeholder.unparsed("measured", String.valueOf(measured)),
                Placeholder.unparsed("warmup", String.valueOf(warm)));

        benchExecutor.submit(() -> {
            try {
                runOnWorker(sender, cfg, selector);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "Benchmark run failed", t);
                String msg = t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : ": " + t.getMessage());
                runOnMain(() -> messages.send(sender, "command.bench.run.failed",
                        Placeholder.unparsed("message", msg)));
            } finally {
                running = false;
            }
        });
    }

    /** worker 线程：构造无头 compositor → 运行 BenchmarkRunner → 写入三件报告产物 → 回主线程发摘要。 */
    private void runOnWorker(CommandSender sender, BenchmarkConfig cfg, String selector) throws IOException {
        CanvasCompositor compositor = BenchCompositor.create(plugin.getLogger());
        SceneLibrary lib = new SceneLibrary();
        if (lib.select(selector).isEmpty()) {
            runOnMain(() -> messages.send(sender, "command.bench.run.no-match",
                    Placeholder.unparsed("selector", selector)));
            return;
        }

        BenchmarkReport report = new BenchmarkRunner()
                .run(compositor, lib, cfg, System.currentTimeMillis());

        Path outDir = benchRoot().resolve(Long.toString(report.generatedAtMillis()));
        Files.createDirectories(outDir);
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(outDir.resolve(REPORT_FILE).toFile(), report);
        Files.writeString(outDir.resolve(SUMMARY_FILE), renderReportText(selector, report));
        Files.writeString(outDir.resolve(HTML_FILE), HtmlReportRenderer.render(report, messages));

        runOnMain(() -> {
            sendReportToSender(sender, report);
            messages.send(sender, "command.bench.run.saved",
                    Placeholder.unparsed("path", outDir.toString()));
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
                messages.send(sender, "command.bench.report.not-found",
                        Placeholder.unparsed("id", id));
                return;
            }
        } else {
            Optional<Path> latest = latestReportDir();
            if (latest.isEmpty()) {
                messages.send(sender, "command.bench.report.empty");
                return;
            }
            dir = latest.get();
        }
        Path file = dir.resolve(REPORT_FILE);
        if (!Files.isRegularFile(file)) {
            messages.send(sender, "command.bench.report.missing-file",
                    Placeholder.unparsed("report_file", REPORT_FILE),
                    Placeholder.unparsed("dir", dir.getFileName().toString()));
            return;
        }
        BenchmarkReport report;
        try {
            report = mapper.readValue(file.toFile(), BenchmarkReport.class);
        } catch (IOException e) {
            messages.send(sender, "command.bench.report.read-failed",
                    Placeholder.unparsed("message", String.valueOf(e.getMessage())));
            return;
        }
        messages.send(sender, "command.bench.report.header",
                Placeholder.unparsed("id", dir.getFileName().toString()));
        sendReportToSender(sender, report);
    }

    // ──────────────────────────────────────────────────────────────────
    //  clear
    // ──────────────────────────────────────────────────────────────────

    private void doClear(CommandSender sender) {
        Path root = benchRoot();
        if (!Files.isDirectory(root)) {
            messages.send(sender, "command.bench.clear.nothing");
            return;
        }
        int[] removed = {0};
        try {
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
            messages.send(sender, "command.bench.clear.failed",
                    Placeholder.unparsed("message", String.valueOf(e.getMessage())));
            return;
        }
        messages.send(sender, "command.bench.clear.done",
                Placeholder.unparsed("count", String.valueOf(removed[0])));
    }

    // ──────────────────────────────────────────────────────────────────
    //  per-scene 摘要格式化（run / report 共享）
    // ──────────────────────────────────────────────────────────────────

    private void sendReportToSender(CommandSender sender, BenchmarkReport report) {
        EnvInfo env = report.env();
        messages.send(sender, "command.bench.summary.header",
                Placeholder.unparsed("scene_count", String.valueOf(report.scenes().size())),
                Placeholder.unparsed("blank_baseline_ms", String.format("%.3f", report.blankBaselineMs())));
        messages.send(sender, "command.bench.summary.env",
                Placeholder.unparsed("java_version", env.javaVersion()),
                Placeholder.unparsed("processors", String.valueOf(env.availableProcessors())),
                Placeholder.unparsed("max_heap_mb", env.maxHeapMb() < 0 ? "?" : env.maxHeapMb() + "MB"),
                Placeholder.unparsed("gc_names", String.join(", ", env.gcNames())));
        for (SceneResult r : report.scenes()) {
            sendSceneLine(sender, r);
        }
        if (!report.perElement().isEmpty()) {
            messages.send(sender, "command.bench.summary.per-element-header");
            for (PerElementCost p : report.perElement()) {
                messages.send(sender, "command.bench.summary.per-element-line",
                        Placeholder.unparsed("element_type", String.format("%-10s", p.elementType())),
                        Placeholder.unparsed("marginal_ms", String.format("%.5f", p.marginalMsPerElement())),
                        Placeholder.unparsed("element_count", String.valueOf(p.elementCount())));
            }
        }
        GcSummary gc = report.gc();
        messages.send(sender, "command.bench.summary.gc",
                Placeholder.unparsed("gc_count", String.valueOf(gc.collectionCount())),
                Placeholder.unparsed("gc_time_ms", String.valueOf(gc.collectionTimeMs())));
    }

    private void sendSceneLine(CommandSender sender, SceneResult r) {
        Percentiles ras = r.rasterizeMs();
        String alloc = r.allocSupported()
                ? String.format("%.2f MB/it", r.meanAllocMbPerIter())
                : "n/a";
        messages.send(sender, "command.bench.summary.scene-line",
                Placeholder.unparsed("scene_id", r.sceneId()),
                Placeholder.unparsed("p50", String.format("%.3f", ras.p50())),
                Placeholder.unparsed("p95", String.format("%.3f", ras.p95())),
                Placeholder.unparsed("p99", String.format("%.3f", ras.p99())),
                Placeholder.unparsed("palette_p50", String.format("%.3f", r.paletteMs().p50())),
                Placeholder.unparsed("alloc", alloc));
    }

    private String renderReportText(String selector, BenchmarkReport report) {
        String loc = messages.defaultLocale();
        Function<String, String> t = k -> {
            String s = messages.rawOrNull(loc, k);
            return s == null ? k : s;
        };
        EnvInfo env = report.env();
        BenchmarkConfig cfg = report.config();
        StringBuilder sb = new StringBuilder();
        sb.append(t.apply("command.bench.report-file.title")).append('\n');
        sb.append(t.apply("command.bench.report-file.scene-prefix")).append(selector).append('\n');
        sb.append(t.apply("command.bench.report-file.env-prefix")).append(env.javaVersion())
                .append(" / ").append(env.osName()).append(' ').append(env.osArch()).append('\n');
        sb.append(t.apply("command.bench.report-file.cpu-prefix")).append(env.availableProcessors())
                .append(t.apply("command.bench.report-file.cpu-cores-mem"))
                .append(env.maxHeapMb() < 0 ? "?" : env.maxHeapMb() + "MB").append('\n');
        sb.append(t.apply("command.bench.report-file.gc-method-prefix")).append(env.gcNames()).append('\n');
        sb.append(t.apply("command.bench.report-file.settings-prefix")).append(cfg.measuredIterations())
                .append(t.apply("command.bench.report-file.settings-warmup"))
                .append(cfg.warmupIterations())
                .append(t.apply("command.bench.report-file.settings-fps")).append(cfg.fpsValues())
                .append(t.apply("command.bench.report-file.settings-viewers")).append(cfg.viewerCounts())
                .append(t.apply("command.bench.report-file.settings-suffix")).append('\n');
        sb.append(t.apply("command.bench.report-file.blank-prefix"))
                .append(String.format("%.4f", report.blankBaselineMs()))
                .append(t.apply("command.bench.report-file.blank-suffix")).append('\n');
        sb.append(t.apply("command.bench.report-file.gc-prefix")).append(report.gc().collectionCount())
                .append(t.apply("command.bench.report-file.gc-mid"))
                .append(report.gc().collectionTimeMs())
                .append(t.apply("command.bench.report-file.gc-suffix")).append('\n');
        sb.append("=================================================================\n");
        sb.append(String.format("%-28s  %-21s  %-21s  %10s%n",
                t.apply("command.bench.report-file.col-scene"),
                t.apply("command.bench.report-file.col-rasterize"),
                t.apply("command.bench.report-file.col-palette"),
                t.apply("command.bench.report-file.col-alloc")));
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
        sb.append(t.apply("command.bench.report-file.per-element-header")).append('\n');
        for (PerElementCost p : report.perElement()) {
            sb.append(String.format("  %-10s x%-4d  %.5f", p.elementType(), p.elementCount(), p.marginalMsPerElement()))
                    .append(t.apply("command.bench.report-file.per-element-unit"))
                    .append(String.format("%.4f", p.isolationSceneMeanMs()))
                    .append(t.apply("command.bench.report-file.per-element-scene"))
                    .append(p.sceneId()).append(")\n");
        }
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────
    //  路径 / 调度 helpers
    // ──────────────────────────────────────────────────────────────────

    private Path benchRoot() {
        return plugin.getDataFolder().toPath().resolve(BENCH_DIR);
    }

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

    private void runOnMain(Runnable r) {
        try {
            Bukkit.getScheduler().runTask(plugin, r);
        } catch (IllegalPluginAccessException | IllegalStateException ex) {
            r.run();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  tab completion
    // ──────────────────────────────────────────────────────────────────

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
    //  run-tween / run-script
    // ──────────────────────────────────────────────────────────────────

    private void doRunTween(CommandSender sender, int iterations, int warmup) {
        if (running) {
            messages.send(sender, "command.bench.already-running");
            return;
        }
        int measured = iterations < 0 ? 100 : iterations;
        int warm = warmup < 0 ? 10 : warmup;
        running = true;
        messages.send(sender, "command.bench.run-tween.started",
                Placeholder.unparsed("measured", String.valueOf(measured)),
                Placeholder.unparsed("warmup", String.valueOf(warm)));
        benchExecutor.submit(() -> {
            try {
                List<BenchRow> rows = TweenBenchmarkDriver.measure(
                        List.of(1, 4, 16, 64), warm, measured);
                runOnMain(() -> sendBenchRows(sender, "run-tween", measured, warm, rows));
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "run-tween failed", t);
                String msg = t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : ": " + t.getMessage());
                runOnMain(() -> messages.send(sender, "command.bench.run-tween.failed",
                        Placeholder.unparsed("message", msg)));
            } finally {
                running = false;
            }
        });
    }

    private void doRunScript(CommandSender sender, int iterations, int warmup) {
        if (running) {
            messages.send(sender, "command.bench.already-running");
            return;
        }
        int measured = iterations < 0 ? 100 : iterations;
        int warm = warmup < 0 ? 10 : warmup;
        running = true;
        messages.send(sender, "command.bench.run-script.started",
                Placeholder.unparsed("measured", String.valueOf(measured)),
                Placeholder.unparsed("warmup", String.valueOf(warm)));
        benchExecutor.submit(() -> {
            try {
                List<BenchRow> rows = ScriptBenchmarkDriver.measure(
                        List.of(1, 10, 25, 50), warm, measured);
                runOnMain(() -> sendBenchRows(sender, "run-script", measured, warm, rows));
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "run-script failed", t);
                String msg = t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : ": " + t.getMessage());
                runOnMain(() -> messages.send(sender, "command.bench.run-script.failed",
                        Placeholder.unparsed("message", msg)));
            } finally {
                running = false;
            }
        });
    }

    private void sendBenchRows(CommandSender sender, String which, int measured, int warm,
                               List<BenchRow> rows) {
        String base = "command.bench." + which;
        messages.send(sender, base + ".done");
        messages.send(sender, base + ".subtitle",
                Placeholder.unparsed("measured", String.valueOf(measured)),
                Placeholder.unparsed("warmup", String.valueOf(warm)));
        messages.send(sender, base + ".table-header");
        for (BenchRow r : rows) {
            var p = r.p();
            messages.send(sender, base + ".row",
                    Placeholder.unparsed("n", String.format("%-8d", r.n())),
                    Placeholder.unparsed("p50", String.format("%8.4f", p.p50())),
                    Placeholder.unparsed("p95", String.format("%8.4f", p.p95())),
                    Placeholder.unparsed("p99", String.format("%8.4f", p.p99())),
                    Placeholder.unparsed("mean", String.format("%8.4f", p.mean())),
                    Placeholder.unparsed("max", String.format("%8.4f", p.max())));
        }
        messages.send(sender, base + ".note");
    }

    private void sendUsage(CommandSender sender) {
        messages.send(sender, "command.bench.usage.header");
        messages.send(sender, "command.bench.usage.list");
        messages.send(sender, "command.bench.usage.run");
        messages.send(sender, "command.bench.usage.run-tween");
        messages.send(sender, "command.bench.usage.run-script");
        messages.send(sender, "command.bench.usage.report");
        messages.send(sender, "command.bench.usage.clear");
    }
}
