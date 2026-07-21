package ac.haru.hikaricanvas.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 自适应渲染：{@link ProjectionThrottler#setIntervalForSession} +
 * {@link ProjectionThrottler#clearSessionInterval} + {@link ProjectionThrottler#discardSession}
 * 覆盖。
 *
 * <p>只覆盖 sessionIntervalOverride map 行为；submit 的尾帧调度路径需要 Bukkit
 * scheduler，由集成 / runServer 手测覆盖（避免引入 MockBukkit 单测依赖）。
 * 构造期把 plugin / sessionManager / projector 传 null —— 本测不触发任何会走
 * Bukkit.getScheduler 的路径。</p>
 */
class ProjectionThrottlerTest {

    @Test
    void effectiveInterval_defaultsToMinIntervalMs() {
        ProjectionThrottler t = new ProjectionThrottler(null, null, null, 200L);
        assertEquals(200L, t.effectiveIntervalForTest("sid-1"),
                "未设置 override 时回落到构造期 minIntervalMs");
    }

    @Test
    void setIntervalForSession_overridesDefault() {
        ProjectionThrottler t = new ProjectionThrottler(null, null, null, 200L);
        t.setIntervalForSession("sid-1", 50L);
        assertEquals(50L, t.effectiveIntervalForTest("sid-1"),
                "set 后 effectiveInterval 取 override");
        // 其它 session 不受影响
        assertEquals(200L, t.effectiveIntervalForTest("sid-2"));
    }

    @Test
    void clearSessionInterval_removesOverride() {
        ProjectionThrottler t = new ProjectionThrottler(null, null, null, 200L);
        t.setIntervalForSession("sid-1", 50L);
        t.clearSessionInterval("sid-1");
        assertEquals(200L, t.effectiveIntervalForTest("sid-1"),
                "clear 后回落默认");
    }

    @Test
    void setIntervalForSession_nonPositiveActsAsClear() {
        ProjectionThrottler t = new ProjectionThrottler(null, null, null, 200L);
        t.setIntervalForSession("sid-1", 50L);
        t.setIntervalForSession("sid-1", 0L);
        assertEquals(200L, t.effectiveIntervalForTest("sid-1"),
                "intervalMs<=0 等同 clearSessionInterval");
        t.setIntervalForSession("sid-1", 80L);
        t.setIntervalForSession("sid-1", -5L);
        assertEquals(200L, t.effectiveIntervalForTest("sid-1"),
                "负数也等同 clear");
    }

    @Test
    void discardSession_clearsOverride() {
        ProjectionThrottler t = new ProjectionThrottler(null, null, null, 200L);
        t.setIntervalForSession("sid-1", 50L);
        t.discardSession("sid-1");
        assertEquals(200L, t.effectiveIntervalForTest("sid-1"),
                "discardSession 清掉 sessionIntervalOverride 防泄漏");
    }

    @Test
    void setIntervalForSession_nullSessionIdNoop() {
        ProjectionThrottler t = new ProjectionThrottler(null, null, null, 200L);
        // 不抛
        t.setIntervalForSession(null, 50L);
        t.clearSessionInterval(null);
    }
}
