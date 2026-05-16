package moe.hikari.canvas;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;

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
        b.editorUrlTemplate = urlTemplate
                .replace("{host}", b.host)
                .replace("{port}", String.valueOf(b.port));

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
