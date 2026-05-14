package moe.hikari.canvas.image;

import moe.hikari.canvas.HikariCanvasConfig;
import moe.hikari.canvas.storage.ImageUploadDao;

import java.util.UUID;

/**
 * M13 配额三层校验：单 wall 引用数 / 玩家 24h 上传次数 / 全服磁盘总字节。任一超限拒。
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
}
