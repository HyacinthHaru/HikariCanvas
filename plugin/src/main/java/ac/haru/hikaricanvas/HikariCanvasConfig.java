package ac.haru.hikaricanvas;

import ac.haru.hikaricanvas.storage.AuditLog;
import ac.haru.hikaricanvas.variable.VariableStore;
import ac.haru.hikaricanvas.variable.plugin.PushRateLimiter;
import ac.haru.hikaricanvas.web.TokenRateLimiter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 把 {@code plugins/HikariCanvas/config.yml} 解析成强类型对象。
 *
 * <p>读取顺序：插件首次启动时 {@link JavaPlugin#saveDefaultConfig()} 从 jar 内
 * {@code resources/config.yml} 拷一份到 dataFolder；之后每次读 dataFolder 那一份。
 * {@code /canvas reload config} 重新载入并构造新实例。</p>
 *
 * <p><b>不可变：</b> 字段全 {@code public final}；reload 时整体替换引用即可，无需 synchronized。
 * 缺字段全部走代码里的默认值，向后兼容旧 config.yml。</p>
 */
public final class HikariCanvasConfig {

    // ---- network ----
    public final String host;
    public final int port;
    public final String editorUrlTemplate;  // 解析后的最终模板（{host}/{port} 已替换；{token} 留给运行期）
    /** WS 连上后多少秒内未通过 auth 就主动 close 4001（防 idle DoS）。 */
    public final int wsAuthTimeoutSeconds;
    /** WS upgrade 允许的 Origin 白名单（除同源 / 127.0.0.1 / localhost 外的反代域名）。 */
    public final List<String> allowedOrigins;

    // ---- session ----
    public final Duration tokenTtl;
    public final Duration wsGrace;
    public final Duration idleTimeout;
    public final long reaperScanTicks;       // 单位 tick（20 = 1s）
    public final long tokenPurgeTicks;       // 单位 tick

    // ---- map-pool ----
    public final int mapPoolInitial;
    public final int mapPoolMax;
    /**
     * per-world initial 配置（world name → 该 world 至少 FREE 数）。
     * 未配置的 world 走 on-demand 扩容。配置 key = world name（与 server.properties 一致）。
     * 不可变 Map，可能为 empty。
     */
    public final Map<String, Integer> mapPoolPerWorldInitial;

    // ---- throttle ----
    public final int projectionFps;
    public final int inputRatePerSecond;
    public final int inputBurst;

    /**
     * 自适应渲染配置（{@code rendering.adaptive-fps} 段）。
     */
    public final AdaptiveFpsConfig adaptiveFps;

    /**
     * 自适应 fps + 主动推帧配置。
     *
     * @param defaultMinIntervalMs  普通 wall 节流间隔（ms）；默认 200ms = 5fps
     * @param highFreqMinIntervalMs 高频 wall 节流间隔（ms）；默认 50ms = 20fps
     * @param pushPacketsEnabled    是否在每次渲染后主动给 chunk-loaded viewer 推
     *                              ClientboundMapItemDataPacket；false 时仅靠 Paper 默认 sync
     */
    public record AdaptiveFpsConfig(
            long defaultMinIntervalMs,
            long highFreqMinIntervalMs,
            boolean pushPacketsEnabled
    ) {
        public static AdaptiveFpsConfig defaults() {
            return new AdaptiveFpsConfig(200L, 50L, true);
        }
    }

    /**
     * 国际化配置（{@code i18n} 段）。
     */
    public final I18nConfig i18nConfig;

    /**
     * 国际化配置。
     *
     * @param defaultLocale 兜底语言（玩家语言无对应文件时使用）；小写下划线形式，如 {@code en_us}
     */
    public record I18nConfig(String defaultLocale) {
        public static I18nConfig defaults() { return new I18nConfig("en_us"); }
    }

    // ---- templates ----
    public final boolean autoReloadTemplatesOnStartup;
    public final int previewCacheSeconds;
    /** 每玩家可发布模板数；0 = 不限。 */
    public final int templatesMaxPerPlayer;

    // ---- images ----
    public final ImageConfig images;

    /** .canvas 工程导入限额。 */
    public final ImportConfig importConfig;

    // ---- database ----
    /** 跑 schema migration 前是否先备份 data.db（默认 true）。 */
    public final boolean databaseAutoBackup;
    /** 备份保留天数：启动期自动清理超过此天数的 data.db.pre-V&lt;NNN&gt;.bak。≤0 = 永久保留。 */
    public final int backupRetentionDays;
    /**
     * {@code audit_log} 表保留天数（{@code docs/security.md §8.2} / {@code data-model.md §2.6.4}）。
     * ≤0 = 永久保留。清理由 {@link AuditLog} 自己顺带跑，不起调度器。
     */
    public final int auditRetentionDays;

    // ---- dynamic ----
    /**
     * Plugin Push API 限流参数（{@code docs/dynamic-data.md §10.2}）。
     * 配置段 {@code dynamic.push-rate-limit}。
     */
    public final PushRateLimiter.Config pushRateLimitConfig;

    /**
     * 兜底列车 ManualScheduleProvider 配置。
     * 配置段 {@code dynamic.schedule}。
     */
    public final ScheduleConfig scheduleConfig;

    /**
     * 全局用户变量配额 per-owner（默 500）。配置段 {@code dynamic.variables.userglobal-max-per-owner}。
     */
    public final int userGlobalMaxPerOwner;
    /**
     * 全局用户变量配额全服总（默 10000）。配置段 {@code dynamic.variables.userglobal-max-total}。
     */
    public final int userGlobalMaxTotal;

    /**
     * token 暴力枚举防御：每 IP 每分钟可尝试 WS auth 的 token 次数。
     * 配置段 {@code security.token-rate-limit.per-minute}（默 10）。详见
     * {@link TokenRateLimiter}。
     */
    public final int tokenRateLimitPerMinute;

    /**
     * 时间轴帧率参数。配置段 {@code timeline}（docs/architecture.md §5.5 / §11）。
     */
    public final TimelineConfig timelineConfig;

    /**
     * 墙脚本参数。配置段 {@code scripts}（docs/scripting.md §2）。
     */
    public final ScriptsConfig scriptsConfig;

    /**
     * 补间动画引擎参数。配置段 {@code scripts.tween}。
     */
    public final TweenConfig tweenConfig;

    /**
     * 兜底列车时刻表配置。
     *
     * @param arrivingThresholdSeconds 进站阈值（秒）。eta ≤ 阈值时 {@code is_arriving=true} +
     *                                 {@code arrival_status=arrivingText}；默认 60 秒
     *                                 （dynamic-data.md §7.3）
     * @param arrivingText             arrival_status 进站文案，默认 "进站中"
     * @param idleText                 arrival_status 空闲文案，默认空字符串
     */
    public record ScheduleConfig(
            long arrivingThresholdSeconds,
            String arrivingText,
            String idleText
    ) {
        public static ScheduleConfig defaults() {
            return new ScheduleConfig(60L, "进站中", "");
        }
    }

    /**
     * 时间轴帧率参数（docs/architecture.md §5.5；config 段 {@code timeline}）。
     *
     * @param defaultFps 新建 timeline 的默认帧率（默 20 = 一个 Bukkit tick 50ms）
     * @param maxFps     服务器级安全阀：单墙 fps 硬上限，op 层 clamp 每条 timeline 的 fps
     *                   （默 60；管理员保护多租户服务器的总阀门，不做自动降级）
     */
    public record TimelineConfig(int defaultFps, int maxFps) {
        public static TimelineConfig defaults() {
            return new TimelineConfig(20, 60);
        }
    }

    /**
     * per-wall 补间动画引擎参数（config 段 {@code scripts.tween}）。
     *
     * @param maxFps        SES cadence 上限兼 enqueue 时 fps clamp 上界（默 60；admin 保护阀门，不自动降级）
     * @param maxConcurrent 同时活跃补间上限（默 16；超限新补间返 error）
     */
    public record TweenConfig(int maxFps, int maxConcurrent) {
        public static TweenConfig defaults() {
            return new TweenConfig(60, 16);
        }
    }

    /**
     * 墙脚本参数（docs/scripting.md §2 / §2.4；config 段 {@code scripts} +
     * 嵌套 {@code scripts.budget} + {@code scripts.command-templates}）。
     *
     * <p>budget 三闸的另两项（{@code max-delay-depth} / {@code max-delay-ms}）是
     * {@link ac.haru.hikaricanvas.script.ScriptRuleValidator} 静态校验项，不在此重复。</p>
     *
     * @param maxRulesPerWall  单墙脚本规则数上限（默 16；{@code /canvas reload} 热更走
     *                         {@link ac.haru.hikaricanvas.script.ScriptStore#setMaxRulesPerWall}）
     * @param maxActionsPerRun 单次触发展开执行的动作总数（含嵌套；默 50；热更走
     *                         {@code ScriptBudget.applyConfig} 接线）
     * @param maxRunsPerSecond 单规则触发频率上限（超出丢弃 + audit RUN_BLOCKED；默 10）
     * @param maxChainDepth    ABA 熔断链深（脚本写变量→触发别的脚本；默 8）
     * @param commandTemplates runCommand 命令模板白名单（templateId → 模板；
     *                         服主手写进 config，插件不内置任何模板——docs/scripting.md §5.2）。
     *                         热更：执行侧经 supplier 惰性读 volatile config 引用，
     *                         {@code /canvas reload} 后下一次执行即生效
     * @param playerNearSampleTicks playerNear 触发器的采样间隔
     *                         （游戏 tick；默 10 = 0.5s；load 期 clamp 1..200）。热更走
     *                         {@code PlayerNearSampler.setSampleTicks}（volatile 跳帧计数，
     *                         无需重 schedule）；实际分辨率受底层 task 周期 2 tick 限制
     * @param maxElementsPerWall 单墙元素总数上限（脚本"克隆元素"积木反复
     *                         克隆是唯一能无界堆元素的入口；默 200）。热更：confirm/open 新建
     *                         EditSession 时透传 + headless 经
     *                         {@code ElementPropertyApplier.setMaxElementsPerWall}，
     *                         {@code /canvas reload} 后下一次克隆即生效
     */
    public record ScriptsConfig(int maxRulesPerWall, int maxActionsPerRun,
                                int maxRunsPerSecond, int maxChainDepth,
                                Map<String, CommandTemplate> commandTemplates,
                                int playerNearSampleTicks,
                                int maxElementsPerWall) {
        /** playerNear 采样间隔默认值（tick）；压测后可能回调（docs/scripting.md §10）。 */
        public static final int DEFAULT_PLAYER_NEAR_SAMPLE_TICKS = 10;

        /** 单墙元素总数上限默认值。 */
        public static final int DEFAULT_MAX_ELEMENTS_PER_WALL = 200;

        public ScriptsConfig {
            commandTemplates = commandTemplates == null
                    ? Map.of() : Map.copyOf(commandTemplates);
        }

        /** 预算参数便捷构造（空命令模板表 + 默认采样间隔与元素上限）。 */
        public ScriptsConfig(int maxRulesPerWall, int maxActionsPerRun,
                             int maxRunsPerSecond, int maxChainDepth) {
            this(maxRulesPerWall, maxActionsPerRun, maxRunsPerSecond, maxChainDepth,
                    Map.of(), DEFAULT_PLAYER_NEAR_SAMPLE_TICKS,
                    DEFAULT_MAX_ELEMENTS_PER_WALL);
        }

        public static ScriptsConfig defaults() {
            return new ScriptsConfig(16, 50, 10, 8,
                    Map.of(), DEFAULT_PLAYER_NEAR_SAMPLE_TICKS,
                    DEFAULT_MAX_ELEMENTS_PER_WALL);
        }
    }

    /**
     * runCommand 命令模板（docs/scripting.md §5.2）。
     *
     * @param command 命令全文，可含 {@code {param}} 占位符（不带前导 {@code /}，
     *                执行侧会防御性剥掉一个）
     * @param params  参数名 → 校验规格；只有声明过的参数会被替换进命令
     */
    public record CommandTemplate(String command, Map<String, ParamSpec> params) {
        public CommandTemplate {
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }

    /**
     * 命令模板单参数规格。
     *
     * @param maxLength 替换值长度上限（默 64；config 解析期 clamp ≥1）
     * @param type      {@code "text"}（默；值含 {@code @} 整体拒）或
     *                  {@code "online-player"}（值必须精确命中在线玩家名）
     */
    public record ParamSpec(int maxLength, String type) {
        /** online-player 类型常量（其余字符串一律按 text 处理）。 */
        public static final String TYPE_ONLINE_PLAYER = "online-player";
        public static final String TYPE_TEXT = "text";
        public static final int DEFAULT_MAX_LENGTH = 64;

        public static ParamSpec defaults() {
            return new ParamSpec(DEFAULT_MAX_LENGTH, TYPE_TEXT);
        }
    }

    public record ImageConfig(
            int maxSizeKb,
            List<String> allowedMime,
            int downscaleMaxEdge,
            int maxPerWall,
            int maxUploadsPerDay,
            int maxTotalStorageMb
    ) {
        public static ImageConfig defaults() {
            return new ImageConfig(2048,
                    List.of("image/png", "image/jpeg", "image/webp"),
                    1024, 16, 50, 1024);
        }
    }

    /**
     * .canvas 工程导入限额（防恶意大文件 / zip 炸弹）。
     * 单位 MB；解包闸把 MB × 1024×1024 换算为字节上限。defaults 全正，保证三闸有效。
     */
    public record ImportConfig(int canvasMaxMb, int canvasMaxEntryMb, int canvasMaxTotalMb) {
        public static ImportConfig defaults() {
            return new ImportConfig(10, 10, 50);
        }
    }

    private HikariCanvasConfig(Builder b) {
        this.host = b.host;
        this.port = b.port;
        this.editorUrlTemplate = b.editorUrlTemplate;
        this.wsAuthTimeoutSeconds = b.wsAuthTimeoutSeconds;
        this.allowedOrigins = b.allowedOrigins;
        this.tokenTtl = b.tokenTtl;
        this.wsGrace = b.wsGrace;
        this.idleTimeout = b.idleTimeout;
        this.reaperScanTicks = b.reaperScanTicks;
        this.tokenPurgeTicks = b.tokenPurgeTicks;
        this.mapPoolInitial = b.mapPoolInitial;
        this.mapPoolMax = b.mapPoolMax;
        this.mapPoolPerWorldInitial = b.mapPoolPerWorldInitial;
        this.projectionFps = b.projectionFps;
        this.inputRatePerSecond = b.inputRatePerSecond;
        this.inputBurst = b.inputBurst;
        this.adaptiveFps = b.adaptiveFps;
        this.i18nConfig = b.i18nConfig;
        this.autoReloadTemplatesOnStartup = b.autoReloadTemplatesOnStartup;
        this.previewCacheSeconds = b.previewCacheSeconds;
        this.templatesMaxPerPlayer = b.templatesMaxPerPlayer;
        this.images = b.images;
        this.importConfig = b.importConfig;
        this.databaseAutoBackup = b.databaseAutoBackup;
        this.backupRetentionDays = b.backupRetentionDays;
        this.auditRetentionDays = b.auditRetentionDays;
        this.pushRateLimitConfig = b.pushRateLimitConfig;
        this.scheduleConfig = b.scheduleConfig;
        this.userGlobalMaxPerOwner = b.userGlobalMaxPerOwner;
        this.userGlobalMaxTotal = b.userGlobalMaxTotal;
        this.tokenRateLimitPerMinute = b.tokenRateLimitPerMinute;
        this.timelineConfig = b.timelineConfig;
        this.scriptsConfig = b.scriptsConfig;
        this.tweenConfig = b.tweenConfig;
    }

    /**
     * 从 plugin 当前的 {@link FileConfiguration} 读取。任何缺失字段走默认值。
     * 启动期或 {@code /canvas reload config} 调；调用前应先 {@code plugin.reloadConfig()}。
     */
    public static HikariCanvasConfig load(JavaPlugin plugin) {
        return fromConfiguration(plugin.getConfig(), plugin.getLogger());
    }

    static HikariCanvasConfig fromConfiguration(FileConfiguration f, Logger logger) {
        Builder b = new Builder();
        b.host = f.getString("network.host", b.host);
        warnIfPublicBind(b.host, logger);
        b.port = f.getInt("network.port", b.port);
        String urlTemplate = f.getString("network.editor-url",
                "http://{host}:{port}/?token={token}");
        // editor-url 协议白名单：仅允许 http(s)，不合法回退到默认并记录错误。
        b.editorUrlTemplate = sanitizeEditorUrl(urlTemplate, b.host, b.port, logger);

        // 网络层安全收口
        b.wsAuthTimeoutSeconds = Math.max(1, Math.min(60, f.getInt("network.ws-auth-timeout-seconds", 5)));
        b.allowedOrigins = readAllowedOrigins(f, logger);

        // token-ttl / ws-grace 下限钳到 1 分钟（不同于 idle-minutes，0 不代表"永不超时"；
        // 配 ≤0 会导致签发后会话立即被 SessionReaper 回收，故回退默认值并打 warning）。
        b.tokenTtl = Duration.ofMinutes(clampMinutes(f, "session.token-ttl-minutes", 15, logger));
        b.wsGrace = Duration.ofMinutes(clampMinutes(f, "session.ws-grace-minutes", 5, logger));
        long idleMin = f.getLong("session.idle-minutes", 30);
        b.idleTimeout = idleMin <= 0 ? Duration.ofDays(365 * 100) : Duration.ofMinutes(idleMin);
        b.reaperScanTicks = 20L * Math.max(5, f.getLong("session.reaper-scan-seconds", 30));
        b.tokenPurgeTicks = 20L * 60 * Math.max(1, f.getLong("session.token-purge-minutes", 5));

        // map-pool.initial 下限钳到 1（MapPool 要求 initialSize > 0），
        // 上限 1024 与 docs/data-model.md §11 声明的 range 1-1024 对齐。
        b.mapPoolInitial = Math.max(1, Math.min(1024, f.getInt("map-pool.initial", 64)));
        b.mapPoolMax = Math.max(b.mapPoolInitial, f.getInt("map-pool.max", 256));
        // 可选 per-world 初始分配。yml 形如：
        //   map-pool:
        //     per-world:
        //       world: 32
        //       world_nether: 8
        //       world_the_end: 4
        // 未列出的 world 走 on-demand 扩容；负值 / 非整数 clamp 到 0。
        ConfigurationSection pw = f.getConfigurationSection("map-pool.per-world");
        if (pw != null) {
            Map<String, Integer> parsed = new LinkedHashMap<>();
            for (String k : pw.getKeys(false)) {
                int v = Math.max(0, pw.getInt(k, 0));
                if (v > 0) parsed.put(k, v);
            }
            b.mapPoolPerWorldInitial = Map.copyOf(parsed);
        }

        b.projectionFps = Math.max(1, Math.min(30, f.getInt("throttle.projection-fps", 5)));
        b.inputRatePerSecond = Math.max(1, f.getInt("throttle.input-rate-per-second", 20));
        b.inputBurst = Math.max(b.inputRatePerSecond, f.getInt("throttle.input-burst", 40));

        // 自适应渲染段：throttle.projection-fps 推算 default-min-interval-ms 的缺省值（1000/fps）；
        // 显式写了 rendering.adaptive-fps.default-min-interval-ms 时以显式值为准。
        b.adaptiveFps = resolveAdaptiveFps(
                f.getConfigurationSection("rendering.adaptive-fps"), b.projectionFps);

        // i18n 段 —— 国际化兜底语言
        ConfigurationSection i18nSec = f.getConfigurationSection("i18n");
        if (i18nSec == null) {
            b.i18nConfig = I18nConfig.defaults();
        } else {
            String def = i18nSec.getString("default-locale", "en_us")
                    .toLowerCase(Locale.ROOT).replace('-', '_');
            b.i18nConfig = new I18nConfig(def);
        }

        b.autoReloadTemplatesOnStartup = f.getBoolean("templates.auto-reload-on-startup", true);
        b.previewCacheSeconds = Math.max(0, f.getInt("templates.preview-cache-seconds", 300));
        b.templatesMaxPerPlayer = Math.max(0, f.getInt("templates.max-per-player", 20));

        // images 段
        ImageConfig defaults = ImageConfig.defaults();
        @SuppressWarnings("unchecked")
        List<String> mimes = (List<String>) f.getList(
                "images.allowed-mime", defaults.allowedMime());
        b.images = new ImageConfig(
                Math.max(1, f.getInt("images.max-size-kb", defaults.maxSizeKb())),
                mimes == null || mimes.isEmpty() ? defaults.allowedMime() : mimes,
                Math.max(64, f.getInt("images.downscale-max-edge", defaults.downscaleMaxEdge())),
                Math.max(0, f.getInt("images.max-per-wall", defaults.maxPerWall())),
                Math.max(0, f.getInt("images.max-uploads-per-day", defaults.maxUploadsPerDay())),
                Math.max(0, f.getInt("images.max-total-storage-mb", defaults.maxTotalStorageMb())));

        // import 段（.canvas 工程导入限额）
        ImportConfig importDefaults = ImportConfig.defaults();
        b.importConfig = new ImportConfig(
                Math.max(1, f.getInt("import.canvas-max-mb", importDefaults.canvasMaxMb())),
                Math.max(1, f.getInt("import.canvas-max-entry-mb", importDefaults.canvasMaxEntryMb())),
                Math.max(1, f.getInt("import.canvas-max-total-mb", importDefaults.canvasMaxTotalMb())));

        // database 段：备份 WAL 安全，默认打开。
        b.databaseAutoBackup = f.getBoolean("database.auto-backup-before-migration", true);
        b.backupRetentionDays = f.getInt("database.backup-retention-days", 30);
        b.auditRetentionDays = f.getInt("database.audit-retention-days",
                AuditLog.DEFAULT_RETENTION_DAYS);

        // dynamic.push-rate-limit 段
        ConfigurationSection rate =
                f.getConfigurationSection("dynamic.push-rate-limit");
        PushRateLimiter.Config rateDefaults =
                PushRateLimiter.Config.defaults();
        if (rate == null) {
            b.pushRateLimitConfig = rateDefaults;
        } else {
            int perPlugin = Math.max(1,
                    rate.getInt("per-plugin-per-second", rateDefaults.perPluginPerSecond()));
            int global = Math.max(perPlugin,
                    rate.getInt("global-per-second", rateDefaults.globalPerSecond()));
            long breakMs = Math.max(0L,
                    rate.getLong("global-circuit-break-ms", rateDefaults.globalCircuitBreakMs()));
            b.pushRateLimitConfig = new PushRateLimiter.Config(
                    perPlugin, global, breakMs);
        }

        // dynamic.schedule 段
        ConfigurationSection schedSec =
                f.getConfigurationSection("dynamic.schedule");
        ScheduleConfig schedDefaults = ScheduleConfig.defaults();
        if (schedSec == null) {
            b.scheduleConfig = schedDefaults;
        } else {
            long threshold = Math.max(0L,
                    schedSec.getLong("arriving-threshold-seconds",
                            schedDefaults.arrivingThresholdSeconds()));
            String arrivingText = schedSec.getString("arriving-text",
                    schedDefaults.arrivingText());
            String idleText = schedSec.getString("idle-text", schedDefaults.idleText());
            b.scheduleConfig = new ScheduleConfig(
                    threshold,
                    arrivingText == null ? schedDefaults.arrivingText() : arrivingText,
                    idleText == null ? schedDefaults.idleText() : idleText);
        }

        // dynamic.variables 段 — 全局用户变量配额（缺则用 VariableStore 默认值）
        ConfigurationSection varSec =
                f.getConfigurationSection("dynamic.variables");
        if (varSec != null) {
            b.userGlobalMaxPerOwner = Math.max(1, varSec.getInt(
                    "userglobal-max-per-owner",
                    VariableStore.DEFAULT_USERGLOBAL_PER_OWNER));
            b.userGlobalMaxTotal = Math.max(1, varSec.getInt(
                    "userglobal-max-total",
                    VariableStore.DEFAULT_USERGLOBAL_TOTAL));
        }

        // security.token-rate-limit 段 —— token 暴力枚举防御
        ConfigurationSection rlSec =
                f.getConfigurationSection("security.token-rate-limit");
        if (rlSec != null) {
            b.tokenRateLimitPerMinute = Math.max(1, rlSec.getInt(
                    "per-minute",
                    TokenRateLimiter.DEFAULT_PER_MINUTE));
        }

        // timeline 段 —— 时间轴帧率参数（default-fps 钳到 max-fps 之内）
        ConfigurationSection tlSec =
                f.getConfigurationSection("timeline");
        TimelineConfig tlDefaults = TimelineConfig.defaults();
        if (tlSec == null) {
            b.timelineConfig = tlDefaults;
        } else {
            int maxFps = Math.max(1, tlSec.getInt("max-fps", tlDefaults.maxFps()));
            int defFps = Math.min(maxFps,
                    Math.max(1, tlSec.getInt("default-fps", tlDefaults.defaultFps())));
            b.timelineConfig = new TimelineConfig(defFps, maxFps);
        }

        // scripts 段 —— 墙脚本参数
        ConfigurationSection scSec =
                f.getConfigurationSection("scripts");
        ScriptsConfig scDefaults = ScriptsConfig.defaults();
        if (scSec == null) {
            b.scriptsConfig = scDefaults;
        } else {
            int maxRules = Math.max(1, scSec.getInt(
                    "max-rules-per-wall", scDefaults.maxRulesPerWall()));
            // scripts.budget 嵌套段（缺段全走默认值）
            ConfigurationSection budSec =
                    scSec.getConfigurationSection("budget");
            int maxActions = scDefaults.maxActionsPerRun();
            int maxRuns = scDefaults.maxRunsPerSecond();
            int maxChain = scDefaults.maxChainDepth();
            if (budSec != null) {
                maxActions = Math.max(1, budSec.getInt("max-actions-per-run", maxActions));
                maxRuns = Math.max(1, budSec.getInt("max-runs-per-second", maxRuns));
                maxChain = Math.max(1, budSec.getInt("max-chain-depth", maxChain));
            }
            // scripts.command-templates 段（docs/scripting.md §5.2）。
            // 形如：
            //   command-templates:
            //     announce:
            //       command: "say [招牌] {msg}"
            //       params:
            //         msg: { max-length: 64 }
            // command 缺失 / 空白的模板条目跳过 + severe（服主写错要看得见）。
            Map<String, CommandTemplate> templates = new LinkedHashMap<>();
            ConfigurationSection ctSec =
                    scSec.getConfigurationSection("command-templates");
            if (ctSec != null) {
                for (String tplId : ctSec.getKeys(false)) {
                    ConfigurationSection tplSec =
                            ctSec.getConfigurationSection(tplId);
                    if (tplSec == null) {
                        logger.severe("config.yml: scripts.command-templates." + tplId
                                + " must be a section with a 'command' field; skipping template.");
                        continue;
                    }
                    String command = tplSec.getString("command", "");
                    if (command == null || command.isBlank()) {
                        logger.severe("config.yml: scripts.command-templates." + tplId
                                + ".command is missing or blank; skipping template.");
                        continue;
                    }
                    Map<String, ParamSpec> params = new LinkedHashMap<>();
                    ConfigurationSection psSec =
                            tplSec.getConfigurationSection("params");
                    if (psSec != null) {
                        for (String pName : psSec.getKeys(false)) {
                            ConfigurationSection pSec =
                                    psSec.getConfigurationSection(pName);
                            int maxLen = ParamSpec.DEFAULT_MAX_LENGTH;
                            String type = ParamSpec.TYPE_TEXT;
                            if (pSec != null) {
                                maxLen = Math.max(1, pSec.getInt("max-length", maxLen));
                                String rawType = pSec.getString("type", type);
                                if (ParamSpec.TYPE_ONLINE_PLAYER.equals(rawType)) {
                                    type = ParamSpec.TYPE_ONLINE_PLAYER;
                                } else if (!ParamSpec.TYPE_TEXT.equals(rawType)) {
                                    logger.warning("config.yml: scripts.command-templates."
                                            + tplId + ".params." + pName + ".type has unknown value '"
                                            + rawType + "' (expected 'text' or 'online-player'); "
                                            + "defaulting to 'text'.");
                                }
                            }
                            params.put(pName, new ParamSpec(maxLen, type));
                        }
                    }
                    templates.put(tplId, new CommandTemplate(command.trim(), params));
                }
            }
            // playerNear 采样间隔（tick；clamp 1..200——下限防主线程
            // 每 tick 全量扫，上限 10s 再迟钝就失去"靠近"语义）。
            int nearTicks = Math.min(200, Math.max(1, scSec.getInt(
                    "player-near-sample-ticks",
                    ScriptsConfig.DEFAULT_PLAYER_NEAR_SAMPLE_TICKS)));
            // 单墙元素总数上限（脚本克隆元素强制）。clamp ≥1。
            int maxElements = Math.max(1, scSec.getInt("max-elements-per-wall",
                    ScriptsConfig.DEFAULT_MAX_ELEMENTS_PER_WALL));
            b.scriptsConfig = new ScriptsConfig(maxRules, maxActions, maxRuns, maxChain,
                    templates, nearTicks, maxElements);
            // scripts.tween 嵌套段——补间引擎 maxFps / maxConcurrent
            ConfigurationSection tweenSec =
                    scSec.getConfigurationSection("tween");
            if (tweenSec != null) {
                TweenConfig td = TweenConfig.defaults();
                int tMaxFps = Math.max(1, tweenSec.getInt("max-fps", td.maxFps()));
                int tMaxConc = Math.max(1, tweenSec.getInt("max-concurrent", td.maxConcurrent()));
                b.tweenConfig = new TweenConfig(tMaxFps, tMaxConc);
            }
        }

        return new HikariCanvasConfig(b);
    }

    /**
     * editor-url 协议白名单。
     *
     * <p>解析步骤：</p>
     * <ol>
     *   <li>占位符 {@code {host}/{port}} 替换</li>
     *   <li>拒绝含 CR/LF（防注入额外协议或 header）</li>
     *   <li>{@code URI.create()} 解析；scheme 必须存在且为 {@code http/https}（大小写不敏感）</li>
     * </ol>
     *
     * <p>任一步失败 → log.severe 并回退默认 {@code http://{host}:{port}/?token={token}}。
     * 不抛异常，不阻塞启动；URL 是用户体验功能，回退到默认仍可用。</p>
     */
    static String sanitizeEditorUrl(String template, String host, int port, Logger logger) {
        String fallback = "http://" + host + ":" + port + "/?token={token}";
        if (template == null || template.isBlank()) {
            return fallback;
        }
        String resolved = template
                .replace("{host}", host)
                .replace("{port}", String.valueOf(port));
        // CR/LF 注入防御：合法 url 不应含换行
        if (resolved.indexOf('\n') >= 0 || resolved.indexOf('\r') >= 0) {
            logger.severe("config.yml: network.editor-url must not contain line breaks; "
                    + "falling back to default (" + fallback + ").");
            return fallback;
        }
        // {token} 是运行期替换占位符；URI 解析期为了拿 scheme，先用占位字符串替代
        String parseTarget = resolved.replace("{token}", "PLACEHOLDER");
        String scheme;
        try {
            URI uri = URI.create(parseTarget);
            scheme = uri.getScheme();
        } catch (IllegalArgumentException e) {
            logger.severe("config.yml: network.editor-url is not a valid URL (" + e.getMessage()
                    + "); falling back to default (" + fallback + ").");
            return fallback;
        }
        if (scheme == null) {
            logger.severe("config.yml: network.editor-url must start with http:// or https://; "
                    + "falling back to default (" + fallback + ").");
            return fallback;
        }
        String lower = scheme.toLowerCase(Locale.ROOT);
        if (!lower.equals("http") && !lower.equals("https")) {
            logger.severe("config.yml: network.editor-url uses unsupported protocol '" + scheme
                    + "' (only http:// and https:// are allowed); falling back to default ("
                    + fallback + ").");
            return fallback;
        }
        return resolved;
    }

    /**
     * 服主把 {@code network.host} 显式绑成公网通配地址（{@code 0.0.0.0} / {@code ::}）时，
     * 启动期打 3 行警告。web 编辑器默认无 TLS，公网裸绑会让 auth token 明文暴露在网络上。
     *
     * <p>只警告、不阻拦——服主有正当理由（如反代在另一台机器）时仍可绑公网。
     * 控制台日志走英文（与 {@link #sanitizeEditorUrl} 一致）。</p>
     */
    static void warnIfPublicBind(String host, Logger logger) {
        if (host == null) return;
        String h = host.trim();
        boolean publicBind = h.equals("0.0.0.0")
                || h.equals("::") || h.equals("[::]")
                || h.equals("*") || h.isEmpty();
        if (!publicBind) return;
        logger.warning("============================================================");
        logger.warning("config.yml: network.host is bound to '" + host
                + "' (a public interface). The web editor has no built-in TLS, so edit "
                + "tokens will travel in plaintext over the network.");
        logger.warning("For public servers, bind 127.0.0.1 and put an HTTPS reverse proxy "
                + "(Caddy or nginx) in front. See the deployment guide.");
    }

    /**
     * 解析 {@code rendering.adaptive-fps} 段，缺省值由 {@code throttle.projection-fps} 推算。
     *
     * <p>{@code projection-fps} 推算 {@code default-min-interval-ms} 的<b>默认值</b>
     * （{@code 1000/fps}；默认 5fps 即 200ms），服主若显式写了 {@code default-min-interval-ms}
     * 则以显式值为准。显式 &gt; 推算，不做隐式自动调优。</p>
     *
     * @param adaptSec      配置段；{@code null} = 整段缺失，全走推算 / 缺省
     * @param projectionFps 已钳位到 [1,30] 的 {@code throttle.projection-fps}
     */
    static AdaptiveFpsConfig resolveAdaptiveFps(
            ConfigurationSection adaptSec, int projectionFps) {
        AdaptiveFpsConfig adaptDefaults = AdaptiveFpsConfig.defaults();
        long derived = Math.max(33L, 1000L / Math.max(1, projectionFps));
        if (adaptSec == null) {
            return new AdaptiveFpsConfig(
                    derived,
                    Math.min(adaptDefaults.highFreqMinIntervalMs(), derived),
                    adaptDefaults.pushPacketsEnabled());
        }
        long defMs = Math.max(33L, adaptSec.getLong("default-min-interval-ms", derived));
        long highMs = Math.max(33L,
                adaptSec.getLong("high-freq-min-interval-ms", adaptDefaults.highFreqMinIntervalMs()));
        // 高频间隔不该比默认还大；交叉配置时取小者保护用户意图
        if (highMs > defMs) highMs = defMs;
        boolean pushOn = adaptSec.getBoolean("push-packets-enabled",
                adaptDefaults.pushPacketsEnabled());
        return new AdaptiveFpsConfig(defMs, highMs, pushOn);
    }

    /**
     * 读 {@code network.allowed-origins}，逐元素校验后返回不可变列表。
     *
     * <p>非字符串或空白元素（如 YAML 空项 {@code - ~} 或数字 {@code - 443}）跳过并打 warning，
     * 避免 {@code List.copyOf} 抛 NPE 或运行期遍历时抛 {@code ClassCastException}。</p>
     */
    static List<String> readAllowedOrigins(FileConfiguration f, Logger logger) {
        List<?> raw = f.getList("network.allowed-origins", List.of());
        if (raw == null || raw.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            if (o instanceof String s && !s.isBlank()) {
                out.add(s.trim());
            } else {
                String badValue = (o == null) ? "empty value"
                        : (o instanceof String ? "blank string" : "'" + o + "'");
                logger.warning("config.yml: network.allowed-origins contains an invalid entry ("
                        + badValue + "); skipping. Each entry must be a quoted URL origin such as "
                        + "\"https://canvas.example.com\".");
            }
        }
        return List.copyOf(out);
    }

    /**
     * 读一个「分钟数」配置项并把下限钳到 1。
     *
     * <p>{@code session.token-ttl-minutes} / {@code session.ws-grace-minutes} 都没有
     * 「0 = 关闭」的语义（隔壁 {@code idle-minutes} 才有），但服主很容易照着隔壁写 0，
     * 而 0 会让整条确认流程静默瘫痪。越界时钳回默认值并 warning，服主至少能在日志里看到。</p>
     */
    static long clampMinutes(FileConfiguration f, String path, long def, Logger logger) {
        long v = f.getLong(path, def);
        if (v >= 1) return v;
        logger.warning("config.yml: " + path + "=" + v + " must be at least 1 minute; "
                + "using default " + def
                + " (unlike session.idle-minutes, 0 does not disable expiration).");
        return def;
    }

    /** 开服配置摘要（监听地址、地图池容量、刷新间隔、会话超时与默认语言）。 */
    public String summary() {
        return String.format(
                "listen=%s:%d, map-pool=%d..%d, refresh=%dms (high-freq=%dms), "
                        + "token-ttl=%dm, idle-timeout=%s, default-locale=%s",
                host, port,
                mapPoolInitial, mapPoolMax,
                adaptiveFps.defaultMinIntervalMs(), adaptiveFps.highFreqMinIntervalMs(),
                tokenTtl.toMinutes(),
                idleTimeout.toDays() > 30 ? "disabled" : (idleTimeout.toMinutes() + "m"),
                i18nConfig.defaultLocale());
    }

    /**
     * 热重载配置摘要：仅汇总 {@code /canvas reload config} 当场热更生效的项，
     * 避免在重载时打印需要重启才生效的端口、地图池等配置误导服主。
     */
    public String reloadSummary() {
        return String.format(
                "default-locale=%s, scripts.max-rules=%d, scripts.max-elements=%d, "
                        + "scripts.near-sample=%dt, scripts.budget=%d/%ds/depth-%d, command-templates=%d",
                i18nConfig.defaultLocale(),
                scriptsConfig.maxRulesPerWall(),
                scriptsConfig.maxElementsPerWall(),
                scriptsConfig.playerNearSampleTicks(),
                scriptsConfig.maxActionsPerRun(),
                scriptsConfig.maxRunsPerSecond(),
                scriptsConfig.maxChainDepth(),
                scriptsConfig.commandTemplates().size());
    }

    /** 字段默认值的初始化容器；load() 内部用。 */
    private static final class Builder {
        String host = "127.0.0.1";
        int port = 8877;
        String editorUrlTemplate = "http://127.0.0.1:8877/?token={token}";
        int wsAuthTimeoutSeconds = 5;
        List<String> allowedOrigins = List.of();
        Duration tokenTtl = Duration.ofMinutes(15);
        Duration wsGrace = Duration.ofMinutes(5);
        Duration idleTimeout = Duration.ofMinutes(30);
        long reaperScanTicks = 20L * 30;
        long tokenPurgeTicks = 20L * 60 * 5;
        int mapPoolInitial = 64;
        int mapPoolMax = 256;
        Map<String, Integer> mapPoolPerWorldInitial = Map.of();
        int projectionFps = 5;
        int inputRatePerSecond = 20;
        int inputBurst = 40;
        boolean autoReloadTemplatesOnStartup = true;
        int previewCacheSeconds = 300;
        int templatesMaxPerPlayer = 20;
        ImageConfig images = ImageConfig.defaults();
        ImportConfig importConfig = ImportConfig.defaults();
        boolean databaseAutoBackup = true;
        int backupRetentionDays = 30;
        int auditRetentionDays = AuditLog.DEFAULT_RETENTION_DAYS;
        PushRateLimiter.Config pushRateLimitConfig = PushRateLimiter.Config.defaults();
        ScheduleConfig scheduleConfig = ScheduleConfig.defaults();
        AdaptiveFpsConfig adaptiveFps = AdaptiveFpsConfig.defaults();
        I18nConfig i18nConfig = I18nConfig.defaults();
        int userGlobalMaxPerOwner = VariableStore.DEFAULT_USERGLOBAL_PER_OWNER;
        int userGlobalMaxTotal = VariableStore.DEFAULT_USERGLOBAL_TOTAL;
        int tokenRateLimitPerMinute = TokenRateLimiter.DEFAULT_PER_MINUTE;
        TimelineConfig timelineConfig = TimelineConfig.defaults();
        ScriptsConfig scriptsConfig = ScriptsConfig.defaults();
        TweenConfig tweenConfig = TweenConfig.defaults();
    }
}
