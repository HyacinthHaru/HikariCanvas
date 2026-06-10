package moe.hikari.canvas.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import moe.hikari.canvas.storage.AuditLog;
import moe.hikari.canvas.storage.WallRepo;
import moe.hikari.canvas.variable.Variable;
import moe.hikari.canvas.variable.VariableException;
import moe.hikari.canvas.variable.VariableStore;
import moe.hikari.canvas.variable.plugin.HikariCanvasAPIImpl;
import moe.hikari.canvas.variable.plugin.PushRateLimiter;
import moe.hikari.canvas.variable.provider.VariableProvider;
import moe.hikari.canvas.variable.provider.VariableProviderDaemon;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 0.4.0-P5-A：{@code /canvas var} 命令族（7 子命令）。
 *
 * <p>所有子命令统一 {@code canvas.var.command} 权限（默认 op）。命令族范围与
 * {@code docs/dynamic-data.md} 一致：</p>
 *
 * <ul>
 *   <li>{@code list [namespace]} — 列变量 / 列单 namespace 变量</li>
 *   <li>{@code get <fullName>} — 查变量完整元信息</li>
 *   <li>{@code set <fullName> <value...>} — 手动设值（args 拼接支持空格）</li>
 *   <li>{@code delete <fullName>} — 删变量</li>
 *   <li>{@code providers} — 列已注册 {@link VariableProvider}</li>
 *   <li>{@code reload} — 重读 {@code config.yml} 限流参数 → 替换
 *       {@link HikariCanvasAPIImpl} 的 {@link PushRateLimiter}</li>
 *   <li>{@code inspect <wallId>} — 倒排索引 {@code referencedFullNamesByWall} 可视化</li>
 * </ul>
 *
 * <h2>架构</h2>
 *
 * <p>类拆 Brigadier 注册（{@link #register} 嵌进 {@link CanvasCommand}.build）+ 纯逻辑
 * （{@link #execute}）。后者只依赖 {@link CommandSender} + {@code String[] args}，便于
 * 单测无 Bukkit 时直接调（{@link moe.hikari.canvas.command.VariableSubCommandTest}）。</p>
 *
 * <p>{@code set} / {@code delete} 写入 {@link AuditLog} 两个新事件：
 * {@code VARIABLE_COMMAND_SET} / {@code VARIABLE_COMMAND_DELETE}（区别于 WS op 的
 * {@code VARIABLE_SET} / {@code VARIABLE_DELETE}，便于运维事后审计来源）。</p>
 *
 * <h2>消息颜色约定</h2>
 *
 * <ul>
 *   <li>{@code §6} 标题 / 状态高亮</li>
 *   <li>{@code §a} 成功</li>
 *   <li>{@code §c} 失败 / 拒绝</li>
 *   <li>{@code §7} info / 提示 / usage</li>
 *   <li>{@code §e} 警告 / 待确认</li>
 * </ul>
 */
public final class VariableSubCommand {

    /** 所有子命令需要的权限。 */
    public static final String PERMISSION = "canvas.var.command";

    /** 7 子命令名（tab completion 用）。 */
    static final List<String> SUBCOMMANDS = List.of(
            "list", "get", "set", "delete", "providers", "reload", "inspect");

    /** 人类可读时间格式器。 */
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /**
     * 0.4.0-P5：抽出 wall 列表 / 查询接口，让单测无 SQLite + 无 mock 框架也能造数据。
     *
     * <p>生产实现 = {@link #fromWallRepo}，包装真 {@link WallRepo}。</p>
     */
    public interface WallSource {
        /** 列出所有 wallId（tab completion 用）。 */
        List<String> allWallIds();

        /** 查 wall 是否存在（inspect 命令用）。 */
        boolean exists(String wallId);

        /** 工厂：用真 {@link WallRepo} 构造（生产路径）。 */
        static WallSource fromWallRepo(WallRepo repo) {
            return new WallSource() {
                @Override public List<String> allWallIds() {
                    List<String> out = new ArrayList<>();
                    for (WallRepo.Wall w : repo.loadAll()) out.add(w.wallId());
                    return out;
                }
                @Override public boolean exists(String wallId) {
                    return repo.loadById(wallId).isPresent();
                }
            };
        }
    }

    private final VariableStore store;
    private final VariableProviderDaemon daemon;
    private final WallSource walls;
    private final AuditLog auditLog;
    private final ReloadHook reloadHook;

    /**
     * 注入用 hook：reload 子命令调它把新的限流参数应用到 {@link HikariCanvasAPIImpl}。
     *
     * <p>抽出 hook 是为了：(a) 单测不依赖 HikariCanvas 主类；(b) 主类决定如何
     * 安排 {@code plugin.reloadConfig() + HikariCanvasConfig.load + applyConfig + setRateLimiter}
     * 全序。命令侧仅触发。</p>
     */
    @FunctionalInterface
    public interface ReloadHook {
        /**
         * 触发 reload 流程。
         *
         * @return 新 {@link PushRateLimiter.Config}（用于反馈给玩家显示当前生效参数）；
         *         若失败抛 RuntimeException，命令侧 catch 后给玩家 §c 错误提示。
         */
        PushRateLimiter.Config reload();
    }

    /** 生产路径构造器（HikariCanvas 主类用）。 */
    public VariableSubCommand(VariableStore store,
                              VariableProviderDaemon daemon,
                              WallRepo wallRepo,
                              AuditLog auditLog,
                              ReloadHook reloadHook) {
        this(store, daemon, WallSource.fromWallRepo(Objects.requireNonNull(wallRepo, "wallRepo")),
                auditLog, reloadHook);
    }

    /** 测试 / 注入构造器：直接传 {@link WallSource}。 */
    public VariableSubCommand(VariableStore store,
                              VariableProviderDaemon daemon,
                              WallSource walls,
                              AuditLog auditLog,
                              ReloadHook reloadHook) {
        this.store = Objects.requireNonNull(store, "store");
        this.daemon = Objects.requireNonNull(daemon, "daemon");
        this.walls = Objects.requireNonNull(walls, "walls");
        this.auditLog = auditLog; // 测试可为 null
        this.reloadHook = Objects.requireNonNull(reloadHook, "reloadHook");
    }

    // ──────────────────────────────────────────────────────────────────
    //  Brigadier 注册（生产路径）
    // ──────────────────────────────────────────────────────────────────

    /**
     * 注入到 {@link CanvasCommand}.build 的 {@code canvas} 根节点。
     *
     * <p>形态：{@code /canvas var <sub> [args...]}。每个子命令一个 literal 分支；
     * 多参子命令额外加 {@link com.mojang.brigadier.arguments.StringArgumentType#greedyString()}
     * 或 {@code word()} 节点。tab completion 通过 {@link #buildSubcommandSuggestions}
     * 等返 {@link CompletableFuture<Suggestions>}。</p>
     */
    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("var")
                .requires(src -> src.getSender().hasPermission(PERMISSION))
                // list / list <namespace>
                // 0.7.0-P2 修：string() 不带引号时只认 [0-9A-Za-z_.+-]，真实 namespace
                // 含冒号/斜杠（user:w-xxxx）会报"参数后应有空格分隔"——尾参改 greedyString
                .then(Commands.literal("list")
                        .executes(ctx -> runExec(ctx, new String[]{"list"}))
                        .then(Commands.argument("namespace", StringArgumentType.greedyString())
                                .suggests(this::suggestNamespaces)
                                .executes(ctx -> runExec(ctx, new String[]{"list",
                                        StringArgumentType.getString(ctx, "namespace")}))))
                // get <fullName>
                .then(Commands.literal("get")
                        .then(Commands.argument("fullName", StringArgumentType.greedyString())
                                .suggests(this::suggestFullNames)
                                .executes(ctx -> runExec(ctx, new String[]{"get",
                                        StringArgumentType.getString(ctx, "fullName")}))))
                // set <fullName> <value...>
                // 0.7.0-P2 修：fullName 原用 string()——不带引号时冒号/斜杠直接 parse error
                // （游戏内 /canvas var set user:w-xxxx/score 5 报"参数后应有空格分隔"）。
                // Brigadier 中段参数无法既不带引号又收任意字符，改成整尾 greedyString +
                // 手工按第一个空格切 fullName / value（value 仍可含空格）。
                .then(Commands.literal("set")
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .suggests(this::suggestFullNames)
                                .executes(ctx -> {
                                    String raw = StringArgumentType.getString(ctx, "args").trim();
                                    int sp = raw.indexOf(' ');
                                    String[] args = (sp < 0)
                                            ? new String[]{"set", raw}
                                            : new String[]{"set", raw.substring(0, sp),
                                                    raw.substring(sp + 1).trim()};
                                    return runExec(ctx, args);
                                })))
                // delete <fullName>
                .then(Commands.literal("delete")
                        .then(Commands.argument("fullName", StringArgumentType.greedyString())
                                .suggests(this::suggestFullNames)
                                .executes(ctx -> runExec(ctx, new String[]{"delete",
                                        StringArgumentType.getString(ctx, "fullName")}))))
                // providers
                .then(Commands.literal("providers")
                        .executes(ctx -> runExec(ctx, new String[]{"providers"})))
                // reload
                .then(Commands.literal("reload")
                        .executes(ctx -> runExec(ctx, new String[]{"reload"})))
                // inspect <wallId>
                .then(Commands.literal("inspect")
                        .then(Commands.argument("wallId", StringArgumentType.word())
                                .suggests(this::suggestWallIds)
                                .executes(ctx -> runExec(ctx, new String[]{"inspect",
                                        StringArgumentType.getString(ctx, "wallId")}))))
                // /canvas var (no subcommand) → 打印 usage
                .executes(ctx -> {
                    sendUsage(ctx.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                });
    }

    private int runExec(CommandContext<CommandSourceStack> ctx, String[] args) {
        CommandSender sender = ctx.getSource().getSender();
        execute(sender, args);
        return Command.SINGLE_SUCCESS;
    }

    // ──────────────────────────────────────────────────────────────────
    //  纯逻辑入口（单测调）
    // ──────────────────────────────────────────────────────────────────

    /**
     * 主入口。args[0] = 子命令；其余按子命令规格。
     *
     * <p>不抛异常——内部 catch 所有 {@link VariableException} / runtime 转 §c 消息。</p>
     *
     * @param sender CommandSender（player / console / cmd block）
     * @param args   {@code [sub, ...]}；空数组打 usage
     */
    public void execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage("§cPermission denied: " + PERMISSION);
            return;
        }
        if (args == null || args.length == 0 || args[0] == null) {
            // P3-76: null 首 token（理论上 Brigadier 不产生，但单测 / 外部调用可能传）
            // 走 usage 脱敏分支，避免 args[0].toLowerCase() 裸 NPE。
            sendUsage(sender);
            return;
        }
        String sub = args[0].toLowerCase();
        try {
            switch (sub) {
                case "list" -> doList(sender, args);
                case "get" -> doGet(sender, args);
                case "set" -> doSet(sender, args);
                case "delete" -> doDelete(sender, args);
                case "providers" -> doProviders(sender);
                case "reload" -> doReload(sender);
                case "inspect" -> doInspect(sender, args);
                default -> {
                    sender.sendMessage("§cUnknown subcommand: " + args[0]);
                    sendUsage(sender);
                }
            }
        } catch (VariableException e) {
            sender.sendMessage("§cFailed: " + e.code() + " — " + e.getMessage());
        } catch (Exception e) {
            sender.sendMessage("§cUnexpected error: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  Tab completion（单测可独立调）
    // ──────────────────────────────────────────────────────────────────

    /**
     * 纯函数 tab completion：根据 args 给出候选。args 长度对应正在补全的"已有 token"数。
     *
     * <ul>
     *   <li>{@code ["list"]}, {@code ["get"]}, ... 等子命令前缀 → 返 SUBCOMMANDS 过滤</li>
     *   <li>{@code ["list", "<prefix>"]} → namespaces 过滤</li>
     *   <li>{@code ["get"/"set"/"delete", "<prefix>"]} → fullNames 过滤</li>
     *   <li>{@code ["inspect", "<prefix>"]} → wallIds 过滤</li>
     * </ul>
     */
    public List<String> completions(String[] args) {
        if (args == null || args.length == 0) return SUBCOMMANDS;
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            String prefix = args[1];
            return switch (sub) {
                case "list" -> filterByPrefix(allNamespaces(), prefix);
                case "get", "set", "delete" -> filterByPrefix(allFullNames(), prefix);
                case "inspect" -> filterByPrefix(allWallIds(), prefix);
                default -> List.of();
            };
        }
        return List.of();
    }

    private static List<String> filterByPrefix(List<String> all, String prefix) {
        if (prefix == null || prefix.isEmpty()) return all;
        String p = prefix.toLowerCase();
        return all.stream().filter(s -> s.toLowerCase().startsWith(p)).toList();
    }

    private List<String> allNamespaces() {
        List<String> out = new ArrayList<>();
        for (Variable v : store.listAll()) {
            if (!out.contains(v.namespace())) out.add(v.namespace());
        }
        return out;
    }

    private List<String> allFullNames() {
        List<String> out = new ArrayList<>();
        for (Variable v : store.listAll()) out.add(v.fullName());
        return out;
    }

    private List<String> allWallIds() {
        return walls.allWallIds();
    }

    // ──────────────────────────────────────────────────────────────────
    //  子命令实现
    // ──────────────────────────────────────────────────────────────────

    private void doList(CommandSender sender, String[] args) {
        if (args.length == 1) {
            // 列全部 namespace + 数量
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Variable v : store.listAll()) {
                counts.merge(v.namespace(), 1, Integer::sum);
            }
            if (counts.isEmpty()) {
                sender.sendMessage("§7No variables in store.");
                return;
            }
            sender.sendMessage("§6Variable namespaces (" + counts.size() + " ns / "
                    + store.size() + " total):");
            for (var e : counts.entrySet()) {
                sender.sendMessage("  §7" + e.getKey() + " §8· §a" + e.getValue() + " var(s)");
            }
            return;
        }
        // 列单 namespace
        String ns = args[1];
        List<Variable> vars = store.listByNamespace(ns);
        if (vars.isEmpty()) {
            sender.sendMessage("§7No variables in namespace: " + ns);
            return;
        }
        sender.sendMessage("§6Namespace '" + ns + "' (" + vars.size() + " variable(s)):");
        for (Variable v : vars) {
            String value = v.currentValue() == null ? "§8(null)" : "§f" + truncate(v.currentValue(), 64);
            String ttl = formatTtl(v.ttl());
            sender.sendMessage("  §7" + v.fullName() + " §8[§e" + v.type() + "§8] §8= "
                    + value + " §8ttl=§7" + ttl);
        }
    }

    private void doGet(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§7Usage: /canvas var get <fullName>");
            return;
        }
        String fullName = args[1].trim();
        Optional<Variable> opt = store.get(fullName);
        if (opt.isEmpty()) {
            sender.sendMessage("§cVariable not found: " + fullName);
            return;
        }
        Variable v = opt.get();
        sender.sendMessage("§6Variable §e" + v.fullName());
        sender.sendMessage("  §7namespace: §f" + v.namespace());
        sender.sendMessage("  §7key:       §f" + v.key());
        sender.sendMessage("  §7type:      §f" + v.type());
        sender.sendMessage("  §7default:   §f" + (v.defaultValue() == null ? "§8(null)" : v.defaultValue()));
        sender.sendMessage("  §7current:   §f" + (v.currentValue() == null ? "§8(null)" : v.currentValue()));
        sender.sendMessage("  §7updated:   §f"
                + (v.updatedAt() == 0L ? "§8(never)" : TS_FMT.format(Instant.ofEpochMilli(v.updatedAt()))));
        sender.sendMessage("  §7ttl:       §f" + formatTtl(v.ttl()));
        sender.sendMessage("  §7source:    §f" + (v.source() == null ? "§8(none)" : v.source()));
        sender.sendMessage("  §7refByWalls:§f" + v.referencedByWalls().size());
    }

    private void doSet(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§7Usage: /canvas var set <fullName> <value...>");
            return;
        }
        String fullName = args[1].trim();
        // args[2..] 拼接成 value（greedyString 已合好，但单测可能传分散，统一兜底）
        StringBuilder sb = new StringBuilder();
        for (int i = 2; i < args.length; i++) {
            if (i > 2) sb.append(' ');
            sb.append(args[i]);
        }
        String value = sb.toString();
        Optional<Variable> existing = store.get(fullName);
        if (existing.isEmpty()) {
            sender.sendMessage("§cVariable not found: " + fullName);
            return;
        }
        store.setValue(fullName, value, null); // null = 沿用原 TTL
        sender.sendMessage("§a✓ Set " + fullName + " = " + truncate(value, 64));
        // AuditLog 仅在装配齐全时写
        if (auditLog != null) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("fullName", fullName);
            details.put("value", truncate(value, 256));
            auditLog.record("VARIABLE_COMMAND_SET",
                    playerUuid(sender), sender.getName(), null, null, details);
        }
    }

    private void doDelete(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§7Usage: /canvas var delete <fullName>");
            return;
        }
        String fullName = args[1].trim();
        if (store.get(fullName).isEmpty()) {
            sender.sendMessage("§cVariable not found: " + fullName);
            return;
        }
        store.delete(fullName);
        sender.sendMessage("§a✓ Deleted " + fullName);
        if (auditLog != null) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("fullName", fullName);
            auditLog.record("VARIABLE_COMMAND_DELETE",
                    playerUuid(sender), sender.getName(), null, null, details);
        }
    }

    private void doProviders(CommandSender sender) {
        List<VariableProvider> providers = daemon.registeredProviders();
        if (providers.isEmpty()) {
            sender.sendMessage("§7No providers registered.");
            return;
        }
        sender.sendMessage("§6Registered providers (" + providers.size() + "):");
        for (VariableProvider p : providers) {
            String interval = formatInterval(p.refreshInterval());
            int keys = p.declaredKeys() == null ? 0 : p.declaredKeys().size();
            sender.sendMessage("  §7" + p.namespace()
                    + " §8'§f" + p.displayName() + "§8' "
                    + (p.isDynamic() ? "§b[dynamic]" : "§a[static]")
                    + " §8keys=§7" + keys
                    + " §8interval=§7" + interval);
        }
    }

    private void doReload(CommandSender sender) {
        PushRateLimiter.Config cfg;
        try {
            cfg = reloadHook.reload();
        } catch (Exception e) {
            sender.sendMessage("§cReload failed: " + e.getMessage());
            return;
        }
        sender.sendMessage("§a✓ Config reloaded. PushRateLimiter:");
        sender.sendMessage("  §7per-plugin:           §f" + cfg.perPluginPerSecond() + "/s");
        sender.sendMessage("  §7global:               §f" + cfg.globalPerSecond() + "/s");
        sender.sendMessage("  §7circuit-break:        §f" + cfg.globalCircuitBreakMs() + "ms");
    }

    private void doInspect(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§7Usage: /canvas var inspect <wallId>");
            return;
        }
        String wallId = args[1].trim();
        if (!walls.exists(wallId)) {
            sender.sendMessage("§eWall not found: " + wallId + " §7(inspecting referenced vars anyway)");
        }
        Set<String> refs = store.referencedFullNamesByWall(wallId);
        if (refs.isEmpty()) {
            sender.sendMessage("§7No variables referenced by wall " + wallId
                    + " §8(may need a render pass to populate)");
            return;
        }
        sender.sendMessage("§6Wall " + wallId + " references " + refs.size() + " variable(s):");
        for (String fn : refs) {
            Optional<Variable> v = store.get(fn);
            String cur = v.map(x -> x.currentValue() == null
                    ? "§8(null)" : "§f" + truncate(x.currentValue(), 48))
                    .orElse("§c(missing from store!)");
            sender.sendMessage("  §7" + fn + " §8= " + cur);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  Suggestion provider helpers（生产路径）
    // ──────────────────────────────────────────────────────────────────

    private CompletableFuture<Suggestions> suggestNamespaces(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder b) {
        for (String s : allNamespaces()) {
            if (matchesRemaining(b, s)) b.suggest(s);
        }
        return b.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestFullNames(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder b) {
        for (String s : allFullNames()) {
            if (matchesRemaining(b, s)) b.suggest(s);
        }
        return b.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestWallIds(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder b) {
        for (String s : allWallIds()) {
            if (matchesRemaining(b, s)) b.suggest(s);
        }
        return b.buildFuture();
    }

    private static boolean matchesRemaining(SuggestionsBuilder b, String candidate) {
        String rem = b.getRemaining();
        return rem == null || rem.isEmpty()
                || candidate.toLowerCase().startsWith(rem.toLowerCase());
    }

    // ──────────────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────────────

    private static void sendUsage(CommandSender sender) {
        sender.sendMessage("§6/canvas var §7subcommands:");
        sender.sendMessage("  §7/canvas var list [namespace]      §8— list namespaces / vars");
        sender.sendMessage("  §7/canvas var get <fullName>        §8— variable metadata");
        sender.sendMessage("  §7/canvas var set <fullName> <val>  §8— set current value");
        sender.sendMessage("  §7/canvas var delete <fullName>     §8— delete variable");
        sender.sendMessage("  §7/canvas var providers             §8— list registered providers");
        sender.sendMessage("  §7/canvas var reload                §8— reload config.yml rate limits");
        sender.sendMessage("  §7/canvas var inspect <wallId>      §8— wall reverse index");
    }

    private static String formatTtl(long ttlMs) {
        if (ttlMs == 0L) return "永久";
        if (ttlMs < 1000) return ttlMs + "ms";
        long s = ttlMs / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "min";
        return (m / 60) + "h";
    }

    private static String formatInterval(Duration d) {
        if (d == null || d.isZero() || d.isNegative()) return "ZERO";
        return formatTtl(d.toMillis());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        // P3-53: max < 3 时 max-3 为负，substring 抛 StringIndexOutOfBoundsException；
        // 钳位：max < 3 直接硬截到 max 长度，不附省略号。
        if (max < 3) return s.substring(0, Math.max(0, max));
        return s.substring(0, max - 3) + "...";
    }

    private static String playerUuid(CommandSender sender) {
        if (sender instanceof Player p) return p.getUniqueId().toString();
        return null;
    }
}
