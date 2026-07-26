package ac.haru.hikaricanvas.web;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 面的访问控制守卫。
 *
 * <ul>
 *   <li>{@code GET /api/wall/{id}/preview.png} 的可见性判定
 *       （{@link WebServer#previewVisible}）—— 这条端点以前零鉴权，
 *       任何人对任意 wallId 都能出一整张画面图</li>
 *   <li>{@code brush.end} 必须计入会话输入限流，其余 brush op 保持豁免</li>
 * </ul>
 */
class HttpAccessControlTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final long LOCKED = 1_700_000_000_000L;

    // ---------- 墙缩略图可见性 ----------

    @Test
    void noSession_neverVisible() {
        assertFalse(WebServer.previewVisible(null, ALICE, null, false),
                "没有有效会话就不该拿得到任何墙的画面");
        assertFalse(WebServer.previewVisible(null, ALICE, LOCKED, true));
    }

    @Test
    void owner_alwaysVisible() {
        assertTrue(WebServer.previewVisible(ALICE, ALICE, null, false));
        assertTrue(WebServer.previewVisible(ALICE, ALICE, LOCKED, false),
                "作者看自己的画不受锁定影响");
    }

    /** 未锁定的草稿墙是协作中间态，与「未锁墙谁都能 open」同一条决策。 */
    @Test
    void draftWall_visibleToAnySession() {
        assertTrue(WebServer.previewVisible(BOB, ALICE, null, false));
    }

    /** 锁定后非 owner 一律 403，除非持 canvas.admin.bypass-lock。 */
    @Test
    void lockedWall_hiddenFromNonOwnerUnlessBypass() {
        assertFalse(WebServer.previewVisible(BOB, ALICE, LOCKED, false),
                "锁定的墙不该被别人拉走画面");
        assertTrue(WebServer.previewVisible(BOB, ALICE, LOCKED, true),
                "持 canvas.admin.bypass-lock 的管理员可见（与 open 口径一致）");
    }

    /** owner 为 null（脏数据）时不能当成「谁都是 owner」。 */
    @Test
    void nullOwner_doesNotGrantOwnership() {
        assertFalse(WebServer.previewVisible(BOB, null, LOCKED, false));
        assertTrue(WebServer.previewVisible(BOB, null, null, false),
                "未锁定仍按草稿墙放行");
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
