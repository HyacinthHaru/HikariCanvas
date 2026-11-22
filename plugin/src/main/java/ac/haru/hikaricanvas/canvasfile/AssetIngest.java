package ac.haru.hikaricanvas.canvasfile;

import ac.haru.hikaricanvas.image.ImageDecodeGuard;
import ac.haru.hikaricanvas.image.ImageQuotaService;
import ac.haru.hikaricanvas.image.ImageStorage;
import ac.haru.hikaricanvas.storage.ImageUploadDao;
import ac.haru.hikaricanvas.storage.WallRepo;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.transaction.TransactionIsolationLevel;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 把导入的 {@code assets/<hash>.png} 逐张安全摄入到图片存储。
 *
 * <p>每张图走与 HTTP 上传同等的不可信防御链（共用 {@link ImageDecodeGuard}）：
 * <ol>
 *   <li>magic bytes 校验（{@link ImageDecodeGuard#detectMagicMime}，非 PNG → 跳过）；</li>
 *   <li>ImageIO 隔离解码（{@link ImageDecodeGuard#decodeWithTimeout}，200ms 协作式超时 +
 *       8192px 头部尺寸预检拦截分配型炸弹；解码失败 / 超时 / 超尺寸 → 跳过）；</li>
 *   <li>{@link ImageStorage#encodePng} 规范化 + {@link ImageStorage#sha256Hex16}
 *       <b>按内容重算 hash</b>（不信文件名）；</li>
 *   <li>per-hash 锁 + SERIALIZABLE 配额事务（{@link ImageQuotaService#tryReserveQuotaOn}）；
 *       配额拒（{@code DeniedPerDay}/{@code DeniedDiskAfterLru}）→ 跳过该张；</li>
 *   <li>事务 commit 后原子落盘 + 清 LRU 已 evict 的孤儿文件。</li>
 * </ol>
 * 任何单张失败<b>只跳过该张、不抛异常、不中止整体导入</b>。
 *
 * <p><b>条目数上限 {@value #MAX_ASSET_ENTRIES}：</b>解包层只数字节，大量极小 PNG 仍可能制造海量
 * SERIALIZABLE 写事务阻塞 SQLite 写锁；超出上限的条目直接跳过并计入 {@link Report#overflowSkipped()}。</p>
 */
public final class AssetIngest {

    /** 单次导入最多摄入多少张 {@code assets/*.png}。 */
    public static final int MAX_ASSET_ENTRIES = 256;

    private final Logger log;
    private final ImageStorage storage;
    private final ImageQuotaService quota;
    private final ImageUploadDao imageDao;
    private final WallRepo wallRepo;
    private final Jdbi jdbi;
    private final ImageDecodeGuard decodeGuard;

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
        this.decodeGuard = new ImageDecodeGuard();
    }

    /**
     * 一次摄入的结果。
     *
     * @param storedHashes    成功落盘（或已存在）的内容 hash，按内容重算、已去重
     * @param rehashed        条目声明的 hash（文件名去 {@code .png}）→ 实际内容 hash，
     *                        <b>仅在两者不同时</b>才有条目。见 {@link #ingestOne} 的说明
     * @param overflowSkipped 因超过 {@link #MAX_ASSET_ENTRIES} 而未处理的张数
     */
    public record Report(Set<String> storedHashes, Map<String, String> rehashed,
                         int overflowSkipped) {
    }

    /**
     * 摄入所有 {@code assets/<file>.png} 条目（其余 key 忽略），不带配额 bypass。
     *
     * @param assets   解包后的条目 map（key = zip 内路径，value = 原始字节）
     * @param uploader 导入者 uuid（落盘 row 的 owner）
     * @return 成功落盘的 hash 集合（按内容重算，已去重）
     */
    public Set<String> ingestAll(Map<String, byte[]> assets, UUID uploader) {
        return ingestAll(assets, uploader, false).storedHashes();
    }

    /**
     * @param bypass 配额 bypass（对应 {@code canvas.upload.bypass-limit} 权限；导入侧一般 false）
     */
    public Report ingestAll(Map<String, byte[]> assets, UUID uploader, boolean bypass) {
        Set<String> stored = new LinkedHashSet<>();
        Map<String, String> rehashed = new LinkedHashMap<>();
        if (assets == null || assets.isEmpty()) {
            return new Report(stored, rehashed, 0);
        }

        int processed = 0;
        int overflow = 0;
        for (Map.Entry<String, byte[]> e : assets.entrySet()) {
            String name = e.getKey();
            if (!isAssetPng(name)) continue;
            if (processed >= MAX_ASSET_ENTRIES) {
                overflow++;
                continue;
            }
            processed++;
            byte[] raw = e.getValue();
            String hash = ingestOne(raw, uploader, bypass);
            if (hash == null) continue;
            stored.add(hash);
            String declared = declaredHashOf(name);
            if (declared != null && !declared.equals(hash)) {
                rehashed.put(declared, hash);
            }
        }
        if (overflow > 0) {
            log.warning("asset ingest: " + overflow + " image(s) beyond the "
                    + MAX_ASSET_ENTRIES + "-entry cap were not processed");
        }
        return new Report(stored, rehashed, overflow);
    }

    /** 单墙引用的不同图片文件数上限（{@code config.images.max-per-wall}）；{@code <=0} = 不限。 */
    public int maxImagesPerWall() {
        return quota.config().maxPerWall();
    }

    /** {@code assets/<stem>.png} 里的 {@code <stem>}——导出方声明的内容 hash。 */
    private static String declaredHashOf(String name) {
        String file = name.substring("assets/".length());
        return file.substring(0, file.length() - ".png".length());
    }

    /** 仅 {@code assets/} 下、以 {@code .png} 结尾的真实文件条目（排除 assets/icons/*.svg 等）。 */
    private static boolean isAssetPng(String name) {
        return name != null
                && name.startsWith("assets/")
                && name.endsWith(".png")
                && name.length() > "assets/".length() + ".png".length();
    }

    /**
     * 单张「bytes → 落 hash」链；任何环节失败返回 null（跳过该张）。
     *
     * <p>返回按内容重算的 hash；若与导出方在条目名中声明的 hash 不同，调用方需通过
     * {@link Report#rehashed()} 改写 {@code ImageElement.source} 引用。</p>
     */
    private String ingestOne(byte[] raw, UUID uploader, boolean bypass) {
        if (!"image/png".equals(ImageDecodeGuard.detectMagicMime(raw))) {
            return null;
        }
        BufferedImage decoded;
        try {
            decoded = decodeGuard.decodeWithTimeout(raw);
        } catch (TimeoutException te) {
            log.warning("asset ingest: image decode timed out after "
                    + ImageDecodeGuard.DECODE_TIMEOUT_MS + "ms; image skipped");
            return null;
        } catch (Exception ex) {
            log.fine("asset ingest: image decode failed, skipped: " + ex.getMessage());
            return null;
        }
        if (decoded == null) return null;
        int w = decoded.getWidth(), h = decoded.getHeight();
        if (w <= 0 || h <= 0 || w > ImageDecodeGuard.BBOX_MAX_EDGE || h > ImageDecodeGuard.BBOX_MAX_EDGE) {
            return null;
        }
        byte[] pngBytes;
        try {
            pngBytes = ImageStorage.encodePng(decoded);
        } catch (IOException ex) {
            log.log(Level.WARNING, "asset ingest: PNG encode failed", ex);
            return null;
        }
        String hash = ImageStorage.sha256Hex16(pngBytes);
        long pngLen = pngBytes.length;

        ReentrantLock hashLock = storage.writeLockFor(hash);
        hashLock.lock();
        try {
            final ImageUploadDao.Row candidate = new ImageUploadDao.Row(
                    hash, pngLen, w, h, "image/png", uploader,
                    System.currentTimeMillis(), System.currentTimeMillis());
            ImageQuotaService.QuotaResult qr;
            try {
                qr = jdbi.inTransaction(TransactionIsolationLevel.SERIALIZABLE, handle -> {
                    handle.execute("UPDATE image_uploads SET last_used_at = last_used_at "
                            + "WHERE hash = '__locker__'");
                    Optional<ImageUploadDao.Row> existing = imageDao.findByHashOn(handle, hash);
                    return quota.tryReserveQuotaOn(
                            handle, uploader, candidate, pngLen, existing.isPresent(),
                            () -> storage.collectReferencedHashesOn(handle, wallRepo), bypass);
                });
            } catch (Exception ex) {
                log.log(Level.WARNING, "asset ingest: quota transaction failed for " + hash, ex);
                return null;
            }

            if (qr instanceof ImageQuotaService.DeniedPerDay) {
                return null;
            }
            if (qr instanceof ImageQuotaService.DeniedDiskAfterLru dd) {
                for (String evictedHash : dd.evictedHashes()) {
                    storage.deleteFileOnly(evictedHash);
                }
                return null;
            }
            ImageQuotaService.Reserved ok = (ImageQuotaService.Reserved) qr;

            if (ok.inserted()) {
                try {
                    storage.writeFileAtomic(hash, pngBytes);
                } catch (IOException ex) {
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

    /** 关闭内部解码守护线程（幂等）。 */
    public void shutdown() {
        decodeGuard.shutdown();
    }
}
