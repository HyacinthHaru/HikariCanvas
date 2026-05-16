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

    // ---- templates ----
    public final boolean autoReloadTemplatesOnStartup;
    public final int previewCacheSeconds;
    /** M14 创意工坊：每玩家可发布模板数；0 = 不限。 */
    public final int templatesMaxPerPlayer;

    // ---- images (M13) ----
    public final ImageConfig images;

    // ---- database (M15.4) ----
    /** 跑 schema migration 前是否先备份 data.db。pre-release 默认 false。 */
    public final boolean databaseAutoBackup;

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
        this.autoReloadTemplatesOnStartup = b.autoReloadTemplatesOnStartup;
        this.previewCacheSeconds = b.previewCacheSeconds;
        this.templatesMaxPerPlayer = b.templatesMaxPerPlayer;
        this.images = b.images;
        this.databaseAutoBackup = b.databaseAutoBackup;
    }

    /**
     * 从 plugin 当前的 {@link FileConfiguration} 读取。任何缺失字段走默认值。
     * 启动期或 {@code /canvas reload config} 调；调用前应先 {@code plugin.reloadConfig()}。
     */
    public static HikariCanvasConfig load(JavaPlugin plugin) {
        FileConfiguration f = plugin.getConfig();
        Builder b = new Builder();
        b.host = f.getString("network.host", b.host);
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

        b.mapPoolInitial = Math.max(0, f.getInt("map-pool.initial", 64));
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

        // M15.4 P0-29 database 段
        b.databaseAutoBackup = f.getBoolean("database.auto-backup-before-migration", false);

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
        boolean databaseAutoBackup = false;
    }
}
