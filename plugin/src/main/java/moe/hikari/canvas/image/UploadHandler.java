package moe.hikari.canvas.image;

import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import moe.hikari.canvas.HikariCanvasConfig;
import moe.hikari.canvas.session.Session;
import moe.hikari.canvas.session.SessionManager;
import moe.hikari.canvas.session.TokenService;
import moe.hikari.canvas.state.ImageElement;
import moe.hikari.canvas.storage.WallRepo;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * M13 {@code POST /api/upload} 处理器：multipart + 6 层校验栈 + 配额。
 *
 * <h2>校验栈</h2>
 * <ol>
 *   <li>auth + permission：{@code sessionId} multipart 字段定位 {@link Session}；查
 *       {@link Bukkit#getPlayer(UUID)} 取 live Player → {@code hasPermission("canvas.upload")}</li>
 *   <li>大小：{@code file.getSize()} ≤ {@code max-size-kb}</li>
 *   <li>Content-Type：客户端声明 MIME ∈ {@code allowed-mime}</li>
 *   <li>magic bytes：实际文件前 16 字节匹配 MIME（防止 Content-Type 假冒）</li>
 *   <li>ImageIO 隔离解码：单独 {@link ExecutorService} + 200ms 超时（防压缩炸弹 / 死循环）</li>
 *   <li>bbox sanity：{@code 0 < w/h ≤ 8192}；边长 > {@code downscale-max-edge} 自动 bilinear 缩</li>
 *   <li>配额三层：{@link ImageQuotaService#check}；磁盘超限 → LRU evict 后重试一次</li>
 * </ol>
 *
 * <h2>响应</h2>
 * 成功：{@code 200 + { source, width, height, bytes }}（{@code source} 是 sha256[:16] hash）。
 * 失败：{@code 400 / 401 / 403 / 413 / 429} + JSON {@code { error, message }}。
 */
public final class UploadHandler {

    private static final long IMAGEIO_TIMEOUT_MS = 200L;
    private static final int BBOX_MAX_EDGE = 8192;

    private final Logger log;
    private final ImageStorage storage;
    private final ImageQuotaService quota;
    private final HikariCanvasConfig.ImageConfig cfg;
    private final TokenService tokenService;
    private final SessionManager sessionManager;
    private final WallRepo wallRepo;
    private final ExecutorService decoderPool;

    public UploadHandler(Logger log,
                         ImageStorage storage, ImageQuotaService quota,
                         HikariCanvasConfig.ImageConfig cfg,
                         TokenService tokenService, SessionManager sessionManager,
                         WallRepo wallRepo) {
        this.log = log;
        this.storage = storage;
        this.quota = quota;
        this.cfg = cfg;
        this.tokenService = tokenService;
        this.sessionManager = sessionManager;
        this.wallRepo = wallRepo;
        this.decoderPool = Executors.newCachedThreadPool(new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "hikari-image-decoder-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    public void shutdown() {
        decoderPool.shutdownNow();
    }

    // ---------- POST /api/upload ----------

    /**
     * 处理上传请求。返回 ack JSON。所有错误路径统一通过 {@link #reject} 写状态 + JSON。
     */
    public void handleUpload(Context ctx) {
        // 1. sessionId + permission
        String sessionId = ctx.formParam("sessionId");
        Session session = resolveSession(ctx, sessionId);
        if (session == null) return;
        UUID uploader = session.playerUuid();
        Player player = Bukkit.getPlayer(uploader);
        if (player != null && !player.hasPermission("canvas.upload")) {
            reject(ctx, 403, "FORBIDDEN", "missing canvas.upload permission");
            return;
        }
        boolean bypass = player != null && player.hasPermission("canvas.upload.bypass-limit");

        // 2. multipart file
        UploadedFile file = ctx.uploadedFile("file");
        if (file == null) {
            reject(ctx, 400, "INVALID_PAYLOAD", "missing 'file' multipart field");
            return;
        }

        long sizeBytes = file.size();
        long maxBytes = (long) cfg.maxSizeKb() * 1024L;
        if (sizeBytes > maxBytes) {
            reject(ctx, 413, "UPLOAD_REJECTED",
                    "file too large: " + sizeBytes + " > " + maxBytes + " bytes");
            return;
        }
        if (sizeBytes <= 0) {
            reject(ctx, 400, "INVALID_PAYLOAD", "empty file");
            return;
        }

        // 3. Content-Type whitelist
        String declaredMime = file.contentType() == null
                ? "application/octet-stream"
                : file.contentType().toLowerCase(Locale.ROOT).trim();
        // 去除 ;charset=... 等参数
        int sep = declaredMime.indexOf(';');
        if (sep > 0) declaredMime = declaredMime.substring(0, sep).trim();
        if (!cfg.allowedMime().contains(declaredMime)) {
            reject(ctx, 415, "UPLOAD_REJECTED",
                    "Content-Type not allowed: " + declaredMime);
            return;
        }

        // 4. 读 bytes + magic bytes 校验
        byte[] bytes;
        try (InputStream in = file.content()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            reject(ctx, 400, "UPLOAD_REJECTED", "read failed: " + e.getMessage());
            return;
        }
        String actualMime = detectMagicMime(bytes);
        if (actualMime == null || !actualMime.equals(declaredMime)) {
            reject(ctx, 400, "UPLOAD_REJECTED",
                    "magic bytes mismatch: declared=" + declaredMime
                            + " actual=" + actualMime);
            return;
        }

        // 5. ImageIO 隔离解码（200ms 超时）
        BufferedImage decoded;
        try {
            decoded = decodeWithTimeout(bytes);
        } catch (TimeoutException te) {
            reject(ctx, 400, "UPLOAD_REJECTED", "decode timeout (possible decompression bomb)");
            return;
        } catch (Exception e) {
            log.log(Level.FINE, "decode failed", e);
            reject(ctx, 400, "UPLOAD_REJECTED", "decode failed: " + e.getMessage());
            return;
        }
        if (decoded == null) {
            reject(ctx, 400, "UPLOAD_REJECTED", "ImageIO returned null (unsupported format?)");
            return;
        }

        // 6. bbox sanity + downscale
        int w = decoded.getWidth();
        int h = decoded.getHeight();
        if (w <= 0 || h <= 0 || w > BBOX_MAX_EDGE || h > BBOX_MAX_EDGE) {
            reject(ctx, 400, "UPLOAD_REJECTED",
                    "bbox out of range: " + w + "x" + h);
            return;
        }
        int maxEdge = cfg.downscaleMaxEdge();
        if (Math.max(w, h) > maxEdge) {
            decoded = downscale(decoded, maxEdge);
        }

        // 7. 配额（per-wall 由 EditSession 在 element.add 时再卡；这里 0 占位 → 不卡 per-wall）
        long approxBytes = (long) decoded.getWidth() * decoded.getHeight() * 4L; // 上限估算（PNG 编码后通常更小）
        ImageQuotaService.CheckResult qr = quota.check(uploader, 0, approxBytes, bypass);
        if (qr instanceof ImageQuotaService.CheckResult.Rejected rj) {
            reject(ctx, 429, rj.code(), rj.reason());
            return;
        }
        if (qr instanceof ImageQuotaService.CheckResult.NeedsEviction ne) {
            long maxTotal = (long) cfg.maxTotalStorageMb() * 1024L * 1024L;
            storage.evictLruUntilUnder(approxBytes, maxTotal, wallRepo);
            // 再查一次
            ImageQuotaService.CheckResult retry = quota.check(uploader, 0, approxBytes, bypass);
            if (retry instanceof ImageQuotaService.CheckResult.Rejected rj2) {
                reject(ctx, 429, rj2.code(), rj2.reason());
                return;
            }
            if (retry instanceof ImageQuotaService.CheckResult.NeedsEviction) {
                reject(ctx, 429, "QUOTA_DISK_FULL",
                        "all uploads in use by active walls; cannot free space (need " + ne.bytesToFree() + " bytes)");
                return;
            }
        }

        // 8. PNG 编码 + 落盘
        ImageStorage.StoreResult result;
        try {
            result = storage.putIfAbsent(decoded, uploader);
        } catch (IOException e) {
            log.log(Level.WARNING, "ImageStorage.putIfAbsent failed", e);
            reject(ctx, 500, "INTERNAL_ERROR", "persist failed: " + e.getMessage());
            return;
        }

        ctx.status(200).json(Map.of(
                "source", result.hash(),
                "width", result.width(),
                "height", result.height(),
                "bytes", result.bytes()));
    }

    // ---------- GET /api/upload/{source} ----------

    public void handleDownload(Context ctx) {
        String hash = ctx.pathParam("source");
        // 去掉可选 .png 后缀
        if (hash.endsWith(".png")) hash = hash.substring(0, hash.length() - 4);
        if (!hash.matches("^[0-9a-f]{16}$")) {
            ctx.status(400);
            return;
        }
        byte[] bytes = storage.readPngBytes(hash);
        if (bytes == null) {
            ctx.status(404);
            return;
        }
        ctx.contentType("image/png");
        // hash 唯一对应内容 → 强缓存 + immutable
        ctx.header("Cache-Control", "public, max-age=31536000, immutable");
        ctx.result(bytes);
    }

    // ---------- GET /api/upload/quota ----------

    public void handleQuota(Context ctx) {
        String sessionId = ctx.queryParam("sessionId");
        Session session = resolveSession(ctx, sessionId);
        if (session == null) return;
        ImageQuotaService.Summary s = quota.remaining(session.playerUuid(), 0);
        ctx.status(200).json(Map.of(
                "perWall", Map.of("limit", s.perWallLimit(), "used", s.perWallUsed()),
                "perDay", Map.of("limit", s.perDayLimit(), "used", s.perDayUsed()),
                "totalDiskMb", Map.of(
                        "limit", s.totalDiskMbLimit(),
                        "used", Math.round(s.totalDiskBytesUsed() / 1048576.0))));
    }

    // ---------- helpers ----------

    /** 通过 multipart {@code sessionId} 字段（或 query 参数）解析 {@link Session}；失败时已写响应。 */
    private Session resolveSession(Context ctx, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            reject(ctx, 401, "AUTH_FAILED", "missing sessionId");
            return null;
        }
        Session s = sessionManager.byId(sessionId);
        if (s == null) {
            reject(ctx, 401, "AUTH_FAILED", "unknown session");
            return null;
        }
        return s;
    }

    private void reject(Context ctx, int status, String code, String message) {
        ctx.status(status).json(Map.of("error", code, "message", message == null ? "" : message));
    }

    /**
     * 在 {@link #decoderPool} 上单独线程跑 {@link ImageIO#read(InputStream)}，限时
     * {@value #IMAGEIO_TIMEOUT_MS} ms；超时 cancel 抛 {@link TimeoutException}。
     */
    private BufferedImage decodeWithTimeout(byte[] bytes) throws Exception {
        Future<BufferedImage> fut = decoderPool.submit(
                (Callable<BufferedImage>) () -> ImageIO.read(new ByteArrayInputStream(bytes)));
        try {
            return fut.get(IMAGEIO_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            fut.cancel(true);
            throw te;
        }
    }

    /** Magic bytes 真实 MIME 探测：前 16 字节即可识别 PNG/JPEG/WEBP。 */
    static String detectMagicMime(byte[] b) {
        if (b == null || b.length < 12) return null;
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if ((b[0] & 0xff) == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47) {
            return "image/png";
        }
        // JPEG: FF D8 FF
        if ((b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF) {
            return "image/jpeg";
        }
        // WEBP: 'RIFF' .... 'WEBP'
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    /** bilinear 缩放到最大边 = maxEdge，等比例。 */
    static BufferedImage downscale(BufferedImage src, int maxEdge) {
        int w = src.getWidth();
        int h = src.getHeight();
        double scale = (double) maxEdge / Math.max(w, h);
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        return dst;
    }
}
