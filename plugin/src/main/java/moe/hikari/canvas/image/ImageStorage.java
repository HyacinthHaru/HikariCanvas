package moe.hikari.canvas.image;

import moe.hikari.canvas.storage.ImageUploadDao;
import moe.hikari.canvas.storage.WallRepo;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
     * <p>注意：调用方应在调用前完成所有上层校验（大小 / mime / 解码 / 配额）；此方法不做这些。</p>
     *
     * @throws IOException 磁盘写失败
     */
    public StoreResult putIfAbsent(BufferedImage img, UUID uploader) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (!ImageIO.write(img, "png", baos)) {
            throw new IOException("ImageIO.write returned false; no PNG writer?");
        }
        byte[] pngBytes = baos.toByteArray();
        String hash = sha256Hex16(pngBytes);
        long now = System.currentTimeMillis();

        Optional<ImageUploadDao.Row> existing = dao.findByHash(hash);
        if (existing.isPresent()) {
            dao.touchLastUsed(hash, now);
            ImageUploadDao.Row r = existing.get();
            return new StoreResult(hash, r.width(), r.height(), r.bytes(), false);
        }

        Path file = uploadsDir.resolve(hash + ".png");
        Files.write(file, pngBytes);

        boolean inserted = dao.insert(new ImageUploadDao.Row(
                hash, pngBytes.length,
                img.getWidth(), img.getHeight(),
                "image/png", uploader, now, now, 1));
        if (!inserted) {
            // 罕见并发：另一线程同 hash 抢先 insert；以已有行为准
            Optional<ImageUploadDao.Row> raced = dao.findByHash(hash);
            if (raced.isPresent()) {
                ImageUploadDao.Row r = raced.get();
                return new StoreResult(hash, r.width(), r.height(), r.bytes(), false);
            }
        }
        return new StoreResult(hash, img.getWidth(), img.getHeight(), pngBytes.length, true);
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
            img = ImageIO.read(file.toFile());
        } catch (IOException e) {
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
