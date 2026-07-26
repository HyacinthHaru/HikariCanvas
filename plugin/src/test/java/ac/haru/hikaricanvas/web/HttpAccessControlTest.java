package ac.haru.hikaricanvas.web;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 面的访问控制守卫。
 *
 * <ul>
 *   <li>{@code GET /api/walls} 只吐{@linkplain ac.haru.hikaricanvas.storage.WallRepo.PublicSummary
 *       裁过字段的公开投影}——该端点匿名可读（前端落地页在拿到 token 前就要用它），
 *       所以字段本身必须是安全的</li>
 *   <li>{@code brush.end} 必须计入会话输入限流，其余 brush op 保持豁免</li>
 * </ul>
 */
class HttpAccessControlTest {

    // ---------- /api/walls 的公开投影不得含定位信息 ----------

    /**
     * 结构守卫：{@code PublicSummary} 的字段集合必须逐字等于白名单。
     *
     * <p>它是匿名端点的响应体。将来给它加字段的人不会记得这条端点没有鉴权——把作者名、
     * 世界、坐标、朝向任何一个加回去，等于把「全服艺术品藏宝图」重新挂上互联网。
     * 加字段就红，逼加的人先想清楚这个字段能不能公开。</p>
     */
    @Test
    void publicWallSummary_carriesNoLocationOrIdentity() {
        Set<String> actual = Arrays.stream(
                        ac.haru.hikaricanvas.storage.WallRepo.PublicSummary.class
                                .getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(
                Set.of("wallId", "alias", "widthMaps", "heightMaps", "publishedAt", "updatedAt"),
                actual,
                "匿名 /api/walls 的字段集变了。禁止出现 ownerName / world / originX / originY /"
                        + " originZ / facing —— 那是定位到世界里具体位置 + 玩家身份的信息");

        for (String forbidden : List.of("ownerName", "ownerUuid", "world",
                "originX", "originY", "originZ", "facing")) {
            assertFalse(actual.contains(forbidden), "公开投影不得含 " + forbidden);
        }
    }

    /** 对照：需要完整字段的游戏内路径仍走 Summary，别把它一起裁了。 */
    @Test
    void internalSummary_stillCarriesFullFields() {
        Set<String> actual = Arrays.stream(
                        ac.haru.hikaricanvas.storage.WallRepo.Summary.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(actual.containsAll(
                        List.of("ownerName", "world", "originX", "originY", "originZ", "facing")),
                "/canvas list 等游戏内路径依赖这些字段");
    }

    // ---------- brush.end 计入限流 ----------

    /**
     * {@code brush.end} 一次要跑最多 5000 点的 RDP 简化（最坏 O(n²)）+ 一次全量快照深拷贝
     * + 往图层永久追加一个元素，不属于「高频低消息」。{@code MAX_ACTIVE_STROKES} 只挡
     * 「start 了不 end」，挡不住 start→点满→end 的循环。
     */
    @Test
    void brushEnd_isRateLimited() {
        assertTrue(BrushOpDispatcher.isRateLimited("brush.end"));
    }

    /** 其余三个仍豁免——那是笔触体验的既定取舍，不是遗漏。 */
    @Test
    void otherBrushOps_stayExempt() {
        assertFalse(BrushOpDispatcher.isRateLimited("brush.start"));
        assertFalse(BrushOpDispatcher.isRateLimited("brush.point"));
        assertFalse(BrushOpDispatcher.isRateLimited("brush.cancel"));
        assertEquals(1, BrushOpDispatcher.RATE_LIMITED_OPS.size(),
                "限流名单只该有 brush.end；要加别的先改 docs/security.md §3.3");
    }

    /** 畸形帧连击上限与 security.md §3.2 的「3 次即断」对齐。 */
    @Test
    void malformedFrameLimitMatchesSecurityDoc() {
        assertEquals(3, WebServer.MALFORMED_FRAME_LIMIT);
    }
}
