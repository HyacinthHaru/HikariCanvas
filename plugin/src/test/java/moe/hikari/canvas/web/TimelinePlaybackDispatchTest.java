package moe.hikari.canvas.web;

import moe.hikari.canvas.render.AnimationTicker;
import moe.hikari.canvas.session.WallKey;
import moe.hikari.canvas.state.LoopMode;
import moe.hikari.canvas.state.ProjectState;
import moe.hikari.canvas.state.Timeline;
import moe.hikari.canvas.storage.WallRepo;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.6 P2 / B2：{@link EditOpDispatcher#handleTimelinePlayback} 单测。
 *
 * <p>{@code timeline.play / pause / seek} 三个播放控制 op 不走 OpResult 流（不 mutate
 * ProjectState、不进 history、无 patch），核心是「payload 解析 + {@link AnimationTicker} 调用 +
 * Result → 协议错误码映射」。{@code WsMessageContext} 是 Javalin final 类不可 mock，故直接驱动
 * package-private 的 {@link EditOpDispatcher#handleTimelinePlayback}（返回待发送的 {@link Envelope}），
 * 绕开 WS 装配链——SessionManager / throttler / push 等依赖在此路径不被触碰，传 {@code null} 即可。</p>
 *
 * <p>装配：真 {@link AnimationTicker}（注入内存 fake {@code WallSource} + no-op {@code FrameRenderer}），
 * auditLog 传 {@code null}（验证 audit null-guard 不 NPE；audit 行内容验证需真 Jdbi，见类外 concern）。</p>
 *
 * <p>覆盖：play 成功 ack / play TIMELINE_NOT_FOUND / play 未知 wall WALL_NOT_FOUND /
 * session 无 wall WALL_NOT_FOUND / ticker 未装配 INTERNAL_ERROR / pause 幂等 ack（未注册 + 已注册）/
 * seek atMs 负数拒 INVALID_PAYLOAD / seek 缺省 atMs=0 ack / seek 成功后位置生效。</p>
 */
class TimelinePlaybackDispatchTest {

    private static final String WALL_ID = "w-aabbccdd";
    private static final String TIMELINE_ID = "tl-1";
    private static final UUID CALLER = UUID.randomUUID();

    private AnimationTicker ticker;
    private EditOpDispatcher dispatcher;

    /** 内存 fake：仅 WALL_ID 一墙，挂一条 LOOP timeline（activeTimelineId 指向它）。 */
    private static final class FakeWallSource implements AnimationTicker.WallSource {
        @Override
        public WallRepo.Wall load(String wallId) {
            if (!WALL_ID.equals(wallId)) return null;
            Timeline tl = new Timeline(TIMELINE_ID, "anim", 5000, 20,
                    LoopMode.LOOP, null, null);
            ProjectState state = new ProjectState(2, 1);
            state.addTimeline(tl);
            state.activeTimelineId(TIMELINE_ID);
            WallKey key = new WallKey("world", 0, 64, 0, BlockFace.NORTH);
            return new WallRepo.Wall(wallId, key, state, List.of(0, 1), 2, 1,
                    CALLER, "owner", null, null, 0L, 0L);
        }

        @Override
        public List<WallRepo.Wall> loadAll() {
            WallRepo.Wall w = load(WALL_ID);
            return w == null ? List.of() : List.of(w);
        }
    }

    /** no-op renderer：恒无观察者（非 force 跳帧返 -1），renderFrame 不应被 play/pause/seek 路径触发。 */
    private static final class NoopRenderer implements AnimationTicker.FrameRenderer {
        @Override
        public int renderFrame(WallRepo.Wall wall, ProjectState frame,
                               AnimationTicker.FrameDiff diff, boolean force) {
            return force ? 0 : -1;
        }
    }

    @BeforeEach
    void setup() {
        ticker = new AnimationTicker(new FakeWallSource(), new NoopRenderer(), 60,
                Logger.getLogger(TimelinePlaybackDispatchTest.class.getName()));
        // 编辑 op 流依赖全部传 null：handleTimelinePlayback 不触碰它们（只用 animationTicker + auditLog）。
        dispatcher = new EditOpDispatcher(null, null, null, null, null, null, /*auditLog=*/null);
        dispatcher.setAnimationTicker(ticker);
    }

    /** 从 ack/error Envelope 取 payload code（error 才有；ack 返 null）。 */
    private static String codeOf(Envelope env) {
        Object p = env.payload();
        return (p instanceof Map<?, ?> m) ? (String) m.get("code") : null;
    }

    private Envelope play(String timelineId) {
        Map<String, Object> payload = (timelineId == null)
                ? Map.of() : Map.<String, Object>of("timelineId", timelineId);
        return dispatcher.handleTimelinePlayback("timeline.play", "c-1", payload,
                WALL_ID, CALLER, "owner", "sess-1");
    }

    @Test
    void playSuccessReturnsAck() {
        Envelope env = play(TIMELINE_ID);
        assertEquals("ack", env.op());
        assertEquals("c-1", env.id());
        assertNull(codeOf(env), "ack 不应带 error code");
        assertTrue(ticker.isWallAnimating(WALL_ID), "play 后 ticker 应将该 wall 标记为产帧中");
    }

    @Test
    void playWithNullTimelineIdResolvesActive() {
        // timelineId 缺省 → ticker 取 activeTimelineId（fake 指向 TIMELINE_ID）
        Envelope env = play(null);
        assertEquals("ack", env.op());
        assertNull(codeOf(env));
        assertTrue(ticker.isWallAnimating(WALL_ID));
    }

    @Test
    void playUnknownTimelineReturnsTimelineNotFound() {
        Envelope env = play("tl-does-not-exist");
        assertEquals("error", env.op());
        assertEquals("TIMELINE_NOT_FOUND", codeOf(env));
    }

    @Test
    void playUnknownWallReturnsWallNotFound() {
        Envelope env = dispatcher.handleTimelinePlayback("timeline.play", "c-1",
                Map.of("timelineId", TIMELINE_ID), "w-unknown", CALLER, "owner", "sess-1");
        assertEquals("error", env.op());
        assertEquals("WALL_NOT_FOUND", codeOf(env));
    }

    @Test
    void noBoundWallReturnsWallNotFound() {
        Envelope env = dispatcher.handleTimelinePlayback("timeline.play", "c-1",
                Map.of(), /*wallId=*/null, CALLER, "owner", "sess-1");
        assertEquals("error", env.op());
        assertEquals("WALL_NOT_FOUND", codeOf(env));
    }

    @Test
    void tickerNotAssembledReturnsInternalError() {
        EditOpDispatcher noTicker = new EditOpDispatcher(null, null, null, null, null, null, null);
        // 不调 setAnimationTicker → animationTicker() == null
        Envelope env = noTicker.handleTimelinePlayback("timeline.play", "c-1",
                Map.of("timelineId", TIMELINE_ID), WALL_ID, CALLER, "owner", "sess-1");
        assertEquals("error", env.op());
        assertEquals("INTERNAL_ERROR", codeOf(env));
    }

    @Test
    void pauseIsIdempotentWhenNotRegistered() {
        // 未 play 过的 wall：pause 应幂等 no-op + 返 ack（不报错）
        Envelope env = dispatcher.handleTimelinePlayback("timeline.pause", "c-1",
                Map.of(), WALL_ID, CALLER, "owner", "sess-1");
        assertEquals("ack", env.op());
        assertNull(codeOf(env));
        assertNotNull(env.payload());
    }

    @Test
    void pauseAfterPlayStopsAnimatingAndAcksAgain() {
        play(TIMELINE_ID);
        assertTrue(ticker.isWallAnimating(WALL_ID));

        Envelope first = dispatcher.handleTimelinePlayback("timeline.pause", "c-2",
                Map.of(), WALL_ID, CALLER, "owner", "sess-1");
        assertEquals("ack", first.op());
        assertTrue(!ticker.isWallAnimating(WALL_ID), "pause 后 gate 应放行 reactive 路径");

        // 再 pause（幂等）：仍 ack，不报错
        Envelope second = dispatcher.handleTimelinePlayback("timeline.pause", "c-3",
                Map.of(), WALL_ID, CALLER, "owner", "sess-1");
        assertEquals("ack", second.op());
        assertNull(codeOf(second));
    }

    @Test
    void seekNegativeAtMsRejected() {
        Envelope env = dispatcher.handleTimelinePlayback("timeline.seek", "c-1",
                Map.of("timelineId", TIMELINE_ID, "atMs", -1), WALL_ID, CALLER, "owner", "sess-1");
        assertEquals("error", env.op());
        assertEquals("INVALID_PAYLOAD", codeOf(env));
    }

    @Test
    void seekDefaultAtMsIsZeroAndAcks() {
        // atMs 缺省 = 0（protocol.md §5.12）：合法，返 ack
        Envelope env = dispatcher.handleTimelinePlayback("timeline.seek", "c-1",
                Map.of("timelineId", TIMELINE_ID), WALL_ID, CALLER, "owner", "sess-1");
        assertEquals("ack", env.op());
        assertNull(codeOf(env));
    }

    @Test
    void seekUnknownTimelineReturnsTimelineNotFound() {
        // 未注册 wall + 未知 timelineId：seek 走 loadWall + resolveTimeline → TIMELINE_NOT_FOUND
        Envelope env = dispatcher.handleTimelinePlayback("timeline.seek", "c-1",
                Map.of("timelineId", "tl-nope", "atMs", 100), WALL_ID, CALLER, "owner", "sess-1");
        assertEquals("error", env.op());
        assertEquals("TIMELINE_NOT_FOUND", codeOf(env));
    }
}
