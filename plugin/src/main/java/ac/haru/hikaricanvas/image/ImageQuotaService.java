package ac.haru.hikaricanvas.image;

import ac.haru.hikaricanvas.HikariCanvasConfig;
import ac.haru.hikaricanvas.storage.ImageUploadDao;
import org.jdbi.v3.core.Handle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 配额三层校验：单 wall 引用数 / 玩家 24h 上传次数 / 全服磁盘总字节。任一超限拒。
 *
 * <p>磁盘超限不直接拒：返 {@link CheckResult.NeedsEviction}，调用方触发
 * {@link ImageStorage#evictLruUntilUnder} 后再校验。</p>
 *
 * <p>配额值 0 表示该维度不限。{@code bypass=true}（持 {@code canvas.upload.bypass-limit}
 * 权限的玩家）跳过全部检查。</p>
 */
public final class ImageQuotaService {

    public sealed interface CheckResult {
        record Ok() implements CheckResult {}
        record Rejected(String code, String reason) implements CheckResult {}
        record NeedsEviction(long bytesToFree) implements CheckResult {}
    }

    public record Summary(
            int perWallLimit, int perWallUsed,
            int perDayLimit, int perDayUsed,
            int totalDiskMbLimit, long totalDiskBytesUsed
    ) {}

    private static final long ONE_DAY_MS = 86_400_000L;

    private final ImageUploadDao dao;
    private final HikariCanvasConfig.ImageConfig cfg;

    public ImageQuotaService(ImageUploadDao dao, HikariCanvasConfig.ImageConfig cfg) {
        this.dao = dao;
        this.cfg = cfg;
    }

    /**
     * @param uploader              当前上传玩家
     * @param imagesInTargetWall    上传完成后 element.add type=image 落到的 wall 中现有 image 元素数量
     *                              （上层 EditSession / 前端协调好；超出则拒）
     * @param incomingBytes         即将存入磁盘的字节数
     * @param bypass                是否持 bypass-limit 权限
     */
    public CheckResult check(UUID uploader, int imagesInTargetWall, long incomingBytes, boolean bypass) {
        if (bypass) return new CheckResult.Ok();

        if (cfg.maxPerWall() > 0 && imagesInTargetWall >= cfg.maxPerWall()) {
            return new CheckResult.Rejected("QUOTA_PER_WALL",
                    "wall already has " + imagesInTargetWall + " image element(s); max " + cfg.maxPerWall());
        }

        if (cfg.maxUploadsPerDay() > 0) {
            long since = System.currentTimeMillis() - ONE_DAY_MS;
            int count = dao.countByUploaderSince(uploader, since);
            if (count >= cfg.maxUploadsPerDay()) {
                return new CheckResult.Rejected("QUOTA_PER_DAY",
                        "uploaded " + count + " image(s) in last 24h; max " + cfg.maxUploadsPerDay());
            }
        }

        if (cfg.maxTotalStorageMb() > 0) {
            long maxBytes = (long) cfg.maxTotalStorageMb() * 1024L * 1024L;
            long after = dao.sumBytes() + incomingBytes;
            if (after > maxBytes) {
                return new CheckResult.NeedsEviction(after - maxBytes);
            }
        }
        return new CheckResult.Ok();
    }

    public Summary remaining(UUID uploader, int imagesInWall) {
        long since = System.currentTimeMillis() - ONE_DAY_MS;
        int perDayUsed = cfg.maxUploadsPerDay() > 0 ? dao.countByUploaderSince(uploader, since) : 0;
        long totalBytes = dao.sumBytes();
        return new Summary(
                cfg.maxPerWall(), imagesInWall,
                cfg.maxUploadsPerDay(), perDayUsed,
                cfg.maxTotalStorageMb(), totalBytes);
    }

    public HikariCanvasConfig.ImageConfig config() {
        return cfg;
    }

    // ---------- 事务内原子化 check+evict+insert ----------

    /**
     * 单事务里跑：per-day 配额 → 磁盘配额（含 LRU evict）→ INSERT 新行。
     * 调用方必须在 {@code jdbi.inTransaction(IMMEDIATE, ...)} 里调本方法，保证 SQLite
     * 写锁全程持有，杜绝并发 49+1+1=51 的 TOCTOU。
     *
     * <p>语义：
     * <ul>
     *   <li>{@code alreadyExists=true}（hash 已在表中）→ 不检查任何配额，仅 touchLastUsed，
     *       返回 {@link Reserved#existingTouched()}={@code true}。这是 content-hash 内容寻址
     *       的 idempotent 重新上传路径。</li>
     *   <li>否则：先 per-day count；若超 → {@link DeniedPerDay}。再算 sum+incoming 是否超
     *       disk 上限；若超 → 在事务内连续 {@code pickLruCandidatesOn} + {@code deleteOn}
     *       直到不超或候选耗尽。仍不够 → {@link DeniedDiskAfterLru}。OK 则 INSERT。</li>
     * </ul>
     *
     * <p><b>evictedHashes 谁都要收：</b>{@link Reserved} 与 {@link DeniedDiskAfterLru} 都带这个列表。
     * jdbi 的 {@code inTransaction} 只要 lambda 正常返回就 COMMIT——即便返回的是"拒绝"，事务内
     * 已 DELETE 的 LRU victim 行也照样落库。调用方在两种结果下都必须拿这个列表去删对应的磁盘
     * PNG，否则就留下"DB 行没了、文件还在"的物理孤儿，{@code sumBytes()} 从此低估真实占用，
     * 磁盘配额一次次拒绝之后逐步失真。</p>
     *
     * <p><b>referencedHashes 是惰性的：</b>算这个集合要全表扫 {@code walls} 并逐行反序列化
     * project_json，代价不小；而它只在真的要 LRU evict 时才用得上。传 supplier 让
     * "已存在 / per-day 拒 / 磁盘没满"这三条常见路径一次都不扫。supplier 会在<b>本事务内</b>
     * 求值，故原有的"事务内一致视图"保证不变。</p>
     *
     * @param h               活动事务 handle
     * @param uploader        上传者
     * @param newRow          要插入的新行（仅 alreadyExists=false 时使用）
     * @param incomingBytes   新文件字节数（仅 alreadyExists=false 时计入 disk 评估）
     * @param alreadyExists   true → 仅 touchLastUsed；false → 走完整配额 + insert
     * @param referencedHashes  被 walls 引用的 hash 集合的 supplier（LRU 候选排除集）；
     *                          只在需要 evict 时求值，且求值发生在本事务内
     * @param bypass          持 canvas.upload.bypass-limit 权限 → 跳过 per-day / disk 检查
     */
    public QuotaResult tryReserveQuotaOn(Handle h,
                                         UUID uploader,
                                         ImageUploadDao.Row newRow,
                                         long incomingBytes,
                                         boolean alreadyExists,
                                         Supplier<Set<String>> referencedHashes,
                                         boolean bypass) {
        long now = System.currentTimeMillis();
        if (alreadyExists) {
            dao.touchLastUsedOn(h, newRow.hash(), now);
            return new Reserved(false, true, List.of());
        }

        // per-day（bypass 跳过）
        if (!bypass && cfg.maxUploadsPerDay() > 0) {
            int count = dao.countByUploaderSinceOn(h, uploader, now - ONE_DAY_MS);
            if (count >= cfg.maxUploadsPerDay()) {
                return new DeniedPerDay(count, cfg.maxUploadsPerDay());
            }
        }

        // disk（bypass 跳过）
        List<String> evicted = new ArrayList<>();
        if (!bypass && cfg.maxTotalStorageMb() > 0) {
            long maxBytes = (long) cfg.maxTotalStorageMb() * 1024L * 1024L;
            long currentSum = dao.sumBytesOn(h);
            // 安全门：单个文件就超 max → 没有 evict 余地，直接拒
            if (incomingBytes > maxBytes) {
                return new DeniedDiskAfterLru(incomingBytes - maxBytes, List.of());
            }
            // 走到这里才真的需要引用集（全表扫 walls），前面几条路径都不必付这个代价。
            if (currentSum + incomingBytes <= maxBytes) {
                // 空间够，不用 evict，也就不用扫引用集
                return insertOrTouch(h, newRow, now, evicted);
            }
            // LRU 在事务内 DELETE 老行；excludeHashes 累计已删 + 引用集，避免重复挑
            Set<String> referenced = referencedHashes == null ? Set.of() : referencedHashes.get();
            Set<String> exclude = new HashSet<>(referenced == null ? Set.of() : referenced);
            int guard = 0;
            while (currentSum + incomingBytes > maxBytes && guard++ < 32) {
                List<ImageUploadDao.Row> victims = dao.pickLruCandidatesOn(h, 16, exclude);
                if (victims.isEmpty()) break;
                for (ImageUploadDao.Row v : victims) {
                    dao.deleteOn(h, v.hash());
                    evicted.add(v.hash());
                    exclude.add(v.hash());
                    currentSum -= v.bytes();
                    if (currentSum + incomingBytes <= maxBytes) break;
                }
            }
            if (currentSum + incomingBytes > maxBytes) {
                long need = (currentSum + incomingBytes) - maxBytes;
                // 拒绝也要把 evicted 交出去：本方法返回后事务会 COMMIT（jdbi 只在抛异常时回滚），
                // 那些 victim 的 DB 行已经真的没了，调用方必须把对应磁盘文件一并删掉。
                return new DeniedDiskAfterLru(need, evicted);
            }
        }

        return insertOrTouch(h, newRow, now, evicted);
    }

    /** 配额通过后的落库尾段：INSERT 新行，撞已存在则退化为 touchLastUsed。 */
    private QuotaResult insertOrTouch(Handle h, ImageUploadDao.Row newRow, long now,
                                      List<String> evicted) {
        int inserted = dao.insertOn(h, newRow);
        if (inserted != 1) {
            // race：在此事务开始之前另一个 caller 抢先插入了同 hash（IMMEDIATE 下理论上
            // 不会发生——写锁先到先得；但 INSERT OR IGNORE 命中冲突时仍降级处理）。
            dao.touchLastUsedOn(h, newRow.hash(), now);
            return new Reserved(false, true, evicted);
        }
        return new Reserved(true, false, evicted);
    }

    /** 事务化 quota+insert 的返回值。 */
    public sealed interface QuotaResult {}

    /** 配额 OK，新行已插入或已存在的 touchLastUsed 完成。 */
    public record Reserved(boolean inserted, boolean existingTouched, List<String> evictedHashes)
            implements QuotaResult {}

    /** per-day 计数超限；此路径在 evict 之前，故没有需要清理的 victim。 */
    public record DeniedPerDay(int currentCount, int limit) implements QuotaResult {}

    /**
     * disk 配额超限且 LRU evict 后仍不够。
     *
     * @param bytesShort    还差多少字节
     * @param evictedHashes 事务内已 DELETE 掉 DB 行的 victim；调用方<b>必须</b>逐个
     *                      {@code storage.deleteFileOnly}，否则文件成物理孤儿、配额簿记失真
     */
    public record DeniedDiskAfterLru(long bytesShort, List<String> evictedHashes)
            implements QuotaResult {}
}
