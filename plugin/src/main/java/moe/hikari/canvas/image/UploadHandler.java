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
import javax.imageio.ImageReader;
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

    // ---------- POST /api/upload/url（2026-05-25 项 3）----------

    /** URL fetch 上限：10 MB。超出立即 abort。 */
    private static final long URL_MAX_BYTES = 10L * 1024 * 1024;
    /** URL fetch 总超时：30s。建立连接 + 读取合计。 */
    private static final int URL_TIMEOUT_MS = 30_000;

    /**
     * 2026-05-25 项 3：URL 粘贴上传。请求 body：{@code {"url": "https://..."}}（JSON）。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>sessionId query 校验（同 file upload）</li>
     *   <li>{@link UrlFetchSafety#check}：SSRF 防御 + http(s) 白名单</li>
     *   <li>HttpURLConnection GET，30s 连接 / 读取超时，10 MB 上限</li>
     *   <li>下载到内存 bytes，走 file upload 同款 6 层校验栈</li>
     * </ol>
     */
    public void handleUrlUpload(io.javalin.http.Context ctx) {
        // 1. sessionId
        String sessionId = ctx.queryParam("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = ctx.formParam("sessionId");  // 兼容 client 用 form 传
        }
        moe.hikari.canvas.session.Session session = resolveSession(ctx, sessionId);
        if (session == null) return;
        UUID uploader = session.playerUuid();
        String uploaderName = session.playerName();
        Player player = Bukkit.getPlayer(uploader);
        // P2-4：权限检查 fail-closed（同 handleUpload）。离线玩家拿不到 live Player → 拒。
        if (player == null || !player.hasPermission("canvas.upload")) {
            auditUploadRejected(uploader, uploaderName, sessionId, "missing_permission_url");
            reject(ctx, 403, "FORBIDDEN", "missing canvas.upload permission");
            return;
        }
        boolean bypass = player.hasPermission("canvas.upload.bypass-limit");

        // 2. body JSON parse
        String url;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            Object u = body == null ? null : body.get("url");
            if (!(u instanceof String us) || us.isBlank()) {
                auditUploadRejected(uploader, uploaderName, sessionId, "missing_url_field");
                reject(ctx, 400, "INVALID_PAYLOAD", "missing 'url' field");
                return;
            }
            url = us.trim();
            if (url.length() > 2048) {
                auditUploadRejected(uploader, uploaderName, sessionId, "url_too_long");
                reject(ctx, 400, "INVALID_PAYLOAD", "url too long");
                return;
            }
        } catch (Exception e) {
            auditUploadRejected(uploader, uploaderName, sessionId, "url_body_parse");
            reject(ctx, 400, "INVALID_PAYLOAD", "invalid JSON body");
            return;
        }

        // 3. SSRF 防御
        UrlFetchSafety.CheckResult safety = UrlFetchSafety.check(url);
        if (!safety.ok()) {
            // M16 P6.1：错误脱敏 — 不 echo URL 内容（可能含 token / id）
            log.warning("url upload rejected: reason=" + safety.reason()
                    + " (url redacted; detail=" + safety.detail() + ")");
            auditUploadRejected(uploader, uploaderName, sessionId,
                    "ssrf_" + safety.reason().name().toLowerCase(Locale.ROOT));
            reject(ctx, 400, "UPLOAD_REJECTED",
                    "URL not allowed (" + safety.reason() + ")");
            return;
        }

        // 4. fetch（带超时 + 字节上限）
        byte[] bytes;
        String declaredMime;
        // P3-33：conn 提到 try 外，统一在 finally 里 disconnect()，避免任一拒绝分支
        // （非 2xx / mime / size / stream 超限）early-return 时 keep-alive 套接字滞留。
        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL netUrl = java.net.URI.create(url).toURL();
            conn = (java.net.HttpURLConnection) netUrl.openConnection();
            conn.setConnectTimeout(URL_TIMEOUT_MS / 2);
            conn.setReadTimeout(URL_TIMEOUT_MS / 2);
            conn.setInstanceFollowRedirects(false);  // 重定向需手工处理 — v1 不跟随防 SSRF
            conn.setRequestProperty("User-Agent", "HikariCanvas-Uploader/0.4.7");
            conn.setRequestProperty("Accept", "image/png,image/jpeg,image/webp");
            int sc = conn.getResponseCode();
            if (sc < 200 || sc >= 300) {
                // P3-33：非 2xx 时 drain + close error stream，避免半态连接占用
                drainAndCloseQuietly(conn.getErrorStream());
                auditUploadRejected(uploader, uploaderName, sessionId, "url_http_" + sc);
                reject(ctx, 502, "UPLOAD_REJECTED", "remote returned status " + sc);
                return;
            }
            declaredMime = conn.getContentType();
            if (declaredMime != null) {
                int sep = declaredMime.indexOf(';');
                if (sep > 0) declaredMime = declaredMime.substring(0, sep).trim();
                declaredMime = declaredMime.toLowerCase(Locale.ROOT);
            }
            if (declaredMime == null || !declaredMime.startsWith("image/")) {
                auditUploadRejected(uploader, uploaderName, sessionId, "url_mime_not_image");
                reject(ctx, 415, "UPLOAD_REJECTED",
                        "remote Content-Type is not image/*: " + declaredMime);
                return;
            }
            if (!cfg.allowedMime().contains(declaredMime)) {
                auditUploadRejected(uploader, uploaderName, sessionId, "url_mime_not_allowed");
                reject(ctx, 415, "UPLOAD_REJECTED",
                        "Content-Type not allowed: " + declaredMime);
                return;
            }
            long advertised = conn.getContentLengthLong();
            if (advertised > 0 && advertised > URL_MAX_BYTES) {
                auditUploadRejected(uploader, uploaderName, sessionId, "url_too_large_advertised");
                reject(ctx, 413, "UPLOAD_REJECTED",
                        "remote file too large: " + advertised + " > " + URL_MAX_BYTES);
                return;
            }
            try (InputStream is = conn.getInputStream();
                 java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(
                         advertised > 0 ? Math.min((int) advertised, 1 << 20) : 1 << 16)) {
                byte[] buf = new byte[8192];
                int n;
                long total = 0;
                while ((n = is.read(buf)) > 0) {
                    total += n;
                    if (total > URL_MAX_BYTES) {
                        auditUploadRejected(uploader, uploaderName, sessionId, "url_too_large_stream");
                        reject(ctx, 413, "UPLOAD_REJECTED",
                                "remote file too large (>" + URL_MAX_BYTES + " bytes)");
                        return;
                    }
                    baos.write(buf, 0, n);
                }
                bytes = baos.toByteArray();
            }
        } catch (java.net.SocketTimeoutException ste) {
            auditUploadRejected(uploader, uploaderName, sessionId, "url_timeout");
            reject(ctx, 504, "UPLOAD_REJECTED", "remote fetch timeout");
            return;
        } catch (Exception e) {
            log.log(Level.WARNING, "url fetch failed", e);
            auditUploadRejected(uploader, uploaderName, sessionId, "url_fetch_failed");
            reject(ctx, 502, "UPLOAD_REJECTED", "failed to fetch URL");
            return;
        } finally {
            if (conn != null) conn.disconnect();
        }
        if (bytes == null || bytes.length == 0) {
            auditUploadRejected(uploader, uploaderName, sessionId, "url_empty");
            reject(ctx, 400, "UPLOAD_REJECTED", "remote returned empty body");
            return;
        }

        // 5. 走相同 6 层校验栈（提取为 helper 复用 file path）
        processDownloadedBytes(ctx, bytes, declaredMime, uploader, uploaderName, sessionId, bypass);
    }

    /**
     * 2026-05-25 项 3：从 file upload 与 URL upload 共用的"已得到 bytes 之后"的处理路径。
     * 校验 magic bytes / decode / EXIF / bbox / quota / 持久化。
     */
    private void processDownloadedBytes(io.javalin.http.Context ctx, byte[] bytes,
                                         String declaredMime, UUID uploader,
                                         String uploaderName, String sessionId, boolean bypass) {
        // P2-55：URL 路径补 max-size-kb 配额（与 file 路径 handleUpload 等价）。原先 URL 路径
        // 仅受硬编码 URL_MAX_BYTES=10MB 约束，绕过了可配的 images.max-size-kb（默认 2MB），
        // 使该配置维度对 URL 入口形同虚设、与 file 路径行为不一致。这里以 cfg.maxSizeKb 为准。
        long maxBytes = (long) cfg.maxSizeKb() * 1024L;
        if (maxBytes > 0 && bytes.length > maxBytes) {
            auditUploadRejected(uploader, uploaderName, sessionId, "size_exceeded_url");
            reject(ctx, 413, "UPLOAD_REJECTED",
                    "file too large: " + bytes.length + " > " + maxBytes + " bytes");
            return;
        }

        // magic bytes
        String actualMime = detectMagicMime(bytes);
        if (actualMime == null || !actualMime.equals(declaredMime)) {
            auditUploadRejected(uploader, uploaderName, sessionId, "magic_mismatch_url");
            reject(ctx, 400, "UPLOAD_REJECTED",
                    "magic bytes mismatch: declared=" + declaredMime + " actual=" + actualMime);
            return;
        }

        // decode
        BufferedImage decoded;
        try {
            decoded = decodeWithTimeout(bytes);
        } catch (TimeoutException te) {
            auditUploadRejected(uploader, uploaderName, sessionId, "decode_timeout_url");
            reject(ctx, 400, "UPLOAD_REJECTED", "decode timeout");
            return;
        } catch (Exception e) {
            log.log(Level.WARNING, "url image decode failed", e);
            auditUploadRejected(uploader, uploaderName, sessionId, "decode_failed_url");
            reject(ctx, 400, "UPLOAD_REJECTED", "failed to decode image");
            return;
        }
        if (decoded == null) {
            auditUploadRejected(uploader, uploaderName, sessionId, "decode_null_url");
            reject(ctx, 400, "UPLOAD_REJECTED", "ImageIO returned null");
            return;
        }
        if ("image/jpeg".equals(actualMime)) {
            decoded = ExifOrientation.autoRotateJpeg(bytes, decoded, log);
        }

        int w = decoded.getWidth();
        int h = decoded.getHeight();
        if (w <= 0 || h <= 0 || w > BBOX_MAX_EDGE || h > BBOX_MAX_EDGE) {
            auditUploadRejected(uploader, uploaderName, sessionId, "bbox_out_of_range_url");
            reject(ctx, 400, "UPLOAD_REJECTED", "bbox out of range: " + w + "x" + h);
            return;
        }
        int maxEdge = cfg.downscaleMaxEdge();
        if (Math.max(w, h) > maxEdge) {
            decoded = downscale(decoded, maxEdge);
        }

        byte[] pngBytes;
        try {
            pngBytes = ImageStorage.encodePng(decoded);
        } catch (IOException e) {
            log.log(Level.WARNING, "url PNG encode failed", e);
            auditUploadRejected(uploader, uploaderName, sessionId, "encode_failed_url");
            reject(ctx, 500, "INTERNAL_ERROR", "encode failed");
            return;
        }
        String hash = ImageStorage.sha256Hex16(pngBytes);
        long pngLen = pngBytes.length;

        ReentrantLock hashLock = storage.writeLockFor(hash);
        hashLock.lock();
        ImageQuotaService.QuotaResult qr;
        ImageUploadDao.Row finalRow;
        try {
            final ImageUploadDao.Row candidate = new ImageUploadDao.Row(
                    hash, pngLen, decoded.getWidth(), decoded.getHeight(),
                    "image/png", uploader,
                    System.currentTimeMillis(), System.currentTimeMillis());
            try {
                qr = jdbi.inTransaction(TransactionIsolationLevel.SERIALIZABLE, handle -> {
                    handle.execute("UPDATE image_uploads SET last_used_at = last_used_at "
                            + "WHERE hash = '__locker__'");
                    // P2-24：referenced 集合在持写锁的事务内 sweep（同一一致视图），消除跨 hash 误删竞态。
                    Set<String> referenced = storage.collectReferencedHashesOn(handle, wallRepo);
                    Optional<ImageUploadDao.Row> existing = imageDao.findByHashOn(handle, hash);
                    return quota.tryReserveQuotaOn(handle, uploader, candidate, pngLen,
                            existing.isPresent(), referenced, bypass);
                });
            } catch (Exception e) {
                log.log(Level.WARNING, "url upload transaction failed", e);
                auditUploadRejected(uploader, uploaderName, sessionId, "persist_failed_url");
                reject(ctx, 500, "INTERNAL_ERROR", "persist failed");
                return;
            }
            if (qr instanceof ImageQuotaService.DeniedPerDay dp) {
                auditUploadRejected(uploader, uploaderName, sessionId, "quota_per_day_url");
                reject(ctx, 429, "QUOTA_EXCEEDED",
                        "per-day upload limit reached: " + dp.currentCount() + "/" + dp.limit());
                return;
            }
            if (qr instanceof ImageQuotaService.DeniedDiskAfterLru dd) {
                auditUploadRejected(uploader, uploaderName, sessionId, "quota_disk_url");
                reject(ctx, 413, "QUOTA_EXCEEDED_DISK",
                        "disk full; cannot free " + dd.bytesShort() + " more bytes");
                return;
            }
            ImageQuotaService.Reserved ok = (ImageQuotaService.Reserved) qr;
            if (ok.inserted()) {
                try {
                    storage.writeFileAtomic(hash, pngBytes);
                } catch (IOException e) {
                    log.log(Level.WARNING, "url writeFileAtomic failed; rolling back " + hash, e);
                    try {
                        jdbi.useTransaction(TransactionIsolationLevel.SERIALIZABLE,
                                handle -> imageDao.deleteOn(handle, hash));
                    } catch (Exception rb) {
                        log.log(Level.SEVERE,
                                "compensation DELETE failed; orphan DB row: " + hash, rb);
                    }
                    // P1-1：本次写盘失败回滚了新行，但事务内已 DELETE 的 LRU victim 行不会
                    // 随之复活（外层 transaction 已 commit）。必须把它们的磁盘文件一并删掉，
                    // 否则物理孤儿（DB 行没了、文件还在）令 sumBytes 与真实磁盘永久漂移。
                    for (String evictedHash : ok.evictedHashes()) {
                        storage.deleteFileOnly(evictedHash);
                    }
                    auditUploadRejected(uploader, uploaderName, sessionId, "write_failed_url");
                    reject(ctx, 500, "INTERNAL_ERROR", "persist failed");
                    return;
                }
            }
            for (String evictedHash : ok.evictedHashes()) {
                storage.deleteFileOnly(evictedHash);
            }
            finalRow = candidate;
        } finally {
            hashLock.unlock();
        }

        auditUploadOk(uploader, uploaderName, sessionId, hash, pngLen);
        ctx.status(200).json(Map.of(
                "source", hash,
                "width", finalRow.width(),
                "height", finalRow.height(),
                "bytes", pngLen));
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
        // P2-4：权限检查 fail-closed。player == null（玩家离线）时不能 fail-open 放行 ——
        // 与 WallOpDispatcher.wall.alias / SessionManager.open 同款离线处理：拿不到 live
        // Player 即视为无 canvas.upload 权限直接拒（base 权限 fail-closed，bypass 权限同样
        // 只在 live Player 上判定）。
        if (player == null || !player.hasPermission("canvas.upload")) {
            auditUploadRejected(uploader, uploaderName, sessionId, "missing_permission");
            reject(ctx, 403, "FORBIDDEN", "missing canvas.upload permission");
            return;
        }
        boolean bypass = player.hasPermission("canvas.upload.bypass-limit");

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

        // 5.5. 2026-05-25 项 4：JPEG EXIF Orientation 自动旋转（手机照片常见 90°/180°/270°）。
        // PNG / WebP 无 EXIF；ExifOrientation.read 走 JPEG magic 快速路径 fail-soft return 1。
        if ("image/jpeg".equals(actualMime)) {
            decoded = ExifOrientation.autoRotateJpeg(bytes, decoded, log);
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
            try {
                qr = jdbi.inTransaction(TransactionIsolationLevel.SERIALIZABLE, handle -> {
                    // P2.1：SQLite 下用 SAVEPOINT-style 不行；这里通过先发一条 no-op
                    // UPDATE（影响 0 行也持锁）把事务从 DEFERRED 升级到 RESERVED 写锁，
                    // 避免并发两个 caller 都先 read 然后争 write 撞 SQLITE_BUSY。
                    // 配合 Database busy_timeout=5000 兜底。
                    handle.execute("UPDATE image_uploads SET last_used_at = last_used_at "
                            + "WHERE hash = '__locker__'");
                    // P2-24：referenced 集合在持写锁的事务内 sweep（同一一致视图），事务内用作
                    // LRU 排除集——消除"事务外快照、事务内 evict"的跨 hash 误删竞态。
                    Set<String> referenced = storage.collectReferencedHashesOn(handle, wallRepo);
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
                    // P1-1：事务已 commit，LRU victim 行已不在 DB；写新文件失败只回滚了新行，
                    // 那些 victim 的磁盘文件必须同步删除，否则物理孤儿令磁盘配额永久漂移
                    // （sumBytes 看不到该行，但文件占着空间），且回归到 P1-1 描述的"误删他人
                    // 图记录 + 文件残留"双重不一致。
                    for (String evictedHash : ok.evictedHashes()) {
                        storage.deleteFileOnly(evictedHash);
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
        // P2-8：上报真实 per-wall image 元素数（按 source 去重，依 data-model.md §2），
        // 取代原硬编码 0——让 perWall.used 对前端有意义（之前恒报 0/limit）。
        ImageQuotaService.Summary s =
                quota.remaining(session.playerUuid(), countImageElementsInWall(session.projectState()));
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
     * P2-8：统计某 wall（{@link Session#projectState()}）全 layer 内 image 元素数，按 {@code source}
     * 去重（data-model.md §2：同 hash 内容寻址，重复引用同一图只算一份）。null source 不计。
     * projectState 为 null（尚未 confirm / SELECTING 态）时返 0。
     */
    private int countImageElementsInWall(moe.hikari.canvas.state.ProjectState ps) {
        if (ps == null || ps.layers() == null) return 0;
        Set<String> sources = new java.util.HashSet<>();
        for (var layer : ps.layers()) {
            for (var el : layer.elements()) {
                if (el instanceof ImageElement im && im.source() != null) {
                    sources.add(im.source());
                }
            }
        }
        return sources.size();
    }

    /**
     * P3-33：drain + close 一个 stream（典型为 {@link java.net.HttpURLConnection#getErrorStream()}）。
     * 非 2xx 响应时不读完 error body 会让底层 keep-alive 连接半态滞留 / 无法复用。null / 异常均吞掉。
     */
    private static void drainAndCloseQuietly(InputStream is) {
        if (is == null) return;
        try (is) {
            byte[] buf = new byte[4096];
            int cap = 64 * 1024;  // 最多 drain 64KB error body，足够触发连接复用
            int read = 0;
            int n;
            while (read < cap && (n = is.read(buf)) > 0) {
                read += n;
            }
        } catch (IOException ignored) {
            // best-effort
        }
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
     * 在 {@link #decoderPool} 上单独线程解码，限时 {@value #IMAGEIO_TIMEOUT_MS} ms；
     * 超时抛 {@link TimeoutException}。
     *
     * <p>P2-46：{@code Future.cancel(true)} 只发 {@link Thread#interrupt()}，对 ImageIO 内部
     * 解码循环大多无效（卡死线程永久占满 core=max=2 的有界池）。改走 {@link ImageReader} 的
     * <b>协作式中止</b>：解码任务把 reader 发布到共享引用，超时时主线程跨线程调
     * {@link ImageReader#abort()}（ImageIO 唯一可靠的中止点），interrupt 仅作 backstop。</p>
     */
    private BufferedImage decodeWithTimeout(byte[] bytes) throws Exception {
        final java.util.concurrent.atomic.AtomicReference<ImageReader> readerRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        Future<BufferedImage> fut;
        try {
            fut = decoderPool.submit((Callable<BufferedImage>) () ->
                    decodeCooperative(bytes, readerRef));
        } catch (java.util.concurrent.RejectedExecutionException ree) {
            // M15.1 P0-13：bounded pool 满 → AbortPolicy 抛 → 转成上层可识别的 IOException。
            throw new IOException("upload decoder busy; retry later", ree);
        }
        try {
            return fut.get(IMAGEIO_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            // P2-46：先协作式 abort（reader.abort 跨线程合法，是 ImageIO 实际会响应的中止），
            // 再 interrupt 作 backstop。abort 让卡死的解码循环尽快退出归还有界池线程。
            ImageReader r = readerRef.get();
            if (r != null) {
                try {
                    r.abort();
                } catch (RuntimeException ignored) {
                    // best-effort
                }
            }
            fut.cancel(true);
            throw te;
        }
    }

    /**
     * P2-46：用 {@link ImageReader} 解码并把 reader 发布到 {@code readerRef}，使外部（超时）线程
     * 能调 {@link ImageReader#abort()} 协作式中止。失败 / 无 reader 时 fail-soft 退回
     * {@link ImageIO#read(InputStream)}（仍受外层 Future 超时 + interrupt 约束）。
     */
    private static BufferedImage decodeCooperative(
            byte[] bytes,
            java.util.concurrent.atomic.AtomicReference<ImageReader> readerRef) throws IOException {
        try (javax.imageio.stream.ImageInputStream iis =
                     ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (iis == null) {
                return ImageIO.read(new ByteArrayInputStream(bytes));
            }
            java.util.Iterator<ImageReader> it = ImageIO.getImageReaders(iis);
            if (!it.hasNext()) return null;  // 无 reader = 不支持的格式（同 ImageIO.read 返 null 语义）
            ImageReader reader = it.next();
            readerRef.set(reader);
            try {
                reader.setInput(iis, true, true);
                // B2：解码前读头部尺寸（getWidth/getHeight 只解析头，不解码全图），
                // 拦截"小体积但巨尺寸"的分配型炸弹（如 30000×30000 声明 → read(0) 分配 GB raster）
                int pw = reader.getWidth(0);
                int ph = reader.getHeight(0);
                if (pw > BBOX_MAX_EDGE || ph > BBOX_MAX_EDGE) {
                    throw new IOException("image dimensions too large: " + pw + "x" + ph
                            + " > " + BBOX_MAX_EDGE);
                }
                return reader.read(0);
            } catch (javax.imageio.IIOException abortOrFail) {
                // abort() 触发的提前结束在多数 reader 下抛 IIOException("aborted") →
                // 由外层 TimeoutException 路径处理，这里转成 IOException 让上层走 decode_failed/timeout。
                throw new IOException("decode aborted or failed", abortOrFail);
            } finally {
                readerRef.set(null);
                reader.dispose();
            }
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
