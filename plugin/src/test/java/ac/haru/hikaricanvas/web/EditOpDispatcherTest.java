package ac.haru.hikaricanvas.web;

import ac.haru.hikaricanvas.render.AnimationTicker;
import ac.haru.hikaricanvas.session.WallKey;
import ac.haru.hikaricanvas.state.EditSession;
import ac.haru.hikaricanvas.state.LoopMode;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.state.Timeline;
import ac.haru.hikaricanvas.storage.WallRepo;
import ac.haru.hikaricanvas.template.TemplateEntry;
import ac.haru.hikaricanvas.template.TemplateSource;
import ac.haru.hikaricanvas.template.TemplateSpec;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EditOpDispatcher} 单元级分发测试：覆盖播放控制（{@code timeline.play / pause / seek}）、
 * 画布补间帧率（{@code canvas.tweenFps}）与模板 pack 降级守卫（{@code applyPackEntry}）。
 */
class EditOpDispatcherTest {

    @Nested
    class TimelinePlayback {

        private static final String WALL_ID = "w-aabbccdd";
        private static final String TIMELINE_ID = "tl-1";
        private static final UUID CALLER = UUID.randomUUID();

        private AnimationTicker ticker;
        private EditOpDispatcher dispatcher;

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

        private static final class NoopRenderer implements AnimationTicker.FrameRenderer {
            @Override
            public int renderFrame(WallRepo.Wall wall, ProjectState frame,
                                   AnimationTicker.FrameDiff diff, boolean force) {
                return force ? 0 : -1;
            }
        }

        @BeforeEach
        void setUp() {
            ticker = new AnimationTicker(new FakeWallSource(), new NoopRenderer(), 60,
                    Logger.getLogger(EditOpDispatcherTest.class.getName()));
            dispatcher = new EditOpDispatcher(null, null, null, null, null, null, null);
            dispatcher.setAnimationTicker(ticker);
        }

        private static String codeOf(Envelope env) {
            Object p = env.payload();
            return (p instanceof Map<?, ?> m) ? (String) m.get("code") : null;
        }

        private Envelope play(String timelineId) {
            Map<String, Object> payload = (timelineId == null)
                    ? Map.of() : Map.of("timelineId", timelineId);
            return dispatcher.handleTimelinePlayback("timeline.play", "c-1", payload,
                    WALL_ID, CALLER, "owner", "sess-1");
        }

        @Test
        void playSuccessReturnsAck() {
            Envelope env = play(TIMELINE_ID);
            assertEquals("ack", env.op());
            assertEquals("c-1", env.id());
            assertNull(codeOf(env));
            assertTrue(ticker.isWallAnimating(WALL_ID));
        }

        @Test
        void playWithNullTimelineIdResolvesActive() {
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
                    Map.of(), null, CALLER, "owner", "sess-1");
            assertEquals("error", env.op());
            assertEquals("WALL_NOT_FOUND", codeOf(env));
        }

        @Test
        void tickerNotAssembledReturnsInternalError() {
            EditOpDispatcher noTicker = new EditOpDispatcher(null, null, null, null, null, null, null);
            Envelope env = noTicker.handleTimelinePlayback("timeline.play", "c-1",
                    Map.of("timelineId", TIMELINE_ID), WALL_ID, CALLER, "owner", "sess-1");
            assertEquals("error", env.op());
            assertEquals("INTERNAL_ERROR", codeOf(env));
        }

        @Test
        void pauseIsIdempotentWhenNotRegistered() {
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
            assertFalse(ticker.isWallAnimating(WALL_ID));

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
            Envelope env = dispatcher.handleTimelinePlayback("timeline.seek", "c-1",
                    Map.of("timelineId", TIMELINE_ID), WALL_ID, CALLER, "owner", "sess-1");
            assertEquals("ack", env.op());
            assertNull(codeOf(env));
        }

        @Test
        void seekUnknownTimelineReturnsTimelineNotFound() {
            Envelope env = dispatcher.handleTimelinePlayback("timeline.seek", "c-1",
                    Map.of("timelineId", "tl-nope", "atMs", 100), WALL_ID, CALLER, "owner", "sess-1");
            assertEquals("error", env.op());
            assertEquals("TIMELINE_NOT_FOUND", codeOf(env));
        }
    }

    @Nested
    class CanvasTweenFps {

        private EditOpDispatcher dispatcher;
        private EditSession es;

        @BeforeEach
        void setUp() {
            dispatcher = new EditOpDispatcher(null, null, null, null, null, null, null);
            es = new EditSession(new ProjectState(2, 2));
        }

        @Test
        void validFps_stores_value_and_returns_ok() {
            EditSession.OpResult r = dispatcher.handleCanvasTweenFps(Map.of("fps", 15), es);

            assertInstanceOf(EditSession.OpResult.Ok.class, r);
            assertEquals(15, es.state().tweenFps());
            assertEquals(15, es.state().effectiveTweenFps());
        }

        @Test
        void nullFps_clears_to_default() {
            dispatcher.handleCanvasTweenFps(Map.of("fps", 20), es);
            assertEquals(20, es.state().tweenFps());

            Map<String, Object> payload = new HashMap<>();
            payload.put("fps", null);
            EditSession.OpResult r = dispatcher.handleCanvasTweenFps(payload, es);

            assertInstanceOf(EditSession.OpResult.Ok.class, r);
            assertNull(es.state().tweenFps());
            assertEquals(30, es.state().effectiveTweenFps());
        }

        @Test
        void missingFpsKey_treatedAsNull() {
            dispatcher.handleCanvasTweenFps(Map.of("fps", 10), es);

            EditSession.OpResult r = dispatcher.handleCanvasTweenFps(Map.of(), es);

            assertInstanceOf(EditSession.OpResult.Ok.class, r);
            assertNull(es.state().tweenFps());
            assertEquals(30, es.state().effectiveTweenFps());
        }

        @Test
        void outOfRange_fps_returns_invalid_payload() {
            EditSession.OpResult r = dispatcher.handleCanvasTweenFps(Map.of("fps", 61), es);

            EditSession.OpResult.Error err = assertInstanceOf(EditSession.OpResult.Error.class, r);
            assertEquals("INVALID_PAYLOAD", err.code());
            assertNull(es.state().tweenFps());
        }

        @Test
        void negativeNonZero_fps_returns_invalid_payload() {
            EditSession.OpResult r = dispatcher.handleCanvasTweenFps(Map.of("fps", -1), es);

            EditSession.OpResult.Error err = assertInstanceOf(EditSession.OpResult.Error.class, r);
            assertEquals("INVALID_PAYLOAD", err.code());
        }

        @Test
        void nonNumberFps_returns_invalid_payload_from_type_guard() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("fps", "fast");
            EditSession.OpResult r = dispatcher.handleCanvasTweenFps(payload, es);

            EditSession.OpResult.Error err = assertInstanceOf(EditSession.OpResult.Error.class, r);
            assertEquals("INVALID_PAYLOAD", err.code());
            assertNull(es.state().tweenFps());
        }
    }

    @Nested
    class PackApplyFallback {

        @Test
        void packEntry_withoutImporter_returnsInternalError() {
            EditOpDispatcher dispatcher = new EditOpDispatcher(null, null, null, null, null, null, null);
            TemplateSpec spec = new TemplateSpec(1, "subway", "Subway", null, null, null, null, null,
                    null, Map.of());
            TemplateEntry packEntry = TemplateEntry.pack(
                    spec, TemplateSource.SERVER, "server:subway.canvas",
                    Optional.empty(), new byte[]{1, 2, 3});

            EditSession.OpResult r = dispatcher.applyPackEntry(
                    "sess-1", "subway", packEntry, Map.of(), null);

            EditSession.OpResult.Error err = assertInstanceOf(EditSession.OpResult.Error.class, r);
            assertEquals("INTERNAL_ERROR", err.code());
        }
    }
}
