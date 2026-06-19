package moe.hikari.canvas.canvasfile;

import moe.hikari.canvas.HikariCanvasConfig;
import moe.hikari.canvas.image.ImageQuotaService;
import moe.hikari.canvas.image.ImageStorage;
import moe.hikari.canvas.storage.ImageUploadDao;
import moe.hikari.canvas.storage.WallRepo;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.transaction.TransactionIsolationLevel;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 0.8-A2 Task 11：把导入的 {@code assets/<hash>.png} 逐张安全摄入到图片存储。
 *
 * <p>每张图走与上传<b>同等</b>的不可信防御链（对照 {@code UploadHandler.processDownloadedBytes}
 * 与配额事务块 {@code UploadHandler.java:572-657}）：
 * <ol>
 *   <li>magic bytes 校验（非 PNG → 跳过）；</li>
 *   <li>ImageIO 隔离解码（独立线程 + {@value #DECODE_TIMEOUT_MS}ms 超时 + 解码前头部尺寸预检
 *       拦截分配型炸弹；解码失败 / 超时 / 超尺寸 → 跳过）；</li>
 *   <li>{@code encodePng} 规范化 + {@code sha256Hex16} <b>按内容重算 hash</b>（不信文件名）；</li>
 *   <li>per-hash 锁 + SERIALIZABLE 配额事务（{@code tryReserveQuotaOn}）；配额拒
 *       （{@code DeniedPerDay}/{@code DeniedDiskAfterLru}）→ 跳过该张；</li>
 *   <li>事务 commit 后原子落盘 + 清 LRU 已 evict 的孤儿文件。</li>
 * </ol>
 * 任何单张失败<b>只跳过该张、不抛异常、不中止整体导入</b>。
 *
 * <p>{@code detectMagicMime}/{@code decodeWithTimeout} 在 {@code image} 包内是
 * package-private / private，跨包不可复用，故此处写等价实现（保留 200ms 隔离 + 8192 预检）。</p>
 */
public final class AssetIngest {

    /** 同 {@code UploadHandler.IMAGEIO_TIMEOUT_MS}：单张解码硬超时，防压缩炸弹卡死。 */
    private static final long DECODE_TIMEOUT_MS = 200L;
    /** 同 {@code UploadHandler.BBOX_MAX_EDGE}：解码前头部尺寸上限，拦"小体积巨尺寸"分配炸弹。 */
    private static final int BBOX_MAX_EDGE = 8192;

    private final Logger log;
    private final ImageStorage storage;
    private final ImageQuotaService quota;
    private final ImageUploadDao imageDao;
    private final WallRepo wallRepo;
    private final Jdbi jdbi;
    private final ExecutorService decoderPool;

    public AssetIngest(Logger log,
                       ImageStorage storage,
                       ImageQuotaService quota,
                       ImageUploadDao imageDao,
                       WallRepo wallRepo,
                       Jdbi jdbi) {
        this.log = log;
        this.storage = storage;
        this.quota = quota;
        this.imageDao = imageDao;
        this.wallRepo = wallRepo;
        this.jdbi = jdbi;
        this.decoderPool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "hc-asset-decoder");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 摄入所有 {@code assets/<file>.png} 条目（其余 key 忽略），不带配额 bypass。
     *
     * @param assets   解包后的条目 map（key = zip 内路径，value = 原始字节）
     * @param uploader 导入者 uuid（落盘 row 的 owner）
     * @return 成功落盘的 hash 集合（按内容重算，已去重）
     */
    public Set<String> ingestAll(Map<String, byte[]> assets, UUID uploader) {
        return ingestAll(assets, uploader, false);
    }

    /**
     * @param bypass 配额 bypass（对应 {@code canvas.upload.bypass-limit} 权限；导入侧一般 false）
     */
    public Set<String> ingestAll(Map<String, byte[]> assets, UUID uploader, boolean bypass) {
        Set<String> stored = new LinkedHashSet<>();
        if (assets == null || assets.isEmpty()) return stored;

        for (Map.Entry<String, byte[]> e : assets.entrySet()) {
            String name = e.getKey();
            if (!isAssetPng(name)) continue;
            byte[] raw = e.getValue();
            String hash = ingestOne(raw, uploader, bypass);
            if (hash != null) stored.add(hash);
        }
        return stored;
    }

    /** 仅 {@code assets/} 下、以 {@code .png} 结尾的真实文件条目（排除 assets/icons/*.svg 等）。 */
    private static boolean isAssetPng(String name) {
        return name != null
                && name.startsWith("assets/")
                && name.endsWith(".png")
                && name.length() > "assets/".length() + ".png".length();
    }

    /** 单张「bytes → 落 hash」链；任何环节失败返回 null（跳过该张）。 */
    private String ingestOne(byte[] raw, UUID uploader, boolean bypass) {
        // 1) magic：非 PNG 直接跳过（不信文件扩展名）
        if (!"image/png".equals(detectMagicMime(raw))) {
            return null;
        }
        // 2) 隔离解码（200ms 超时 + 头部尺寸预检）
        BufferedImage decoded;
        try {
            decoded = decodeWithTimeout(raw);
        } catch (Exception ex) {
            // 解码失败 / 超时 / 超尺寸 → 跳过
            return null;
        }
        if (decoded == null) return null;
        // 3) bbox sanity
        int w = decoded.getWidth(), h = decoded.getHeight();
        if (w <= 0 || h <= 0 || w > BBOX_MAX_EDGE || h > BBOX_MAX_EDGE) {
            return null;
        }
        // 4) 规范化为 PNG + 按内容重算 hash
        byte[] pngBytes;
        try {
            pngBytes = ImageStorage.encodePng(decoded);
        } catch (IOException ex) {
            log.log(Level.WARNING, "asset ingest: PNG encode failed", ex);
            return null;
        }
        String hash = ImageStorage.sha256Hex16(pngBytes);
        long pngLen = pngBytes.length;

        // 5) per-hash 锁 + SERIALIZABLE 配额事务（对照 UploadHandler:572-657）
        ReentrantLock hashLock = storage.writeLockFor(hash);
        hashLock.lock();
        try {
            final ImageUploadDao.Row candidate = new ImageUploadDao.Row(
                    hash, pngLen, w, h, "image/png", uploader,
                    System.currentTimeMillis(), System.currentTimeMillis());
            ImageQuotaService.QuotaResult qr;
            try {
                qr = jdbi.inTransaction(TransactionIsolationLevel.SERIALIZABLE, handle -> {
                    // 升级到写锁（与 UploadHandler 同范式：no-op UPDATE 抢 RESERVED 锁）
                    handle.execute("UPDATE image_uploads SET last_used_at = last_used_at "
                            + "WHERE hash = '__locker__'");
                    Set<String> referenced = storage.collectReferencedHashesOn(handle, wallRepo);
                    Optional<ImageUploadDao.Row> existing = imageDao.findByHashOn(handle, hash);
                    boolean alreadyExists = existing.isPresent();
                    return quota.tryReserveQuotaOn(
                            handle, uploader, candidate, pngLen,
                            alreadyExists, referenced, bypass);
                });
            } catch (Exception ex) {
                log.log(Level.WARNING, "asset ingest: quota transaction failed for " + hash, ex);
                return null;
            }

            // 配额拒 → 跳过该张（不抛，导入继续）
            if (qr instanceof ImageQuotaService.DeniedPerDay
                    || qr instanceof ImageQuotaService.DeniedDiskAfterLru) {
                return null;
            }
            ImageQuotaService.Reserved ok = (ImageQuotaService.Reserved) qr;

            // 6) 事务 commit 后：(a) 写新文件 atomic；(b) 清 LRU 已 evict 的孤儿文件
            if (ok.inserted()) {
                try {
                    storage.writeFileAtomic(hash, pngBytes);
                } catch (IOException ex) {
                    // 补偿：回滚 DB 行，避免孤儿 row（对照 UploadHandler 的补偿逻辑）
                    log.log(Level.WARNING,
                            "asset ingest: writeFileAtomic failed; rolling back DB row " + hash, ex);
                    try {
                        jdbi.useTransaction(TransactionIsolationLevel.SERIALIZABLE, handle ->
                                imageDao.deleteOn(handle, hash));
                    } catch (Exception rb) {
                        log.log(Level.SEVERE,
                                "asset ingest: compensation DELETE failed; orphan row may remain: "
                                        + hash, rb);
                    }
                    for (String evictedHash : ok.evictedHashes()) {
                        storage.deleteFileOnly(evictedHash);
                    }
                    return null;
                }
            }
            for (String evictedHash : ok.evictedHashes()) {
                storage.deleteFileOnly(evictedHash);
            }
            return hash;
        } finally {
            hashLock.unlock();
        }
    }

    /** 关闭内部解码线程池（编排结束后调用；不调也无妨——daemon 线程不阻塞 JVM 退出）。 */
    public void shutdown() {
        decoderPool.shutdownNow();
    }

    // ---------- magic / 隔离解码：image 包内私有逻辑的跨包等价实现 ----------

    /** 等价 {@code UploadHandler.detectMagicMime}：仅判定 PNG（导入只接受 PNG）。 */
    static String detectMagicMime(byte[] b) {
        if (b == null || b.length < 12) return null;
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if ((b[0] & 0xff) == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47) {
            return "image/png";
        }
        return null;
    }

    /**
     * 独立线程解码，限时 {@value #DECODE_TIMEOUT_MS}ms；超时跨线程协作式 {@link ImageReader#abort()}。
     * 等价 {@code UploadHandler.decodeWithTimeout} + {@code decodeCooperative}（含 8192 头部预检）。
     */
    private BufferedImage decodeWithTimeout(byte[] bytes) throws Exception {
        final AtomicReference<ImageReader> readerRef = new AtomicReference<>();
        Future<BufferedImage> fut;
        try {
            fut = decoderPool.submit((Callable<BufferedImage>) () -> decodeCooperative(bytes, readerRef));
        } catch (RejectedExecutionException ree) {
            throw new IOException("asset decoder busy", ree);
        }
        try {
            return fut.get(DECODE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
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

    /** 用 {@link ImageReader} 解码并发布 reader 供超时线程 abort；含解码前头部尺寸预检。 */
    private static BufferedImage decodeCooperative(byte[] bytes, AtomicReference<ImageReader> readerRef)
            throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (iis == null) {
                return ImageIO.read(new ByteArrayInputStream(bytes));
            }
            Iterator<ImageReader> it = ImageIO.getImageReaders(iis);
            if (!it.hasNext()) return null;  // 无 reader = 不支持的格式
            ImageReader reader = it.next();
            readerRef.set(reader);
            try {
                reader.setInput(iis, true, true);
                // 解码前读头部尺寸（只解析头，不解码全图）拦截分配型炸弹
                int pw = reader.getWidth(0);
                int ph = reader.getHeight(0);
                if (pw > BBOX_MAX_EDGE || ph > BBOX_MAX_EDGE) {
                    throw new IOException("image dimensions too large: " + pw + "x" + ph);
                }
                return reader.read(0);
            } catch (javax.imageio.IIOException abortOrFail) {
                throw new IOException("decode aborted or failed", abortOrFail);
            } finally {
                readerRef.set(null);
                reader.dispose();
            }
        }
    }
}
