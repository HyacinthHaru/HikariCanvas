package ac.haru.hikaricanvas.state;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 2026-05-14 Bug 2 回归测试：{@link EditSession#purgeStaleStrokes()} 行为验证。
 *
 * <p>原 bug：{@code purgeStaleStrokes} 只在 {@code startBrush} 内调用，用户永久离开后
 * stroke buffer 永不清理 → 服务端内存泄漏。修复：{@link ac.haru.hikaricanvas.session.SessionReaper#sweep}
 * 每 30s 周期通过 {@code SessionManager.purgeAllStaleStrokes} 触发。本测试覆盖纯函数行为。</p>
 */
class EditSessionBrushPurgeTest {

    private static EditSession newSession() {
        return new EditSession(new ProjectState(2, 1));
    }

    private static String startBrush(EditSession es) {
        return ((EditSession.OpResult.OkBrushStart) es.startBrush(
                Map.of("size", 4, "color", "#FF0000"), null)).strokeId();
    }

    @Test
    void freshStrokeIsNotPurged() {
        EditSession es = newSession();
        String sid = startBrush(es);
        assertEquals(1, es.activeStrokeCountForTest());
        // 新鲜笔触（lastActivityMs ≈ now）→ purge 不动它
        es.purgeStaleStrokes();
        assertEquals(1, es.activeStrokeCountForTest());
        // cleanup
        es.cancelBrush(sid);
    }

    @Test
    void staleStrokeIsPurged() {
        EditSession es = newSession();
        String sid = startBrush(es);
        // 把 lastActivityMs 设为 0（远早于 30s timeout cutoff）
        es.overrideStrokeActivityForTest(sid, 0);
        es.purgeStaleStrokes();
        assertEquals(0, es.activeStrokeCountForTest());
        // stale 后再 brush.end 应失败（buffer 已清）
        EditSession.OpResult r = es.endBrush(sid);
        assertInstanceOf(EditSession.OpResult.Error.class, r);
        assertEquals("INVALID_STROKE", ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void mixedStrokesPurgeOnlyStale() {
        EditSession es = newSession();
        String fresh = startBrush(es);
        String stale = startBrush(es);
        assertEquals(2, es.activeStrokeCountForTest());
        // 仅 stale 那条标过期
        es.overrideStrokeActivityForTest(stale, 0);
        es.purgeStaleStrokes();
        // fresh 仍在；stale 已清
        assertEquals(1, es.activeStrokeCountForTest());
        // fresh 可继续 endBrush + 创建 element
        es.appendBrushPoints(fresh, List.of(
                new BrushPoint(10, 10, 0.5),
                new BrushPoint(20, 20, 0.5)));
        EditSession.OpResult r = es.endBrush(fresh);
        assertInstanceOf(EditSession.OpResult.Ok.class, r);
        // stale 那条 endBrush 应返 INVALID_STROKE
        EditSession.OpResult r2 = es.endBrush(stale);
        assertInstanceOf(EditSession.OpResult.Error.class, r2);
    }

    @Test
    void startBrushTriggersPurge() {
        // 原行为：startBrush 内调 purgeStaleStrokes（M12-A 引入）；本测试固化
        EditSession es = newSession();
        String sid = startBrush(es);
        es.overrideStrokeActivityForTest(sid, 0);
        // 不显式 purge，仅再 startBrush；间接调用 purge 清掉 stale
        String fresh = startBrush(es);
        assertNotNull(fresh);
        // 之前的 stale 应已被 purge
        assertEquals(1, es.activeStrokeCountForTest());
        es.cancelBrush(fresh);
    }
}
