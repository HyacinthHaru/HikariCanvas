package moe.hikari.canvas.image;

import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import moe.hikari.canvas.HikariCanvasConfig;
import moe.hikari.canvas.session.Session;
import moe.hikari.canvas.session.SessionManager;
import moe.hikari.canvas.session.TokenService;
import moe.hikari.canvas.state.ImageElement;
import moe.hikari.canvas.storage.ImageUploadDao;
import moe.hikari.canvas.storage.WallRepo;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.transaction.TransactionIsolationLevel;

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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
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
    private final ImageUploadDao imageDao;
    private final Jdbi jdbi;
    private final HikariCanvasConfig.ImageConfig cfg;
    private final TokenService tokenService;
    private final SessionManager sessionManager;
    private final WallRepo wallRepo;
    /** M16 P6.4：上传成功 / 拒绝路径打 audit；可空（旧测试构造器走 null）。 */
    private final moe.hikari.canvas.storage.AuditLog auditLog;
    private final ExecutorService decoderPool;

    public UploadHandler(Logger log,
                         ImageStorage storage, ImageQuotaService quota,
                         ImageUploadDao imageDao, Jdbi jdbi,
                         HikariCanvasConfig.ImageConfig cfg,
                         TokenService tokenService, SessionManager sessionManager,
                         WallRepo wallRepo) {
        this(log, storage, quota, imageDao, jdbi, cfg, tokenService, sessionManager, wallRepo, null);
    }

    public UploadHandler(Logger log,
                         ImageStorage storage, ImageQuotaService quota,
                         ImageUploadDao imageDao, Jdbi jdbi,
                         HikariCanvasConfig.ImageConfig cfg,
                         TokenService tokenService, SessionManager sessionManager,
                         WallRepo wallRepo,
                         moe.hikari.canvas.storage.AuditLog auditLog) {
        this.log = log;
        this.storage = storage;
        this.quota = quota;
        this.imageDao = imageDao;
        this.jdbi = jdbi;
        this.cfg = cfg;
        this.tokenService = tokenService;
        this.sessionManager = sessionManager;
        this.wallRepo = wallRepo;
        this.auditLog = auditLog;
        // M15.1 P0-13：有界 ThreadPool 防 unbounded fork。线程上限 2 + 队列 8 =
        // 同时最多 10 个上传等待，超出立即 AbortPolicy 拒（不堆积内存）。
        this.decoderPool = new java.util.concurrent.ThreadPoolExecutor(
                2, 2,
                0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(8),
                new ThreadFactory() {
                    private final AtomicInteger n = new AtomicInteger();
                    @Override public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "hikari-image-decoder-" + n.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    }
                },
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
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
        String uploaderName = session.playerName();
        Player player = Bukkit.getPlayer(uploader);
        if (player != null && !player.hasPermission("canvas.upload")) {
            auditUploadRejected(uploader, uploaderName, sessionId, "missing_permission");
            reject(ctx, 403, "FORBIDDEN", "missing canvas.upload permission");
            return;
        }
        boolean bypass = player != null && player.hasPermission("canvas.upload.bypass-limit");

        // 2. multipart file
        UploadedFile file = ctx.uploadedFile("file");
        if (file == null) {
            auditUploadRejected(uploader, uploaderName, sessionId, "missing_file_field");
            reject(ctx, 400, "INVALID_PAYLOAD", "missing 'file' multipart field");
            return;
        }

        long sizeBytes = file.size();
        long maxBytes = (long) cfg.maxSizeKb() * 1024L;
        if (sizeBytes > maxBytes) {
            auditUploadRejected(uploader, uploaderName, sessionId, "size_exceeded");
            reject(ctx, 413, "UPLOAD_REJECTED",
                    "file too large: " + sizeBytes + " > " + maxBytes + " bytes");
            return;
        }
        if (sizeBytes <= 0) {
            auditUploadRejected(uploader, uploaderName, sessionId, "empty_file");
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
            auditUploadRejected(uploader, uploaderName, sessionId, "mime_not_allowed");
            reject(ctx, 415, "UPLOAD_REJECTED",
                    "Content-Type not allowed: " + declaredMime);
            return;
        }

        // 4. 读 bytes + magic bytes 校验
        byte[] bytes;
        try (InputStream in = file.content()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            // M16 P6.1：不 echo IOException message（可能含 multipart tmp 路径 / 文件名 / 内部状态）
            log.log(Level.WARNING, "upload read failed", e);
            auditUploadRejected(uploader, uploaderName, sessionId, "read_failed");
            reject(ctx, 400, "UPLOAD_REJECTED", "failed to read uploaded file");
            return;
        }
        String actualMime = detectMagicMime(bytes);
        if (actualMime == null || !actualMime.equals(declaredMime)) {
            auditUploadRejected(uploader, uploaderName, sessionId, "magic_mismatch");
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
            auditUploadRejected(uploader, uploaderName, sessionId, "decode_timeout");
            reject(ctx, 400, "UPLOAD_REJECTED", "decode timeout (possible decompression bomb)");
            return;
        } catch (Exception e) {
            // M16 P6.1：不 echo ImageIO 异常 message（可能含 codec 内部细节、tmp 路径）
            log.log(Level.WARNING, "image decode failed", e);
            auditUploadRejected(uploader, uploaderName, sessionId, "decode_failed");
            reject(ctx, 400, "UPLOAD_REJECTED", "failed to decode image");
            return;
        }
        if (decoded == null) {
            auditUploadRejected(uploader, uploaderName, sessionId, "decode_null");
            reject(ctx, 400, "UPLOAD_REJECTED", "ImageIO returned null (unsupported format?)");
            return;
        }

        // 6. bbox sanity + downscale
        int w = decoded.getWidth();
        int h = decoded.getHeight();
        if (w <= 0 || h <= 0 || w > BBOX_MAX_EDGE || h > BBOX_MAX_EDGE) {
            auditUploadRejected(uploader, uploaderName, sessionId, "bbox_out_of_range");
            reject(ctx, 400, "UPLOAD_REJECTED",
                    "bbox out of range: " + w + "x" + h);
            return;
        }
        int maxEdge = cfg.downscaleMaxEdge();
        if (Math.max(w, h) > maxEdge) {
            decoded = downscale(decoded, maxEdge);
        }

        // 7. PNG 编码 + hash 计算（事务外做 CPU 重活）
        byte[] pngBytes;
        try {
            pngBytes = ImageStorage.encodePng(decoded);
        } catch (IOException e) {
            log.log(Level.WARNING, "PNG encode failed", e);
            auditUploadRejected(uploader, uploaderName, sessionId, "encode_failed");
            reject(ctx, 500, "INTERNAL_ERROR", "encode failed");
            return;
        }
        String hash = ImageStorage.sha256Hex16(pngBytes);
        long pngLen = pngBytes.length;

        // 8. M16 P2.1 + P2.2 单事务 quota+evict+insert（IMMEDIATE 写锁）。
        //    per-hash lock 串行化同 hash 并发，避免两个 caller 同时写同一文件。
        ReentrantLock hashLock = storage.writeLockFor(hash);
        hashLock.lock();
        ImageQuotaService.QuotaResult qr;
        ImageUploadDao.Row finalRow;
        try {
            final ImageUploadDao.Row candidate = new ImageUploadDao.Row(
                    hash, pngLen,
                    decoded.getWidth(), decoded.getHeight(),
                    "image/png", uploader,
                    System.currentTimeMillis(), System.currentTimeMillis());
            // referenced 集合在事务外 sweep（jdbi 读连接）；事务内只用作 LRU 排除集
            Set<String> referenced = storage.collectReferencedHashes(wallRepo);

            try {
                qr = jdbi.inTransaction(TransactionIsolationLevel.SERIALIZABLE, handle -> {
                    // P2.1：SQLite 下用 SAVEPOINT-style 不行；这里通过先发一条 no-op
                    // UPDATE（影响 0 行也持锁）把事务从 DEFERRED 升级到 RESERVED 写锁，
                    // 避免并发两个 caller 都先 read 然后争 write 撞 SQLITE_BUSY。
                    // 配合 Database busy_timeout=5000 兜底。
                    handle.execute("UPDATE image_uploads SET last_used_at = last_used_at "
                            + "WHERE hash = '__locker__'");
                    // 重新查（持写锁内）以决定走 exists vs new
                    Optional<ImageUploadDao.Row> existing = imageDao.findByHashOn(handle, hash);
                    boolean alreadyExists = existing.isPresent();
                    return quota.tryReserveQuotaOn(
                            handle, uploader, candidate, pngLen,
                            alreadyExists, referenced, bypass);
                });
            } catch (Exception e) {
                log.log(Level.WARNING, "upload transaction failed", e);
                auditUploadRejected(uploader, uploaderName, sessionId, "persist_failed");
                reject(ctx, 500, "INTERNAL_ERROR", "persist failed");
                return;
            }

            if (qr instanceof ImageQuotaService.DeniedPerDay dp) {
                auditUploadRejected(uploader, uploaderName, sessionId, "quota_per_day");
                reject(ctx, 429, "QUOTA_EXCEEDED",
                        "per-day upload limit reached: " + dp.currentCount() + "/" + dp.limit());
                return;
            }
            if (qr instanceof ImageQuotaService.DeniedDiskAfterLru dd) {
                auditUploadRejected(uploader, uploaderName, sessionId, "quota_disk");
                reject(ctx, 413, "QUOTA_EXCEEDED_DISK",
                        "disk full; cannot free " + dd.bytesShort() + " more bytes");
                return;
            }

            ImageQuotaService.Reserved ok = (ImageQuotaService.Reserved) qr;

            // 事务 commit 后：(a) 写新文件 atomic move；(b) 清 LRU 已 evict 的孤儿文件
            if (ok.inserted()) {
                try {
                    storage.writeFileAtomic(hash, pngBytes);
                } catch (IOException e) {
                    // 补偿：回滚 DB 行，避免孤儿 DB row
                    log.log(Level.WARNING, "writeFileAtomic failed; rolling back DB row " + hash, e);
                    try {
                        jdbi.useTransaction(TransactionIsolationLevel.SERIALIZABLE, handle ->
                                imageDao.deleteOn(handle, hash));
                    } catch (Exception rb) {
                        log.log(Level.SEVERE,
                                "compensation DELETE failed; orphan DB row may remain: " + hash, rb);
                    }
                    auditUploadRejected(uploader, uploaderName, sessionId, "write_failed");
                    reject(ctx, 500, "INTERNAL_ERROR", "persist failed");
                    return;
                }
            }
            // best-effort 删除 LRU 已 evict 的磁盘文件（DB row 已在事务内 DELETE）
            for (String evictedHash : ok.evictedHashes()) {
                storage.deleteFileOnly(evictedHash);
            }
            finalRow = candidate;
        } finally {
            hashLock.unlock();
        }

        // M16 P6.4：上传成功 audit（磁盘写敏感操作，必须留痕便于审计 / 异常排查）
        auditUploadOk(uploader, uploaderName, sessionId, hash, pngLen);

        ctx.status(200).json(Map.of(
                "source", hash,
                "width", finalRow.width(),
                "height", finalRow.height(),
                "bytes", pngLen));
    }

    // ---------- GET /api/upload/{source} ----------

    /**
     * M16 P1.1：要求 query string {@code ?sessionId=<id>} 校验，sessionId 只有通过 WS auth 才能拿到
     * （契约见 {@code docs/protocol.md §3}），等价于 AUTHED 状态。修复任何人拿到 hash 即可
     * 下载图片的 IDOR 风险（{@code docs/security.md §4.5}）。
     *
     * <p>失败统一 401 + {@code {"code":"UNAUTHORIZED"}}，不 echo 内部异常。</p>
     */
    public void handleDownload(Context ctx) {
        // M16 P1.1：sessionId query 参数 → resolveSession 校验。等价于 POST /api/upload 的鉴权方式
        String sessionId = ctx.queryParam("sessionId");
        if (sessionId == null || sessionId.isBlank() || sessionManager.byId(sessionId) == null) {
            ctx.status(401).json(Map.of("code", "UNAUTHORIZED"));
            return;
        }

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
        // hash 唯一对应内容 → 强缓存 + immutable；但因 URL 带 sessionId 鉴权，
        // 改 private（防止被中间代理跨用户缓存命中泄露内容）
        ctx.header("Cache-Control", "private, max-age=31536000, immutable");
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
     * M16 P6.4：成功上传 audit。包含 uploader / sha16 / bytes；不含文件名 / 内容
     * （内容寻址下 hash 已足够定位文件）。
     */
    private void auditUploadOk(UUID uploader, String uploaderName, String sessionId,
                               String sha16, long bytes) {
        if (auditLog == null) return;
        java.util.LinkedHashMap<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("sha16", sha16);
        details.put("bytes", bytes);
        auditLog.record("IMAGE_UPLOAD_OK",
                uploader == null ? null : uploader.toString(),
                uploaderName, sessionId, null, details);
    }

    /**
     * M16 P6.4：拒绝上传 audit。{@code reason} 是稳定枚举 token（如 {@code size_exceeded}），
     * 不含文件名 / 路径 / IO 异常 message 等敏感数据。
     */
    private void auditUploadRejected(UUID uploader, String uploaderName, String sessionId,
                                     String reason) {
        if (auditLog == null) return;
        java.util.LinkedHashMap<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("reason", reason);
        auditLog.record("IMAGE_UPLOAD_REJECTED",
                uploader == null ? null : uploader.toString(),
                uploaderName, sessionId, null, details);
    }

    /**
     * 在 {@link #decoderPool} 上单独线程跑 {@link ImageIO#read(InputStream)}，限时
     * {@value #IMAGEIO_TIMEOUT_MS} ms；超时 cancel 抛 {@link TimeoutException}。
     */
    private BufferedImage decodeWithTimeout(byte[] bytes) throws Exception {
        Future<BufferedImage> fut;
        try {
            fut = decoderPool.submit(
                    (Callable<BufferedImage>) () -> ImageIO.read(new ByteArrayInputStream(bytes)));
        } catch (java.util.concurrent.RejectedExecutionException ree) {
            // M15.1 P0-13：bounded pool 满 → AbortPolicy 抛 → 转成上层可识别的 IOException。
            throw new IOException("upload decoder busy; retry later", ree);
        }
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
