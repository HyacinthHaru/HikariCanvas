package ac.haru.hikaricanvas.image;

import ac.haru.hikaricanvas.HikariCanvasConfig;
import ac.haru.hikaricanvas.session.Session;
import ac.haru.hikaricanvas.session.SessionManager;
import ac.haru.hikaricanvas.state.ImageElement;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.storage.AuditLog;
import ac.haru.hikaricanvas.storage.ImageUploadDao;
import ac.haru.hikaricanvas.storage.WallRepo;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.transaction.TransactionIsolationLevel;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code POST /api/upload} 与 {@code POST /api/upload/url} 处理器：6 层校验栈 + 三层配额。
 *
 * <h2>校验栈</h2>
 * <ol>
 *   <li>auth + permission：{@code X-Canvas-Session} header（或 query / multipart 字段）定位
 *       {@link Session} → 校验会话级 IP 绑定（{@code docs/security.md §2.5}）→ 一次主线程 hop 查
 *       {@code canvas.upload} 与 {@code canvas.upload.bypass-limit}</li>
 *   <li>大小：字节数 ≤ {@code max-size-kb}</li>
 *   <li>Content-Type：客户端声明 MIME ∈ {@code allowed-mime} 且本 JVM 安装了对应 reader</li>
 *   <li>magic bytes：实际文件前 12 字节匹配 MIME（{@link ImageDecodeGuard#detectMagicMime}）</li>
 *   <li>ImageIO 隔离解码：{@link ImageDecodeGuard#decodeWithTimeout} 200ms 协作式超时 + 8192px 头部预检</li>
 *   <li>bbox sanity：{@code 0 < w/h ≤ 8192}；边长 &gt; {@code downscale-max-edge} 自动 bilinear 缩放</li>
 *   <li>配额三层：{@link ImageQuotaService#tryReserveQuotaOn}；磁盘超限 → 事务内 LRU evict</li>
 * </ol>
 *
 * <h2>响应</h2>
 * 成功：{@code 200 + { source, width, height, bytes }}（{@code source} 是 sha256[:16] hash）。
 * 失败：{@code 400 / 401 / 403 / 413 / 415 / 429} + JSON {@code { error, message }}。
 */
public final class UploadHandler {

    private final Logger log;
    private final ImageStorage storage;
    private final ImageQuotaService quota;
    private final ImageUploadDao imageDao;
    private final Jdbi jdbi;
    private final HikariCanvasConfig.ImageConfig cfg;
    private final SessionManager sessionManager;
    private final WallRepo wallRepo;
    private final AuditLog auditLog;
    private final ImageDecodeGuard decodeGuard;

    public UploadHandler(Logger log,
                         ImageStorage storage, ImageQuotaService quota,
                         ImageUploadDao imageDao, Jdbi jdbi,
                         HikariCanvasConfig.ImageConfig cfg,
                         SessionManager sessionManager,
                         WallRepo wallRepo,
                         AuditLog auditLog) {
        this.log = log;
        this.storage = storage;
        this.quota = quota;
        this.imageDao = imageDao;
        this.jdbi = jdbi;
        this.cfg = cfg;
        this.sessionManager = sessionManager;
        this.wallRepo = wallRepo;
        this.auditLog = auditLog;
        this.decodeGuard = new ImageDecodeGuard();
    }

    public void shutdown() {
        decodeGuard.shutdown();
    }

    // ---------- POST /api/upload/url ----------

    /** URL fetch 硬上限：10 MB。超出立即 abort。 */
    private static final long URL_MAX_BYTES = 10L * 1024 * 1024;
    /** URL fetch 总超时：30s。建立连接 + 读取合计。 */
    private static final int URL_TIMEOUT_MS = 30_000;

    /**
     * URL 粘贴上传。请求 body：{@code {"url": "https://..."}}（JSON）。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>sessionId + 会话级 IP 绑定校验（同 file upload）</li>
     *   <li>{@link UrlFetchSafety#check}：绝对 URI 语法 + {@code http(s)} scheme 白名单校验
     *       （不做 DNS / 内网地址黑名单过滤，外联风险由服主承担，见 {@link UrlFetchSafety}）</li>
     *   <li>{@link HttpURLConnection} GET，30s 连接 / 读取超时，禁跟随重定向，10 MB 流式读取上限</li>
     *   <li>校验 {@code images.max-size-kb} 后进入与文件上传共享的校验与落盘管线</li>
     * </ol>
     */
    public void handleUrlUpload(Context ctx) {
        String sessionId = sessionIdOf(ctx, true);
        Session session = authSession(ctx, sessionId);
        if (session == null) return;
        UUID uploader = session.playerUuid();
        String uploaderName = session.playerName();
        UploadPerms perms = resolveUploadPerms(session);
        if (!perms.canUpload()) {
            auditUploadRejected(uploader, uploaderName, sessionId, "missing_permission_url");
            reject(ctx, 403, "FORBIDDEN", "missing canvas.upload permission");
            return;
        }
        boolean bypass = perms.bypassLimit();

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

        // 3. URI 语法与 http(s) scheme 校验
        UrlFetchSafety.CheckResult safety = UrlFetchSafety.check(url);
        if (!safety.ok()) {
            // 错误脱敏 — 不 echo URL 内容（可能含 token / id）
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
        HttpURLConnection conn = null;
        try {
            URL netUrl = URI.create(url).toURL();
            conn = (HttpURLConnection) netUrl.openConnection();
            conn.setConnectTimeout(URL_TIMEOUT_MS / 2);
            conn.setReadTimeout(URL_TIMEOUT_MS / 2);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "HikariCanvas");
            conn.setRequestProperty("Accept", "image/png,image/jpeg,image/webp");
            int sc = conn.getResponseCode();
            if (sc < 200 || sc >= 300) {
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
            if (!canDecodeMime(declaredMime)) {
                auditUploadRejected(uploader, uploaderName, sessionId, "url_mime_no_decoder");
                reject(ctx, 415, "UPLOAD_REJECTED", noDecoderMessage(declaredMime));
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
                 ByteArrayOutputStream baos = new ByteArrayOutputStream(
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
        } catch (SocketTimeoutException ste) {
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

        long maxBytes = (long) cfg.maxSizeKb() * 1024L;
        if (maxBytes > 0 && bytes.length > maxBytes) {
            auditUploadRejected(uploader, uploaderName, sessionId, "size_exceeded_url");
            reject(ctx, 413, "UPLOAD_REJECTED",
                    "file too large: " + bytes.length + " > " + maxBytes + " bytes");
            return;
        }

        // 5. 走共享的解码、缩放、配额与持久化管线
        ingestUploadedBytes(ctx, bytes, declaredMime, uploader, uploaderName, sessionId, bypass, "_url");
    }

    // ---------- POST /api/upload ----------

    /**
     * 处理 multipart 文件上传请求。所有错误路径统一通过 {@link #reject} 写状态 + JSON。
     */
    public void handleUpload(Context ctx) {
        // 1. sessionId + permission（离线玩家拿不到 live Player，fail-closed 拒绝）
        String sessionId = sessionIdOf(ctx, true);
        Session session = authSession(ctx, sessionId);
        if (session == null) return;
        UUID uploader = session.playerUuid();
        String uploaderName = session.playerName();
        UploadPerms perms = resolveUploadPerms(session);
        if (!perms.canUpload()) {
            auditUploadRejected(uploader, uploaderName, sessionId, "missing_permission");
            reject(ctx, 403, "FORBIDDEN", "missing canvas.upload permission");
            return;
        }
        boolean bypass = perms.bypassLimit();

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
        int sep = declaredMime.indexOf(';');
        if (sep > 0) declaredMime = declaredMime.substring(0, sep).trim();
        if (!cfg.allowedMime().contains(declaredMime)) {
            auditUploadRejected(uploader, uploaderName, sessionId, "mime_not_allowed");
            reject(ctx, 415, "UPLOAD_REJECTED",
                    "Content-Type not allowed: " + declaredMime);
            return;
        }
        if (!canDecodeMime(declaredMime)) {
            auditUploadRejected(uploader, uploaderName, sessionId, "mime_no_decoder");
            reject(ctx, 415, "UPLOAD_REJECTED", noDecoderMessage(declaredMime));
            return;
        }

        // 4. 读 bytes
        byte[] bytes;
        try (InputStream in = file.content()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            log.log(Level.WARNING, "upload read failed", e);
            auditUploadRejected(uploader, uploaderName, sessionId, "read_failed");
            reject(ctx, 400, "UPLOAD_REJECTED", "failed to read uploaded file");
            return;
        }

        // 5. 走共享的解码、缩放、配额与持久化管线
        ingestUploadedBytes(ctx, bytes, declaredMime, uploader, uploaderName, sessionId, bypass, "");
    }

    /**
     * 文件上传与 URL 上传共用的后半段管线：magic bytes 校验 → 隔离解码 → JPEG EXIF 旋转 →
     * bbox 校验与 bilinear 缩放 → PNG 编码与 sha256[:16] 计算 → 单事务配额/LRU 预留 → 原子写盘。
     */
    private void ingestUploadedBytes(Context ctx, byte[] bytes, String declaredMime,
                                     UUID uploader, String uploaderName,
                                     String sessionId, boolean bypass, String auditSuffix) {
        String actualMime = ImageDecodeGuard.detectMagicMime(bytes);
        if (actualMime == null || !actualMime.equals(declaredMime)) {
            auditUploadRejected(uploader, uploaderName, sessionId, "magic_mismatch" + auditSuffix);
            reject(ctx, 400, "UPLOAD_REJECTED",
                    "magic bytes mismatch: declared=" + declaredMime + " actual=" + actualMime);
            return;
        }

        BufferedImage decoded;
        try {
            decoded = decodeGuard.decodeWithTimeout(bytes);
        } catch (TimeoutException te) {
            auditUploadRejected(uploader, uploaderName, sessionId, "decode_timeout" + auditSuffix);
            reject(ctx, 400, "UPLOAD_REJECTED", "decode timeout (possible decompression bomb)");
            return;
        } catch (Exception e) {
            log.log(Level.WARNING, "image decode failed", e);
            auditUploadRejected(uploader, uploaderName, sessionId, "decode_failed" + auditSuffix);
            reject(ctx, 400, "UPLOAD_REJECTED", "failed to decode image");
            return;
        }
        if (decoded == null) {
            auditUploadRejected(uploader, uploaderName, sessionId, "decode_null" + auditSuffix);
            reject(ctx, 400, "UPLOAD_REJECTED", "ImageIO returned null (unsupported format?)");
            return;
        }

        if ("image/jpeg".equals(actualMime)) {
            decoded = ExifOrientation.autoRotateJpeg(bytes, decoded, log);
        }

        int w = decoded.getWidth();
        int h = decoded.getHeight();
        if (w <= 0 || h <= 0 || w > ImageDecodeGuard.BBOX_MAX_EDGE || h > ImageDecodeGuard.BBOX_MAX_EDGE) {
            auditUploadRejected(uploader, uploaderName, sessionId, "bbox_out_of_range" + auditSuffix);
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
            log.log(Level.WARNING, "PNG encode failed", e);
            auditUploadRejected(uploader, uploaderName, sessionId, "encode_failed" + auditSuffix);
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
                    hash, pngLen,
                    decoded.getWidth(), decoded.getHeight(),
                    "image/png", uploader,
                    System.currentTimeMillis(), System.currentTimeMillis());
            try {
                qr = jdbi.inTransaction(TransactionIsolationLevel.SERIALIZABLE, handle -> {
                    // 先发一条 no-op UPDATE 将事务升级到 RESERVED 写锁，避免并发先读后写撞 SQLITE_BUSY。
                    handle.execute("UPDATE image_uploads SET last_used_at = last_used_at "
                            + "WHERE hash = '__locker__'");
                    // referenced 集合在持写锁的事务内惰性求值，仅在需要 LRU evict 时扫 walls 表。
                    Optional<ImageUploadDao.Row> existing = imageDao.findByHashOn(handle, hash);
                    return quota.tryReserveQuotaOn(
                            handle, uploader, candidate, pngLen,
                            existing.isPresent(),
                            () -> storage.collectReferencedHashesOn(handle, wallRepo), bypass);
                });
            } catch (Exception e) {
                log.log(Level.WARNING, "upload transaction failed", e);
                auditUploadRejected(uploader, uploaderName, sessionId, "persist_failed" + auditSuffix);
                reject(ctx, 500, "INTERNAL_ERROR", "persist failed");
                return;
            }

            if (qr instanceof ImageQuotaService.DeniedPerDay dp) {
                auditUploadRejected(uploader, uploaderName, sessionId, "quota_per_day" + auditSuffix);
                reject(ctx, 429, "QUOTA_EXCEEDED",
                        "per-day upload limit reached: " + dp.currentCount() + "/" + dp.limit());
                return;
            }
            if (qr instanceof ImageQuotaService.DeniedDiskAfterLru dd) {
                // 事务已 COMMIT，已 DELETE 的 LRU victim 行对应的磁盘文件须一并删除。
                for (String evictedHash : dd.evictedHashes()) {
                    storage.deleteFileOnly(evictedHash);
                }
                auditUploadRejected(uploader, uploaderName, sessionId, "quota_disk" + auditSuffix);
                reject(ctx, 413, "QUOTA_EXCEEDED_DISK",
                        "disk full; cannot free " + dd.bytesShort() + " more bytes");
                return;
            }

            ImageQuotaService.Reserved ok = (ImageQuotaService.Reserved) qr;
            if (ok.inserted()) {
                try {
                    storage.writeFileAtomic(hash, pngBytes);
                } catch (IOException e) {
                    log.log(Level.WARNING, "writeFileAtomic failed; rolling back DB row " + hash, e);
                    try {
                        jdbi.useTransaction(TransactionIsolationLevel.SERIALIZABLE, handle ->
                                imageDao.deleteOn(handle, hash));
                    } catch (Exception rb) {
                        log.log(Level.SEVERE,
                                "compensation DELETE failed; orphan DB row may remain: " + hash, rb);
                    }
                    for (String evictedHash : ok.evictedHashes()) {
                        storage.deleteFileOnly(evictedHash);
                    }
                    auditUploadRejected(uploader, uploaderName, sessionId, "write_failed" + auditSuffix);
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

    // ---------- GET /api/upload/{source} ----------

    /**
     * 图片读取端点：要求会话鉴权与会话级 IP 绑定（契约见 {@code docs/protocol.md §3} /
     * {@code docs/security.md §4.5}）。失败统一返回 {@code 401 + {"code":"UNAUTHORIZED"}}。
     */
    public void handleDownload(Context ctx) {
        // 该端点被 <img src> 直接使用，sessionId 位于 query string，靠会话级 IP 绑定防止日志泄漏重放。
        String sessionId = sessionIdOf(ctx, false);
        Session downloadSession = sessionId == null || sessionId.isBlank()
                ? null : sessionManager.byId(sessionId);
        if (downloadSession == null || !sessionIpMatches(ctx, sessionId, downloadSession)) {
            ctx.status(401).json(Map.of("code", "UNAUTHORIZED"));
            return;
        }

        String hash = ctx.pathParam("source");
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
        ctx.header("Cache-Control", "private, max-age=31536000, immutable");
        ctx.result(bytes);
    }

    // ---------- GET /api/upload/quota ----------

    public void handleQuota(Context ctx) {
        String sessionId = sessionIdOf(ctx, false);
        Session session = authSession(ctx, sessionId);
        if (session == null) return;
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

    /**
     * HTTP 面的会话鉴权：解析 {@link Session} + 校验会话级 IP 绑定（{@code docs/security.md §2.5}），
     * 防止通过日志或 Referer 泄漏的 sessionId 从其他 IP 重放。失败时已写响应。
     */
    private Session authSession(Context ctx, String sessionId) {
        Session s = resolveSession(ctx, sessionId);
        if (s == null) return null;
        if (!sessionIpMatches(ctx, sessionId, s)) {
            reject(ctx, 401, "AUTH_FAILED", "session ip mismatch");
            return null;
        }
        return s;
    }

    /** 通过 header / query / form 参数解析 {@link Session}；失败时已写响应。 */
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

    /**
     * 会话级 IP 绑定比对（{@code docs/security.md §2.5}）。与 WS 侧共用
     * {@link SessionManager#bindOrCheckIp}，只认 socket peer 地址、不解析 {@code X-Forwarded-For}。
     *
     * <p>本机回环地址族互认（{@code ::1} 与 {@code 127.0.0.1}），避免同机部署时浏览器为 WS 与 HTTP
     * 选用不同回环地址族而被误拒。</p>
     */
    private boolean sessionIpMatches(Context ctx, String sessionId, Session session) {
        String presented = httpClientIp(ctx);
        SessionManager.IpBindResult result = sessionManager.bindOrCheckIp(sessionId, presented);
        if (result != SessionManager.IpBindResult.MISMATCH) return true;
        if (bothLoopback(session.boundIp(), presented)) {
            log.fine("HTTP session ip differs but both are loopback; accepting sid=" + sessionId);
            return true;
        }
        log.warning("HTTP session ip mismatch sid=" + sessionId
                + " boundIp=" + session.boundIp() + " presentedIp=" + presented
                + "; sessionId may have leaked through logs / Referer");
        return false;
    }

    /**
     * 取 sessionId：{@code X-Canvas-Session} header 优先，其次 query，最后 form。
     *
     * @param allowForm 是否读 multipart / form 字段（只对 POST 端点开，GET 上读 form 会去解析 body）
     */
    static String sessionIdOf(Context ctx, boolean allowForm) {
        String header = ctx.header(SESSION_HEADER);
        if (header != null && !header.isBlank()) return header.trim();
        String query = ctx.queryParam("sessionId");
        if (query != null && !query.isBlank()) return query.trim();
        if (!allowForm) return null;
        try {
            String form = ctx.formParam("sessionId");
            return form == null || form.isBlank() ? null : form.trim();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** HTTP 客户端 IP，规范化到与 WS 侧 {@code InetAddress.getHostAddress()} 相同的写法。 */
    private static String httpClientIp(Context ctx) {
        try {
            return normalizeIp(ctx.ip());
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    /**
     * 把各种写法的地址串归一：去 {@code /} 前缀、去 IPv6 方括号、去 IPv4 的 {@code :port}，
     * 再过一遍 {@link InetAddress#getHostAddress()}。输入是地址字面量，不会触发 DNS 查询。
     */
    static String normalizeIp(String raw) {
        if (raw == null || raw.isBlank()) return "unknown";
        String s = raw.trim();
        if (s.startsWith("/")) s = s.substring(1);
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close > 1) s = s.substring(1, close);
        } else if (s.indexOf(':') >= 0 && s.indexOf(':') == s.lastIndexOf(':')) {
            s = s.substring(0, s.indexOf(':'));   // IPv4:port
        }
        if (s.isBlank()) return "unknown";
        try {
            return InetAddress.getByName(s).getHostAddress();
        } catch (UnknownHostException | SecurityException e) {
            return s;
        }
    }

    /** 两个地址是否都是回环（见 {@link #sessionIpMatches} 里的互认说明）。 */
    static boolean bothLoopback(String a, String b) {
        return isLoopback(a) && isLoopback(b);
    }

    private static boolean isLoopback(String ip) {
        if (ip == null || ip.isBlank()) return false;
        try {
            return InetAddress.getByName(normalizeIp(ip)).isLoopbackAddress();
        } catch (UnknownHostException | SecurityException e) {
            return false;
        }
    }

    // ---------- 主线程权限解析（对齐 web.MainThreadPerms 的纪律） ----------

    /** {@code sessionId} 的推荐传递位置：header，避免落进 access log / Referer。 */
    static final String SESSION_HEADER = "X-Canvas-Session";

    /** 主线程权限解析超时；与 WS 侧一致。 */
    private static final long PERM_TIMEOUT_SECONDS = 2L;

    /**
     * 上传相关权限的解析结果。
     *
     * @param online      玩家是否在线
     * @param canUpload   是否持 {@code canvas.upload}
     * @param bypassLimit 是否持 {@code canvas.upload.bypass-limit}
     */
    record UploadPerms(boolean online, boolean canUpload, boolean bypassLimit) {}

    private static final UploadPerms DENY_ALL = new UploadPerms(false, false, false);

    /** 承载本类的插件实例（{@code JavaPlugin.getProvidingPlugin}）；测试环境解析不出来 → null。 */
    private volatile Plugin owningPlugin;
    private volatile boolean owningPluginResolved;

    /**
     * 解析上传相关权限：控制台会话直接授予（见 {@code docs/security.md §5.0}），
     * 玩家会话走 {@link #resolveUploadPerms(UUID)} 主线程 hop。
     */
    private UploadPerms resolveUploadPerms(Session session) {
        if (session == null) return DENY_ALL;
        if (session.isConsole()) return new UploadPerms(true, true, true);
        return resolveUploadPerms(session.playerUuid());
    }

    /**
     * 一次主线程 hop 解析玩家上传权限。
     *
     * <p>{@code Bukkit.getPlayer(UUID)} 与 {@code Player.hasPermission} 均为主线程专用 API；
     * 超时 / 调度器关停 / 中断 / 异常或玩家离线一律 <b>fail-closed</b>（返回 {@link #DENY_ALL}）。</p>
     */
    private UploadPerms resolveUploadPerms(UUID uploader) {
        if (uploader == null) return DENY_ALL;
        Plugin plugin = owningPlugin();
        if (plugin == null) {
            return resolveUploadPermsDirect(uploader);
        }
        try {
            return Bukkit.getScheduler()
                    .callSyncMethod(plugin, () -> resolveUploadPermsDirect(uploader))
                    .get(PERM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return DENY_ALL;
        } catch (TimeoutException | ExecutionException
                | RejectedExecutionException | CancellationException e) {
            log.warning("main-thread permission check failed for upload; denying: " + e);
            return DENY_ALL;
        }
    }

    /** 主线程内（或测试单线程内）直接解析。 */
    private static UploadPerms resolveUploadPermsDirect(UUID uploader) {
        Player p = Bukkit.getPlayer(uploader);
        if (p == null) return DENY_ALL;
        return new UploadPerms(true,
                p.hasPermission("canvas.upload"),
                p.hasPermission("canvas.upload.bypass-limit"));
    }

    /**
     * 懒解析承载本类的插件实例。纯 JVM 单测环境返回 {@code null}，退回直接调用。
     */
    private Plugin owningPlugin() {
        if (owningPluginResolved) return owningPlugin;
        Plugin p = null;
        try {
            p = JavaPlugin.getProvidingPlugin(UploadHandler.class);
        } catch (RuntimeException | LinkageError ignored) {
            // 非插件类加载器（单测）/ Bukkit 未初始化
        }
        owningPlugin = p;
        owningPluginResolved = true;
        return p;
    }

    private void reject(Context ctx, int status, String code, String message) {
        ctx.status(status).json(Map.of("error", code, "message", message == null ? "" : message));
    }

    /**
     * 统计某 wall（{@link Session#projectState()}）全 layer 内 image 元素数，按 {@code source}
     * 去重（{@code docs/data-model.md §2}：同 hash 内容寻址，重复引用同一图只算一份）。
     */
    private int countImageElementsInWall(ProjectState ps) {
        if (ps == null || ps.layers() == null) return 0;
        Set<String> sources = new HashSet<>();
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
     * drain + close 一个 stream（典型为 {@link HttpURLConnection#getErrorStream()}），
     * 避免非 2xx 响应时底层 keep-alive 连接滞留。
     */
    private static void drainAndCloseQuietly(InputStream is) {
        if (is == null) return;
        try (is) {
            byte[] buf = new byte[4096];
            int cap = 64 * 1024;
            int read = 0;
            int n;
            while (read < cap && (n = is.read(buf)) > 0) {
                read += n;
            }
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private void auditUploadOk(UUID uploader, String uploaderName, String sessionId,
                               String sha16, long bytes) {
        if (auditLog == null) return;
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        details.put("sha16", sha16);
        details.put("bytes", bytes);
        auditLog.record("IMAGE_UPLOAD_OK",
                uploader == null ? null : uploader.toString(),
                uploaderName, sessionId, null, details);
    }

    private void auditUploadRejected(UUID uploader, String uploaderName, String sessionId,
                                     String reason) {
        if (auditLog == null) return;
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        auditLog.record("IMAGE_UPLOAD_REJECTED",
                uploader == null ? null : uploader.toString(),
                uploaderName, sessionId, null, details);
    }

    /**
     * 本 JVM 的 {@link ImageIO} 是否存在能解码指定 MIME 的 reader（例如标准 JDK 默认无 WebP reader，
     * 装了第三方 WebP ImageIO 插件时自动放行）。
     */
    static boolean canDecodeMime(String mime) {
        if (mime == null) return false;
        return ImageIO.getImageReadersByMIMEType(mime).hasNext();
    }

    /** 白名单放行但本机没有解码器时的拒绝文案。 */
    private static String noDecoderMessage(String mime) {
        return "this server cannot decode " + mime
                + " (no image reader installed); please upload PNG or JPEG instead";
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
