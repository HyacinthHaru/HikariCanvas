package moe.hikari.canvas.web;

import moe.hikari.canvas.state.EditSession;
import moe.hikari.canvas.state.ProjectState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 0.7.3 ultrareview D2：{@link EditOpDispatcher#handleCanvasTweenFps} dispatch 路径单测。
 *
 * <p><b>背景</b>：{@code canvas.tweenFps} op 在 {@link EditOpDispatcher#dispatch} switch
 * 里有完整 handler，但 {@code WebServer.handleMessage} 的路由 switch 漏了此 case，导致
 * 前端发的 {@code canvas.tweenFps} 落 default → {@code INVALID_OP}，用户改帧率乐观更新
 * 本地但后端永远不写入。修复：WebServer switch 加 {@code "canvas.tweenFps"} case，并将
 * 逻辑提取为 package-private {@link EditOpDispatcher#handleCanvasTweenFps}（照
 * {@link EditOpDispatcher#handleTimelinePlayback} 的可测 seam 范式）。</p>
 *
 * <p><b>测试目的</b>：覆盖 dispatch handler 这一层（payload 解析 → {@code EditSession.setTweenFps}
 * 调用 → {@code ProjectState.tweenFps} 真被写入），避免有人日后把 {@code canvas.tweenFps}
 * 从 {@code EditOpDispatcher.dispatch} switch 删掉后测试也无法拦截。{@code WsMessageContext}
 * 是 Javalin final 类不可 mock，故直接驱动 package-private 的 {@code handleCanvasTweenFps}
 * ——与 {@link TimelinePlaybackDispatchTest} 同范式，绕开 WS 装配链。</p>
 *
 * <p>覆盖：① fps=15 合法 → ack + state 写入 ② fps=null → 清回 null（effectiveTweenFps=30）
 * ③ fps=61 越界 → INVALID_PAYLOAD ④ fps="not-a-number"（非 Number 类型）→ INVALID_PAYLOAD
 * ⑤ fps 键缺失（payload 无该键）→ 等同 null，清回 null</p>
 */
class CanvasTweenFpsDispatchTest {

    private EditOpDispatcher dispatcher;
    private EditSession es;

    @BeforeEach
    void setup() {
        // 仅测 handleCanvasTweenFps，不触碰 sessionManager / rateLimiter / push 等装配链。
        dispatcher = new EditOpDispatcher(
                /*sessionManager=*/null, /*throttler=*/null, /*rateLimiter=*/null,
                /*templateRegistry=*/null, /*wallRepo=*/null, /*push=*/null,
                /*auditLog=*/null);
        es = new EditSession(new ProjectState(2, 2));
    }

    /** fps=15（合法值）→ OpResult.Ok + state.tweenFps=15 + effectiveTweenFps=15。 */
    @Test
    void validFps_stores_value_and_returns_ok() {
        EditSession.OpResult r = dispatcher.handleCanvasTweenFps(Map.of("fps", 15), es);

        assertEquals("ok", resultType(r), "fps=15 合法，应返 ok");
        assertEquals(15, es.state().tweenFps(), "state.tweenFps 应为 15");
        assertEquals(15, es.state().effectiveTweenFps(), "effectiveTweenFps 应为 15");
    }

    /** fps=null（explicit null）→ 清回 null，effectiveTweenFps=30（默认）。 */
    @Test
    void nullFps_clears_to_default() {
        // 先设一个值
        dispatcher.handleCanvasTweenFps(Map.of("fps", 20), es);
        assertEquals(20, es.state().tweenFps());

        // 显式 null → Map 只有 key 但值为 null
        Map<String, Object> payload = new HashMap<>();
        payload.put("fps", null);
        EditSession.OpResult r = dispatcher.handleCanvasTweenFps(payload, es);

        assertEquals("ok", resultType(r), "null fps 应返 ok（清回默认）");
        assertNull(es.state().tweenFps(), "state.tweenFps 应清回 null");
        assertEquals(30, es.state().effectiveTweenFps(), "effectiveTweenFps 应回默认 30");
    }

    /** fps 键缺失（payload 为空 map）→ 等同 null，清回 null。 */
    @Test
    void missingFpsKey_treatedAsNull() {
        dispatcher.handleCanvasTweenFps(Map.of("fps", 10), es);

        EditSession.OpResult r = dispatcher.handleCanvasTweenFps(Map.of(), es);

        assertEquals("ok", resultType(r));
        assertNull(es.state().tweenFps(), "payload 缺 fps 键，应清回 null（等同 null）");
        assertEquals(30, es.state().effectiveTweenFps());
    }

    /** fps=61 超上限 → INVALID_PAYLOAD（由 EditSession.setTweenFps 校验）。 */
    @Test
    void outOfRange_fps_returns_invalid_payload() {
        EditSession.OpResult r = dispatcher.handleCanvasTweenFps(Map.of("fps", 61), es);

        assertEquals("error", resultType(r));
        assertEquals("INVALID_PAYLOAD", errorCode(r));
        // state 不应被改（仍为初始 null）
        assertNull(es.state().tweenFps(), "越界 fps 不应写入 state");
    }

    /** fps=-1 低于下限 → INVALID_PAYLOAD。 */
    @Test
    void negativeNonZero_fps_returns_invalid_payload() {
        EditSession.OpResult r = dispatcher.handleCanvasTweenFps(Map.of("fps", -1), es);

        assertEquals("error", resultType(r));
        assertEquals("INVALID_PAYLOAD", errorCode(r));
    }

    /** fps 值为非 Number 类型（如字符串 "fast"）→ INVALID_PAYLOAD（在 handleCanvasTweenFps 前置守卫拦截）。 */
    @Test
    void nonNumberFps_returns_invalid_payload_from_type_guard() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("fps", "fast");
        EditSession.OpResult r = dispatcher.handleCanvasTweenFps(payload, es);

        assertEquals("error", resultType(r));
        assertEquals("INVALID_PAYLOAD", errorCode(r));
        assertNull(es.state().tweenFps(), "类型错误不应写入 state");
    }

    // ──────── helpers ────────

    private static String resultType(EditSession.OpResult r) {
        return switch (r) {
            case EditSession.OpResult.Ok ok -> "ok";
            case EditSession.OpResult.OkSnapshot oks -> "ok";
            case EditSession.OpResult.OkBrushStart obs -> "ok";
            case EditSession.OpResult.Error er -> "error";
        };
    }

    private static String errorCode(EditSession.OpResult r) {
        if (r instanceof EditSession.OpResult.Error er) return er.code();
        return null;
    }
}
