package moe.hikari.canvas;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.time.Duration;
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
    /** M16 P1.2：WS 连上后多少秒内未通过 auth 就主动 close 4001（防 idle DoS）。 */
    public final int wsAuthTimeoutSeconds;
    /** M16 P1.3：WS upgrade 允许的 Origin 白名单（除同源 / 127.0.0.1 / localhost 外的反代域名）。 */
    public final java.util.List<String> allowedOrigins;

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
     * M16 P2.3：per-world initial 配置（world name → 该 world 至少 FREE 数）。
     * 未配置的 world 走 on-demand 扩容。配置 key = world name（与 server.properties 一致）。
     * 不可变 Map，可能为 empty。
     */
    public final java.util.Map<String, Integer> mapPoolPerWorldInitial;

    // ---- throttle ----
    public final int projectionFps;
    public final int inputRatePerSecond;
    public final int inputBurst;

    /**
     * 0.4.0 方案 B 自适应渲染配置（{@code rendering.adaptive-fps} 段）。
     */
    public final AdaptiveFpsConfig adaptiveFps;

    /**
     * 0.4.0 方案 B：自适应 fps + 主动推帧配置。
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
     * 0.8.2：国际化配置（{@code i18n} 段）。
     */
    public final I18nConfig i18nConfig;

    /**
     * 0.8.2：国际化配置。
     *
     * @param defaultLocale 兜底语言（玩家语言无对应文件时使用）；小写下划线形式，如 {@code en_us}
     */
    public record I18nConfig(String defaultLocale) {
        public static I18nConfig defaults() { return new I18nConfig("en_us"); }
    }

    // ---- templates ----
    public final boolean autoReloadTemplatesOnStartup;
    public final int previewCacheSeconds;
    /** M14 创意工坊：每玩家可发布模板数；0 = 不限。 */
    public final int templatesMaxPerPlayer;

    // ---- images (M13) ----
    public final ImageConfig images;

    /** 0.8 .canvas 工程导入限额。 */
    public final ImportConfig importConfig;

    // ---- database (M15.4) ----
    /** 跑 schema migration 前是否先备份 data.db。pre-release 默认 false。 */
    public final boolean databaseAutoBackup;
    /** 备份保留天数：启动期自动清理超过此天数的 data.db.pre-V&lt;NNN&gt;.bak。≤0 = 永久保留。 */
    public final int backupRetentionDays;

    // ---- dynamic (0.4.0-P4-P) ----
    /**
     * 0.4.0-P4-P：Plugin Push API 限流参数（{@code docs/dynamic-data.md §10.2}）。
     * 配置段 {@code dynamic.push-rate-limit}。
     */
    public final moe.hikari.canvas.variable.plugin.PushRateLimiter.Config pushRateLimitConfig;

    /**
     * 0.4.0 bugfix（Bug 3）：兜底列车 ManualScheduleProvider 配置。
     * 配置段 {@code dynamic.schedule}。
     */
    public final ScheduleConfig scheduleConfig;

    /**
     * 0.4.3：全局用户变量配额 per-owner（默 500）。配置段 {@code dynamic.variables.userglobal-max-per-owner}。
     */
    public final int userGlobalMaxPerOwner;
    /**
     * 0.4.3：全局用户变量配额全服总（默 10000）。配置段 {@code dynamic.variables.userglobal-max-total}。
     */
    public final int userGlobalMaxTotal;

    /**
     * 2026-05-25 token 暴力枚举防御：每 IP 每分钟可尝试 WS auth 的 token 次数。
     * 配置段 {@code security.token-rate-limit.per-minute}（默 10）。详见
     * {@link moe.hikari.canvas.web.TokenRateLimiter}。
     */
    public final int tokenRateLimitPerMinute;

    /**
     * 0.6：时间轴帧率参数。配置段 {@code timeline}（docs/architecture.md §5.5 / §11）。
     */
    public final TimelineConfig timelineConfig;

    /**
     * 0.7：墙脚本参数。配置段 {@code scripts}（docs/scripting.md §2）。
     */
    public final ScriptsConfig scriptsConfig;

    /**
     * 0.7.1：补间动画引擎参数。配置段 {@code scripts.tween}。
     */
    public final TweenConfig tweenConfig;

    /**
     * 0.4.0 bugfix（Bug 3）：兜底列车时刻表配置。
     *
     * @param arrivingThresholdSeconds 进站阈值（秒）。eta ≤ 阈值时 {@code is_arriving=true} +
     *                                 {@code arrival_status=arrivingText}；默认 60 秒（dynamic-data.md
     *                                 §7.3 原写 5min 已废，秒粒度更符合实际列车业务）
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
     * 0.6：时间轴帧率参数（docs/architecture.md §5.5；config 段 {@code timeline}）。
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
     * 0.7.1：per-wall 补间动画引擎参数（config 段 {@code scripts.tween}）。
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
     * 0.7：墙脚本参数（docs/scripting.md §2 / §2.4；config 段 {@code scripts} +
     * 嵌套 {@code scripts.budget} + {@code scripts.command-templates}）。
     *
     * <p>budget 三闸的另两项（{@code max-delay-depth} / {@code max-delay-ms}）是
     * {@link moe.hikari.canvas.script.ScriptRuleValidator} 静态校验项，不在此重复。</p>
     *
     * @param maxRulesPerWall  单墙脚本规则数上限（默 16；{@code /canvas reload} 热更走
     *                         {@link moe.hikari.canvas.script.ScriptStore#setMaxRulesPerWall}）
     * @param maxActionsPerRun 单次触发展开执行的动作总数（含嵌套；默 50；热更走
     *                         {@code ScriptBudget.applyConfig}，批次 3 接线）
     * @param maxRunsPerSecond 单规则触发频率上限（超出丢弃 + audit RUN_BLOCKED；默 10）
     * @param maxChainDepth    ABA 熔断链深（脚本写变量→触发别的脚本；默 8）
     * @param commandTemplates 0.7.0-P3 A1：runCommand 命令模板白名单（templateId → 模板；
     *                         服主手写进 config，插件不内置任何模板——docs/scripting.md §5.2）。
     *                         热更：执行侧经 supplier 惰性读 volatile config 引用，
     *                         {@code /canvas reload} 后下一次执行即生效
     * @param playerNearSampleTicks 0.7.0-P3 B2（K14）：playerNear 触发器的采样间隔
     *                         （游戏 tick；默 10 = 0.5s；load 期 clamp 1..200）。热更走
     *                         {@code PlayerNearSampler.setSampleTicks}（volatile 跳帧计数，
     *                         无需重 schedule）；实际分辨率受底层 task 周期 2 tick 限制
     * @param maxElementsPerWall 0.7.2-P2（F10）：单墙元素总数上限（脚本"克隆元素"积木反复
     *                         克隆是唯一能无界堆元素的入口；默 200）。热更：confirm/open 新建
     *                         EditSession 时透传 + headless 经
     *                         {@code ElementPropertyApplier.setMaxElementsPerWall}，
     *                         {@code /canvas reload} 后下一次克隆即生效
     */
    public record ScriptsConfig(int maxRulesPerWall, int maxActionsPerRun,
                                int maxRunsPerSecond, int maxChainDepth,
                                java.util.Map<String, CommandTemplate> commandTemplates,
                                int playerNearSampleTicks,
                                int maxElementsPerWall) {
        /** playerNear 采样间隔默认值（tick）；P6 压测后可能回调（docs/scripting.md §10）。 */
        public static final int DEFAULT_PLAYER_NEAR_SAMPLE_TICKS = 10;

        /** 0.7.2-P2（F10）：单墙元素总数上限默认值。 */
        public static final int DEFAULT_MAX_ELEMENTS_PER_WALL = 200;

        public ScriptsConfig {
            commandTemplates = commandTemplates == null
                    ? java.util.Map.of() : java.util.Map.copyOf(commandTemplates);
        }

        /** 兼容旧 4 参形态（既有单测 / budget 路径不关心模板与采样间隔）：模板表为空。 */
        public ScriptsConfig(int maxRulesPerWall, int maxActionsPerRun,
                             int maxRunsPerSecond, int maxChainDepth) {
            this(maxRulesPerWall, maxActionsPerRun, maxRunsPerSecond, maxChainDepth,
                    java.util.Map.of(), DEFAULT_PLAYER_NEAR_SAMPLE_TICKS,
                    DEFAULT_MAX_ELEMENTS_PER_WALL);
        }

        /** 兼容 0.7.0-P3 A1 的 5 参形态（采样间隔走默认）。 */
        public ScriptsConfig(int maxRulesPerWall, int maxActionsPerRun,
                             int maxRunsPerSecond, int maxChainDepth,
                             java.util.Map<String, CommandTemplate> commandTemplates) {
            this(maxRulesPerWall, maxActionsPerRun, maxRunsPerSecond, maxChainDepth,
                    commandTemplates, DEFAULT_PLAYER_NEAR_SAMPLE_TICKS,
                    DEFAULT_MAX_ELEMENTS_PER_WALL);
        }

        /** 兼容 0.7.0-P3 B2 的 6 参形态（元素配额走默认）。 */
        public ScriptsConfig(int maxRulesPerWall, int maxActionsPerRun,
                             int maxRunsPerSecond, int maxChainDepth,
                             java.util.Map<String, CommandTemplate> commandTemplates,
                             int playerNearSampleTicks) {
            this(maxRulesPerWall, maxActionsPerRun, maxRunsPerSecond, maxChainDepth,
                    commandTemplates, playerNearSampleTicks, DEFAULT_MAX_ELEMENTS_PER_WALL);
        }

        public static ScriptsConfig defaults() {
            return new ScriptsConfig(16, 50, 10, 8);
        }
    }

    /**
     * 0.7.0-P3 A1：runCommand 命令模板（docs/scripting.md §5.2；K13）。
     *
     * @param command 命令全文，可含 {@code {param}} 占位符（不带前导 {@code /}，
     *                执行侧会防御性剥掉一个）
     * @param params  参数名 → 校验规格；只有声明过的参数会被替换进命令
     */
    public record CommandTemplate(String command, java.util.Map<String, ParamSpec> params) {
        public CommandTemplate {
            params = params == null ? java.util.Map.of() : java.util.Map.copyOf(params);
        }
    }

    /**
     * 命令模板单参数规格（K13）。
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
            java.util.List<String> allowedMime,
            int downscaleMaxEdge,
            int maxPerWall,
            int maxUploadsPerDay,
            int maxTotalStorageMb
    ) {
        public static ImageConfig defaults() {
            return new ImageConfig(2048,
                    java.util.List.of("image/png", "image/jpeg", "image/webp"),
                    1024, 16, 50, 1024);
        }
    }

    /**
     * 0.8 .canvas 工程导入限额（防恶意大文件 / zip 炸弹）。
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
        FileConfiguration f = plugin.getConfig();
        Builder b = new Builder();
        b.host = f.getString("network.host", b.host);
        warnIfPublicBind(b.host, plugin.getLogger());
        b.port = f.getInt("network.port", b.port);
        String urlTemplate = f.getString("network.editor-url",
                "http://{host}:{port}/?token={token}");
        // M16 P6.5：editor-url 协议白名单。admin 把 url 写成 javascript:/data:/file:/vbscript:
        // 等 → 玩家点 ActionBar / chat 链接执行 JS，等价于配置侧 XSS。仅允许 http(s)；
        // 不合法回退到默认 http://{host}:{port}/?token={token}，并 severe log 让 ops 知道。
        b.editorUrlTemplate = sanitizeEditorUrl(urlTemplate, b.host, b.port, plugin.getLogger());

        // M16 P1.2 / P1.3：网络层安全收口
        b.wsAuthTimeoutSeconds = Math.max(1, Math.min(60, f.getInt("network.ws-auth-timeout-seconds", 5)));
        @SuppressWarnings("unchecked")
        java.util.List<String> origins = (java.util.List<String>) f.getList(
                "network.allowed-origins", java.util.List.of());
        b.allowedOrigins = origins == null
                ? java.util.List.of()
                : java.util.List.copyOf(origins);

        b.tokenTtl = Duration.ofMinutes(f.getLong("session.token-ttl-minutes", 15));
        b.wsGrace = Duration.ofMinutes(f.getLong("session.ws-grace-minutes", 5));
        long idleMin = f.getLong("session.idle-minutes", 30);
        b.idleTimeout = idleMin <= 0 ? Duration.ofDays(365 * 100) : Duration.ofMinutes(idleMin);
        b.reaperScanTicks = 20L * Math.max(5, f.getLong("session.reaper-scan-seconds", 30));
        b.tokenPurgeTicks = 20L * 60 * Math.max(1, f.getLong("session.token-purge-minutes", 5));

        // P3-44：map-pool.initial 下限钳到 1（不是 0）。MapPool 构造前置条件要求
        // initialSize > 0，配 0 会在 onEnable 抛 IllegalArgumentException 崩插件。
        // 上限 1024 与 docs/data-model.md §11 声明的 range 1-1024 对齐。
        b.mapPoolInitial = Math.max(1, Math.min(1024, f.getInt("map-pool.initial", 64)));
        b.mapPoolMax = Math.max(b.mapPoolInitial, f.getInt("map-pool.max", 256));
        // M16 P2.3：可选 per-world 初始分配。yml 形如：
        //   map-pool:
        //     per-world:
        //       world: 32
        //       world_nether: 8
        //       world_the_end: 4
        // 未列出的 world 走 on-demand 扩容；负值 / 非整数 clamp 到 0。
        org.bukkit.configuration.ConfigurationSection pw = f.getConfigurationSection("map-pool.per-world");
        if (pw != null) {
            java.util.Map<String, Integer> parsed = new java.util.LinkedHashMap<>();
            for (String k : pw.getKeys(false)) {
                int v = Math.max(0, pw.getInt(k, 0));
                if (v > 0) parsed.put(k, v);
            }
            b.mapPoolPerWorldInitial = java.util.Map.copyOf(parsed);
        }

        b.projectionFps = Math.max(1, Math.min(30, f.getInt("throttle.projection-fps", 5)));
        b.inputRatePerSecond = Math.max(1, f.getInt("throttle.input-rate-per-second", 20));
        b.inputBurst = Math.max(b.inputRatePerSecond, f.getInt("throttle.input-burst", 40));

        // 0.4.0 方案 B 自适应渲染段
        org.bukkit.configuration.ConfigurationSection adaptSec =
                f.getConfigurationSection("rendering.adaptive-fps");
        AdaptiveFpsConfig adaptDefaults = AdaptiveFpsConfig.defaults();
        if (adaptSec == null) {
            b.adaptiveFps = adaptDefaults;
        } else {
            long defMs = Math.max(33L,
                    adaptSec.getLong("default-min-interval-ms", adaptDefaults.defaultMinIntervalMs()));
            long highMs = Math.max(33L,
                    adaptSec.getLong("high-freq-min-interval-ms", adaptDefaults.highFreqMinIntervalMs()));
            // 高频间隔不该比默认还大；交叉配置时取小者保护用户意图
            if (highMs > defMs) highMs = defMs;
            boolean pushOn = adaptSec.getBoolean("push-packets-enabled",
                    adaptDefaults.pushPacketsEnabled());
            b.adaptiveFps = new AdaptiveFpsConfig(defMs, highMs, pushOn);
        }

        // 0.8.2：i18n 段 —— 国际化兜底语言
        org.bukkit.configuration.ConfigurationSection i18nSec = f.getConfigurationSection("i18n");
        if (i18nSec == null) {
            b.i18nConfig = I18nConfig.defaults();
        } else {
            String def = i18nSec.getString("default-locale", "en_us")
                    .toLowerCase(java.util.Locale.ROOT).replace('-', '_');
            b.i18nConfig = new I18nConfig(def);
        }

        b.autoReloadTemplatesOnStartup = f.getBoolean("templates.auto-reload-on-startup", true);
        b.previewCacheSeconds = Math.max(0, f.getInt("templates.preview-cache-seconds", 300));
        b.templatesMaxPerPlayer = Math.max(0, f.getInt("templates.max-per-player", 20));

        // M13 images 段
        ImageConfig defaults = ImageConfig.defaults();
        @SuppressWarnings("unchecked")
        java.util.List<String> mimes = (java.util.List<String>) f.getList(
                "images.allowed-mime", defaults.allowedMime());
        b.images = new ImageConfig(
                Math.max(1, f.getInt("images.max-size-kb", defaults.maxSizeKb())),
                mimes == null || mimes.isEmpty() ? defaults.allowedMime() : mimes,
                Math.max(64, f.getInt("images.downscale-max-edge", defaults.downscaleMaxEdge())),
                Math.max(0, f.getInt("images.max-per-wall", defaults.maxPerWall())),
                Math.max(0, f.getInt("images.max-uploads-per-day", defaults.maxUploadsPerDay())),
                Math.max(0, f.getInt("images.max-total-storage-mb", defaults.maxTotalStorageMb())));

        // 0.8 import 段（.canvas 工程导入限额）
        ImportConfig importDefaults = ImportConfig.defaults();
        b.importConfig = new ImportConfig(
                Math.max(1, f.getInt("import.canvas-max-mb", importDefaults.canvasMaxMb())),
                Math.max(1, f.getInt("import.canvas-max-entry-mb", importDefaults.canvasMaxEntryMb())),
                Math.max(1, f.getInt("import.canvas-max-total-mb", importDefaults.canvasMaxTotalMb())));

        // database 段：0.9.1 起备份 WAL 安全，默认打开。
        b.databaseAutoBackup = f.getBoolean("database.auto-backup-before-migration", true);
        b.backupRetentionDays = f.getInt("database.backup-retention-days", 30);

        // 0.4.0-P4-P：dynamic.push-rate-limit 段
        org.bukkit.configuration.ConfigurationSection rate =
                f.getConfigurationSection("dynamic.push-rate-limit");
        moe.hikari.canvas.variable.plugin.PushRateLimiter.Config rateDefaults =
                moe.hikari.canvas.variable.plugin.PushRateLimiter.Config.defaults();
        if (rate == null) {
            b.pushRateLimitConfig = rateDefaults;
        } else {
            int perPlugin = Math.max(1,
                    rate.getInt("per-plugin-per-second", rateDefaults.perPluginPerSecond()));
            int global = Math.max(perPlugin,
                    rate.getInt("global-per-second", rateDefaults.globalPerSecond()));
            long breakMs = Math.max(0L,
                    rate.getLong("global-circuit-break-ms", rateDefaults.globalCircuitBreakMs()));
            b.pushRateLimitConfig = new moe.hikari.canvas.variable.plugin.PushRateLimiter.Config(
                    perPlugin, global, breakMs);
        }

        // 0.4.0 bugfix（Bug 3）：dynamic.schedule 段
        org.bukkit.configuration.ConfigurationSection schedSec =
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

        // 0.4.3：dynamic.variables 段 — 全局用户变量配额（缺则用 VariableStore 默认值）
        org.bukkit.configuration.ConfigurationSection varSec =
                f.getConfigurationSection("dynamic.variables");
        if (varSec != null) {
            b.userGlobalMaxPerOwner = Math.max(1, varSec.getInt(
                    "userglobal-max-per-owner",
                    moe.hikari.canvas.variable.VariableStore.DEFAULT_USERGLOBAL_PER_OWNER));
            b.userGlobalMaxTotal = Math.max(1, varSec.getInt(
                    "userglobal-max-total",
                    moe.hikari.canvas.variable.VariableStore.DEFAULT_USERGLOBAL_TOTAL));
        }

        // 2026-05-25：security.token-rate-limit 段 —— token 暴力枚举防御
        org.bukkit.configuration.ConfigurationSection rlSec =
                f.getConfigurationSection("security.token-rate-limit");
        if (rlSec != null) {
            b.tokenRateLimitPerMinute = Math.max(1, rlSec.getInt(
                    "per-minute",
                    moe.hikari.canvas.web.TokenRateLimiter.DEFAULT_PER_MINUTE));
        }

        // 0.6：timeline 段 —— 时间轴帧率参数（default-fps 钳到 max-fps 之内）
        org.bukkit.configuration.ConfigurationSection tlSec =
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

        // 0.7：scripts 段 —— 墙脚本参数
        org.bukkit.configuration.ConfigurationSection scSec =
                f.getConfigurationSection("scripts");
        ScriptsConfig scDefaults = ScriptsConfig.defaults();
        if (scSec == null) {
            b.scriptsConfig = scDefaults;
        } else {
            int maxRules = Math.max(1, scSec.getInt(
                    "max-rules-per-wall", scDefaults.maxRulesPerWall()));
            // 0.7.0-P2：scripts.budget 嵌套段（照 dynamic.schedule 范式；缺段全 default）
            org.bukkit.configuration.ConfigurationSection budSec =
                    scSec.getConfigurationSection("budget");
            int maxActions = scDefaults.maxActionsPerRun();
            int maxRuns = scDefaults.maxRunsPerSecond();
            int maxChain = scDefaults.maxChainDepth();
            if (budSec != null) {
                maxActions = Math.max(1, budSec.getInt("max-actions-per-run", maxActions));
                maxRuns = Math.max(1, budSec.getInt("max-runs-per-second", maxRuns));
                maxChain = Math.max(1, budSec.getInt("max-chain-depth", maxChain));
            }
            // 0.7.0-P3 A1：scripts.command-templates 段（docs/scripting.md §5.2）。
            // 形如：
            //   command-templates:
            //     announce:
            //       command: "say [招牌] {msg}"
            //       params:
            //         msg: { max-length: 64 }
            // command 缺失 / 空白的模板条目跳过 + severe（服主写错要看得见）。
            java.util.Map<String, CommandTemplate> templates = new java.util.LinkedHashMap<>();
            org.bukkit.configuration.ConfigurationSection ctSec =
                    scSec.getConfigurationSection("command-templates");
            if (ctSec != null) {
                for (String tplId : ctSec.getKeys(false)) {
                    org.bukkit.configuration.ConfigurationSection tplSec =
                            ctSec.getConfigurationSection(tplId);
                    if (tplSec == null) {
                        plugin.getLogger().severe("scripts.command-templates." + tplId
                                + " 不是配置段，已跳过");
                        continue;
                    }
                    String command = tplSec.getString("command", "");
                    if (command == null || command.isBlank()) {
                        plugin.getLogger().severe("scripts.command-templates." + tplId
                                + ".command 缺失或为空，已跳过该模板");
                        continue;
                    }
                    java.util.Map<String, ParamSpec> params = new java.util.LinkedHashMap<>();
                    org.bukkit.configuration.ConfigurationSection psSec =
                            tplSec.getConfigurationSection("params");
                    if (psSec != null) {
                        for (String pName : psSec.getKeys(false)) {
                            org.bukkit.configuration.ConfigurationSection pSec =
                                    psSec.getConfigurationSection(pName);
                            int maxLen = ParamSpec.DEFAULT_MAX_LENGTH;
                            String type = ParamSpec.TYPE_TEXT;
                            if (pSec != null) {
                                maxLen = Math.max(1, pSec.getInt("max-length", maxLen));
                                String rawType = pSec.getString("type", type);
                                if (ParamSpec.TYPE_ONLINE_PLAYER.equals(rawType)) {
                                    type = ParamSpec.TYPE_ONLINE_PLAYER;
                                } else if (!ParamSpec.TYPE_TEXT.equals(rawType)) {
                                    plugin.getLogger().severe("scripts.command-templates."
                                            + tplId + ".params." + pName + ".type 未知值 '"
                                            + rawType + "'，按 text 处理");
                                }
                            }
                            params.put(pName, new ParamSpec(maxLen, type));
                        }
                    }
                    templates.put(tplId, new CommandTemplate(command.trim(), params));
                }
            }
            // 0.7.0-P3 B2（K14）：playerNear 采样间隔（tick；clamp 1..200——下限防主线程
            // 每 tick 全量扫，上限 10s 再迟钝就失去"靠近"语义）。
            int nearTicks = Math.min(200, Math.max(1, scSec.getInt(
                    "player-near-sample-ticks",
                    ScriptsConfig.DEFAULT_PLAYER_NEAR_SAMPLE_TICKS)));
            // 0.7.2-P2（F10）：单墙元素总数上限（脚本克隆元素强制）。clamp ≥1。
            int maxElements = Math.max(1, scSec.getInt("max-elements-per-wall",
                    ScriptsConfig.DEFAULT_MAX_ELEMENTS_PER_WALL));
            b.scriptsConfig = new ScriptsConfig(maxRules, maxActions, maxRuns, maxChain,
                    templates, nearTicks, maxElements);
            // 0.7.1：scripts.tween 嵌套段——补间引擎 maxFps / maxConcurrent
            org.bukkit.configuration.ConfigurationSection tweenSec =
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
     * M16 P6.5：editor-url 协议白名单。
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
            logger.severe("editor-url contains CR/LF; falling back to default. raw=<redacted>");
            return fallback;
        }
        // {token} 是运行期替换占位符；URI 解析期为了拿 scheme，先用占位字符串替代
        String parseTarget = resolved.replace("{token}", "PLACEHOLDER");
        String scheme;
        try {
            URI uri = URI.create(parseTarget);
            scheme = uri.getScheme();
        } catch (IllegalArgumentException e) {
            logger.severe("editor-url is not a valid URI; falling back to default. reason=" + e.getMessage());
            return fallback;
        }
        if (scheme == null) {
            logger.severe("editor-url has no scheme; must start with http:// or https://. falling back to default.");
            return fallback;
        }
        String lower = scheme.toLowerCase(java.util.Locale.ROOT);
        if (!lower.equals("http") && !lower.equals("https")) {
            logger.severe("editor-url has invalid scheme '" + scheme
                    + "'; must be http(s); falling back to default. "
                    + "(rejected schemes include javascript:/data:/file:/vbscript:)");
            return fallback;
        }
        return resolved;
    }

    /**
     * 0.9.3：服主把 {@code network.host} 显式绑成公网通配地址（{@code 0.0.0.0} / {@code ::}）时，
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
        logger.warning("[HikariCanvas] network.host is bound to '" + host
                + "' (a public interface). The web editor ships NO TLS, so the auth "
                + "token is exposed in plaintext on the network.");
        logger.warning("[HikariCanvas] For any public deployment, bind 127.0.0.1 and put "
                + "a TLS reverse proxy (nginx / Caddy) in front. See SECURITY.md.");
    }

    /** 摘要字符串，启动 / reload 时 log 一下，方便排错。 */
    public String summary() {
        return String.format(
                "host=%s port=%d tokenTtl=%dm idleTimeout=%s mapPool=[%d..%d] "
                        + "fps=%d rate=%d/s burst=%d",
                host, port, tokenTtl.toMinutes(),
                idleTimeout.toDays() > 30 ? "∞" : (idleTimeout.toMinutes() + "m"),
                mapPoolInitial, mapPoolMax,
                projectionFps, inputRatePerSecond, inputBurst);
    }

    /** 字段默认值的初始化容器；load() 内部用。 */
    private static final class Builder {
        String host = "127.0.0.1";
        int port = 8877;
        String editorUrlTemplate = "http://127.0.0.1:8877/?token={token}";
        int wsAuthTimeoutSeconds = 5;
        java.util.List<String> allowedOrigins = java.util.List.of();
        Duration tokenTtl = Duration.ofMinutes(15);
        Duration wsGrace = Duration.ofMinutes(5);
        Duration idleTimeout = Duration.ofMinutes(30);
        long reaperScanTicks = 20L * 30;
        long tokenPurgeTicks = 20L * 60 * 5;
        int mapPoolInitial = 64;
        int mapPoolMax = 256;
        java.util.Map<String, Integer> mapPoolPerWorldInitial = java.util.Map.of();
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
        moe.hikari.canvas.variable.plugin.PushRateLimiter.Config pushRateLimitConfig =
                moe.hikari.canvas.variable.plugin.PushRateLimiter.Config.defaults();
        ScheduleConfig scheduleConfig = ScheduleConfig.defaults();
        AdaptiveFpsConfig adaptiveFps = AdaptiveFpsConfig.defaults();
        I18nConfig i18nConfig = I18nConfig.defaults();
        int userGlobalMaxPerOwner =
                moe.hikari.canvas.variable.VariableStore.DEFAULT_USERGLOBAL_PER_OWNER;
        int userGlobalMaxTotal =
                moe.hikari.canvas.variable.VariableStore.DEFAULT_USERGLOBAL_TOTAL;
        int tokenRateLimitPerMinute =
                moe.hikari.canvas.web.TokenRateLimiter.DEFAULT_PER_MINUTE;
        TimelineConfig timelineConfig = TimelineConfig.defaults();
        ScriptsConfig scriptsConfig = ScriptsConfig.defaults();
        TweenConfig tweenConfig = TweenConfig.defaults();
    }
}
