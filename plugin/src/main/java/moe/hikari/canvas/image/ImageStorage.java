package moe.hikari.canvas.image;

import moe.hikari.canvas.storage.ImageUploadDao;
import moe.hikari.canvas.storage.WallRepo;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * M13 图片存储层：sha256[:16] 内容寻址 + 磁盘 LRU + 60s 内存缓存。
 *
 * <ul>
 *   <li><b>磁盘布局</b>：{@code plugins/HikariCanvas/uploads/<hash>.png}（统一存 PNG，
 *       上传 jpeg/webp 经 ImageIO 解码 + PNG 编码后存）</li>
 *   <li><b>hash 算法</b>：sha256（PNG 编码字节）[:16] = 16 字符小写 hex；同图同 PNG 编码
 *       参数下稳定，跨 wall 引用零重复存储</li>
 *   <li><b>内存缓存</b>：MRU LinkedHashMap 最多 16 项 + 60s TTL；过期重读磁盘</li>
 *   <li><b>LRU 候选</b>：v1 实时 sweep {@code walls.project_json} 收集所有 image.source；
 *       不在集合内的 image_uploads 行为 orphan，可按 {@code last_used_at} 升序删</li>
 * </ul>
 *
 * <p>线程模型：{@code synchronized} 包内存缓存访问；磁盘 IO + DAO 访问 Jdbi 自己管。
 * 上传并发安全靠 {@code INSERT OR IGNORE} + {@code findByHash} 重读。</p>
 */
public final class ImageStorage {

    private static final Pattern HASH_RE = Pattern.compile("^[0-9a-f]{16}$");
    private static final int MEMORY_CACHE_MAX = 16;
    private static final long MEMORY_CACHE_TTL_MS = 60_000L;

    private final Logger log;
    private final Path uploadsDir;
    private final ImageUploadDao dao;

    // M15.4 P0-17：渲染解码也要 timeout 隔离，否则损坏 PNG 让 rasterize 主线程死锁。
    // 同 UploadHandler 的有界 ThreadPoolExecutor pattern，但 TTL 短（render 上下文）。
    private final ExecutorService renderDecoderPool = new java.util.concurrent.ThreadPoolExecutor(
            1, 1,
            0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(4),
            r -> {
                Thread t = new Thread(r, "hikari-render-decoder");
                t.setDaemon(true);
                return t;
            },
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    private static final long RENDER_DECODE_TIMEOUT_MS = 500L;

    // M16 P2.2：同 hash 并发 putIfAbsent 串行（保证 Files.write 不重入），
    // 不同 hash 不互相阻塞。LRU 自动清理：lock 在 finally 释放后由 GC 回收的可能性
    // 极低（map 永远引用），但锁数与历史出现的 hash 数同阶；每个 ReentrantLock ~48B，
    // 1k hash 占 ~48KB，可接受。
    private final ConcurrentHashMap<String, ReentrantLock> writeLocks = new ConcurrentHashMap<>();

    private final LinkedHashMap<String, CacheEntry> memCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > MEMORY_CACHE_MAX;
        }
    };

    private record CacheEntry(BufferedImage img, long expireAt) {}

    /** {@link #putIfAbsent} 返回值：表示一次 upload 落库的成果。 */
    public record StoreResult(String hash, int width, int height, long bytes, boolean isNew) {}

    public ImageStorage(Logger log, Path dataFolder, ImageUploadDao dao) {
        this.log = log;
        this.uploadsDir = dataFolder.resolve("uploads");
        this.dao = dao;
        try {
            Files.createDirectories(uploadsDir);
        } catch (IOException e) {
            throw new IllegalStateException("create uploads dir failed: " + uploadsDir, e);
        }
    }

    /**
     * 把 {@link BufferedImage} 编码为 PNG 后按 hash 内容寻址持久化。
     * 若同 hash 已存在 → 不写磁盘 + 仅刷 last_used_at，返回 {@code isNew=false}。
     *
     * <p><b>注意</b>：M16 P2.2 之后此方法保留作 legacy 简化路径（仅用于测试 / 老代码）。
     * 生产路径 {@link moe.hikari.canvas.image.UploadHandler} 走"事务内 quota+insert →
     * 事务外写磁盘"的拆分流程，不再调本方法。</p>
     *
     * <p>注意：调用方应在调用前完成所有上层校验（大小 / mime / 解码 / 配额）；此方法不做这些。</p>
     *
     * @throws IOException 磁盘写失败
     */
    public StoreResult putIfAbsent(BufferedImage img, UUID uploader) throws IOException {
        byte[] pngBytes = encodePng(img);
        String hash = sha256Hex16(pngBytes);
        long now = System.currentTimeMillis();

        ReentrantLock lock = writeLocks.computeIfAbsent(hash, k -> new ReentrantLock());
        lock.lock();
        try {
            Optional<ImageUploadDao.Row> existing = dao.findByHash(hash);
            if (existing.isPresent()) {
                dao.touchLastUsed(hash, now);
                ImageUploadDao.Row r = existing.get();
                return new StoreResult(hash, r.width(), r.height(), r.bytes(), false);
            }

            // 先 DB insert，后写文件（与 P2.2 主路径方向一致）
            boolean inserted = dao.insert(new ImageUploadDao.Row(
                    hash, pngBytes.length,
                    img.getWidth(), img.getHeight(),
                    "image/png", uploader, now, now, 1));
            if (!inserted) {
                // race：另一线程同 hash 抢先 insert
                Optional<ImageUploadDao.Row> raced = dao.findByHash(hash);
                if (raced.isPresent()) {
                    ImageUploadDao.Row r = raced.get();
                    return new StoreResult(hash, r.width(), r.height(), r.bytes(), false);
                }
            }
            try {
                writeFileAtomic(hash, pngBytes);
            } catch (IOException e) {
                // 补偿：删除已插入的 DB 行，避免孤儿
                dao.delete(hash);
                throw e;
            }
            return new StoreResult(hash, img.getWidth(), img.getHeight(), pngBytes.length, true);
        } finally {
            lock.unlock();
        }
    }

    /**
     * M16 P2.2：将 BufferedImage 编码为 PNG 字节。无副作用，可在任何线程调用。
     */
    public static byte[] encodePng(BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (!ImageIO.write(img, "png", baos)) {
            throw new IOException("ImageIO.write returned false; no PNG writer?");
        }
        return baos.toByteArray();
    }

    /**
     * M16 P2.2：原子地把 PNG 字节落到 {@code uploads/<hash>.png}。
     * 流程：写到 {@code <hash>.png.tmp} → {@code Files.move(tmp → final, ATOMIC_MOVE, REPLACE_EXISTING)}。
     * 失败 finally 清理 tmp，避免孤儿。
     *
     * <p>幂等：目标已存在 + 同 hash（content-addressed）则跳过写。这样 race 中第二个 caller
     * 不会重复 IO。</p>
     */
    public void writeFileAtomic(String hash, byte[] pngBytes) throws IOException {
        if (hash == null || !HASH_RE.matcher(hash).matches()) {
            throw new IOException("invalid hash for writeFileAtomic: " + hash);
        }
        Path finalPath = uploadsDir.resolve(hash + ".png");
        if (Files.isRegularFile(finalPath)) {
            // content-hash 内容寻址保证 idempotent
            return;
        }
        Path tmpPath = uploadsDir.resolve(hash + ".png.tmp");
        try {
            Files.write(tmpPath, pngBytes);
            try {
                Files.move(tmpPath, finalPath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException amnse) {
                // 兜底：FS 不支持原子 move（极少见，比如跨 mount）→ 退化为普通 move
                Files.move(tmpPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(tmpPath);
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }

    /**
     * M16 P2.2：删除磁盘 PNG 文件（**不**碰 DB / 内存缓存）。
     * 用于事务 commit 之后 LRU evict 已 DELETE 的行的磁盘 cleanup；DB 行已不在，
     * 这里失败仅 warn，孤儿文件由下次启动 sweep / 手工清理 / 永远占空间（最坏情况）。
     */
    public void deleteFileOnly(String hash) {
        if (hash == null || !HASH_RE.matcher(hash).matches()) return;
        Path file = uploadsDir.resolve(hash + ".png");
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.log(Level.WARNING, "deleteFileOnly failed (orphan file may remain): " + hash, e);
        }
        synchronized (memCache) {
            memCache.remove(hash);
        }
    }

    /**
     * M16 P2.2：取（创建）某 hash 专属串行锁。同 hash 写流程必须 lock/unlock 包裹。
     * 不同 hash 不互锁。
     */
    public ReentrantLock writeLockFor(String hash) {
        return writeLocks.computeIfAbsent(hash, k -> new ReentrantLock());
    }

    /**
     * 按 hash 加载原图。返回 {@code null} = 文件 / 行缺失（调用方画占位）。
     * 命中内存缓存 → 直接返；否则从磁盘 ImageIO.read + 进缓存。
     */
    public BufferedImage load(String hash) {
        if (hash == null || !HASH_RE.matcher(hash).matches()) return null;
        long now = System.currentTimeMillis();
        synchronized (memCache) {
            CacheEntry hit = memCache.get(hash);
            if (hit != null && hit.expireAt > now) {
                return hit.img;
            }
            if (hit != null) memCache.remove(hash);
        }
        Path file = uploadsDir.resolve(hash + ".png");
        if (!Files.isRegularFile(file)) return null;
        BufferedImage img;
        try {
            java.util.concurrent.Future<BufferedImage> fut = renderDecoderPool.submit(
                    () -> ImageIO.read(file.toFile()));
            try {
                img = fut.get(RENDER_DECODE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                fut.cancel(true);
                log.warning("ImageStorage.load timeout: " + hash);
                return null;
            }
        } catch (java.util.concurrent.RejectedExecutionException ree) {
            log.warning("ImageStorage.load decoder busy: " + hash);
            return null;
        } catch (Exception e) {
            log.log(Level.WARNING, "ImageStorage.load read failed: " + hash, e);
            return null;
        }
        if (img == null) return null;
        synchronized (memCache) {
            memCache.put(hash, new CacheEntry(img, now + MEMORY_CACHE_TTL_MS));
        }
        dao.touchLastUsed(hash, now);
        return img;
    }

    /** M15.4 P0-17：关闭渲染解码池，由 HikariCanvas.onDisable 调。 */
    public void shutdown() {
        renderDecoderPool.shutdownNow();
    }

    /**
     * 返回某 hash 文件的原始 PNG 字节（用于 GET /api/upload/{source} 直接 stream）。
     * 不进内存图像缓存，但触一次 last_used_at。返回 {@code null} = 文件缺失。
     */
    public byte[] readPngBytes(String hash) {
        if (hash == null || !HASH_RE.matcher(hash).matches()) return null;
        Path file = uploadsDir.resolve(hash + ".png");
        if (!Files.isRegularFile(file)) return null;
        try {
            byte[] bytes = Files.readAllBytes(file);
            dao.touchLastUsed(hash, System.currentTimeMillis());
            return bytes;
        } catch (IOException e) {
            log.log(Level.WARNING, "ImageStorage.readPngBytes failed: " + hash, e);
            return null;
        }
    }

    /**
     * 删除单个 hash：磁盘 + DAO + 内存缓存。
     */
    public void deleteHash(String hash) {
        Path file = uploadsDir.resolve(hash + ".png");
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.log(Level.WARNING, "deleteHash file failed: " + hash, e);
        }
        dao.delete(hash);
        synchronized (memCache) {
            memCache.remove(hash);
        }
    }

    /**
     * 当总磁盘字节 + {@code incomingBytes} 超过 {@code maxTotalBytes} 时，按 LRU 顺序
     * 删除 **未被 walls.project_json 引用** 的 orphan hash 直到不再超限或候选耗尽。
     *
     * @param incomingBytes 即将上传的字节数（用于预算）
     * @param maxTotalBytes 总配额（0 = 不限，直接返回）
     * @param wallRepo      用于 sweep 所有 walls 收集 referenced hash 集合
     * @return 实际删除的 hash 数量
     */
    public int evictLruUntilUnder(long incomingBytes, long maxTotalBytes, WallRepo wallRepo) {
        if (maxTotalBytes <= 0) return 0;
        long total = dao.sumBytes() + incomingBytes;
        if (total <= maxTotalBytes) return 0;

        Set<String> referenced = collectReferencedHashes(wallRepo);
        int evicted = 0;
        int safetyGuard = 0;
        while (total > maxTotalBytes && safetyGuard++ < 32) {
            List<ImageUploadDao.Row> victims = dao.pickLruCandidates(16, referenced);
            if (victims.isEmpty()) break;
            for (ImageUploadDao.Row v : victims) {
                deleteHash(v.hash());
                total -= v.bytes();
                evicted++;
                if (total <= maxTotalBytes) break;
            }
        }
        if (evicted > 0) {
            log.info("ImageStorage LRU evicted " + evicted + " orphan upload(s)");
        }
        return evicted;
    }

    /**
     * 扫所有 walls 的 ProjectState（含 layers/elements），收集所有 {@code ImageElement.source}
     * 的 hash 集合。**v1 实时计算**，~50 walls 量级 SQLite 查询 + Jackson 反序列化 < 50ms 可接受；
     * 未来 wall 数量上千再考虑增量 refcount 维护。
     */
    public Set<String> collectReferencedHashes(WallRepo wallRepo) {
        Set<String> out = new HashSet<>();
        try {
            for (WallRepo.Wall w : wallRepo.loadAll()) {
                if (w.state() == null || w.state().layers() == null) continue;
                for (var layer : w.state().layers()) {
                    for (var el : layer.elements()) {
                        if (el instanceof moe.hikari.canvas.state.ImageElement im) {
                            if (HASH_RE.matcher(im.source()).matches()) {
                                out.add(im.source());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "collectReferencedHashes failed; treating no referenced", e);
        }
        return out;
    }

    public static String sha256Hex16(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i] & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** test-only：清空内存缓存。 */
    public void clearMemCacheForTest() {
        synchronized (memCache) {
            memCache.clear();
        }
    }

    public Path uploadsDir() {
        return uploadsDir;
    }
}
