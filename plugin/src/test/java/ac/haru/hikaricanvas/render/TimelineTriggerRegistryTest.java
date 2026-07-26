package ac.haru.hikaricanvas.render;

import ac.haru.hikaricanvas.state.EditSession;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.storage.WallRepo;
import ac.haru.hikaricanvas.variable.VariableInterpolator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.6 P5：{@link TimelineTriggerRegistry} 单测——变量变化 → 播放绑定时间轴。
 *
 * <p>用真实 {@link VariableInterpolator#resolveFullName} 作 resolver，验证"trigger 配 user/X →
 * 必须注入 wallId 成 user:&lt;wallId&gt;/X 才命中"这条一致性坑（§5.2 R）。player 用捕获桩、clock 注入。</p>
 */
class TimelineTriggerRegistryTest {

    private static final class CapturePlayer implements TimelineTriggerRegistry.TimelinePlayer {
        final List<String> calls = new ArrayList<>();
        @Override public void play(String wallId, String timelineId) { calls.add(wallId + "/" + timelineId); }
    }

    private static WallRepo.Wall wall(String wallId, ProjectState state) {
        return new WallRepo.Wall(wallId, null, state, List.of(), 1, 1, null, null, null, null, 0L, 0L);
    }

    /** 造一个 activeTimeline 带指定 trigger 的 state（type=manual 时 rawFullName 传 null）。 */
    private static ProjectState stateWithTrigger(String type, String rawFullName) {
        EditSession es = new EditSession(new ProjectState(2, 1));
        es.setTimelineFpsLimits(20, 60);
        Map<String, Object> trig = rawFullName == null
                ? Map.of("type", type)
                : Map.of("type", type, "params", Map.of("fullName", rawFullName));
        EditSession.OpResult r = es.createTimeline("T", 5000, 30, "loop", trig);
        assertTrue(r instanceof EditSession.OpResult.Ok, "createTimeline should succeed");
        return es.state();
    }

    private static TimelineTriggerRegistry registry(CapturePlayer player, WallRepo.Wall... walls) {
        Map<String, WallRepo.Wall> byId = new HashMap<>();
        for (WallRepo.Wall w : walls) byId.put(w.wallId(), w);
        AnimationTicker.WallSource src = new AnimationTicker.WallSource() {
            @Override public WallRepo.Wall load(String wallId) { return byId.get(wallId); }
            @Override public List<WallRepo.Wall> loadAll() { return new ArrayList<>(byId.values()); }
        };
        return new TimelineTriggerRegistry(src, VariableInterpolator::resolveFullName, player,
                Logger.getAnonymousLogger());
    }

    @Test
    void variableChangeFiresPlayWithResolvedFullName() {
        CapturePlayer player = new CapturePlayer();
        ProjectState st = stateWithTrigger("variableChange", "user/hp");
        String tlId = st.activeTimelineId();
        TimelineTriggerRegistry reg = registry(player, wall("w-1", st));
        reg.rebuildAll();

        // trigger 配 user/hp + wallId=w-1 → 内部 user:w-1/hp
        assertEquals(1, reg.bindingCount("user:w-1/hp"));
        // 字面 user/hp 不命中（必须解析后形式）
        reg.onVariableChange("user/hp");
        assertTrue(player.calls.isEmpty(), "字面名不应命中");
        // 解析后命中 → play
        reg.onVariableChange("user:w-1/hp");
        assertEquals(List.of("w-1/" + tlId), player.calls);
    }

    @Test
    void manualTriggerNotRegistered() {
        CapturePlayer player = new CapturePlayer();
        ProjectState st = stateWithTrigger("manual", null);
        TimelineTriggerRegistry reg = registry(player, wall("w-1", st));
        reg.rebuildAll();
        reg.onVariableChange("user:w-1/hp");
        assertTrue(player.calls.isEmpty(), "MANUAL 触发不进触发表");
    }

    @Test
    void scheduleTriggerResolvesScheduleNamespace() {
        CapturePlayer player = new CapturePlayer();
        ProjectState st = stateWithTrigger("schedule", "schedule/eta_seconds");
        String tlId = st.activeTimelineId();
        TimelineTriggerRegistry reg = registry(player, wall("w-7", st));
        reg.rebuildAll();
        assertEquals(1, reg.bindingCount("schedule:w-7/eta_seconds"));
        reg.onVariableChange("schedule:w-7/eta_seconds");
        assertEquals(List.of("w-7/" + tlId), player.calls);
    }

    @Test
    void debounceSuppressesRapidRepeat() {
        CapturePlayer player = new CapturePlayer();
        ProjectState st = stateWithTrigger("variableChange", "user/hp");
        TimelineTriggerRegistry reg = registry(player, wall("w-1", st));
        AtomicLong now = new AtomicLong(1000);
        reg.setClock(now::get);
        reg.rebuildAll();

        reg.onVariableChange("user:w-1/hp");   // t=1000 → play
        now.set(1100);                          // +100ms < 200 窗口
        reg.onVariableChange("user:w-1/hp");   // 去抖 → 不播
        now.set(1300);                          // 距上次播 300ms > 200
        reg.onVariableChange("user:w-1/hp");   // 播
        assertEquals(2, player.calls.size(), "200ms 窗口内重复触发只播一次");
    }

    @Test
    void removeWallClearsBinding() {
        CapturePlayer player = new CapturePlayer();
        ProjectState st = stateWithTrigger("variableChange", "user/hp");
        TimelineTriggerRegistry reg = registry(player, wall("w-1", st));
        reg.rebuildAll();
        assertEquals(1, reg.bindingCount("user:w-1/hp"));

        reg.removeWall("w-1");
        assertEquals(0, reg.bindingCount("user:w-1/hp"));
        reg.onVariableChange("user:w-1/hp");
        assertTrue(player.calls.isEmpty());
    }

    @Test
    void rebuildForWallPicksUpTriggerChange() {
        CapturePlayer player = new CapturePlayer();
        // 起始 MANUAL（不进表）
        ProjectState manual = stateWithTrigger("manual", null);
        Map<String, WallRepo.Wall> byId = new HashMap<>();
        byId.put("w-1", wall("w-1", manual));
        AnimationTicker.WallSource src = new AnimationTicker.WallSource() {
            @Override public WallRepo.Wall load(String wallId) { return byId.get(wallId); }
            @Override public List<WallRepo.Wall> loadAll() { return new ArrayList<>(byId.values()); }
        };
        TimelineTriggerRegistry reg = new TimelineTriggerRegistry(
                src, VariableInterpolator::resolveFullName, player, Logger.getAnonymousLogger());
        reg.rebuildAll();
        assertEquals(0, reg.bindingCount("user:w-1/hp"));

        // 用户改 trigger → VARIABLE_CHANGE 并持久化；rebuildForWall 重读新 state
        ProjectState changed = stateWithTrigger("variableChange", "user/hp");
        byId.put("w-1", wall("w-1", changed));
        reg.rebuildForWall("w-1");
        assertEquals(1, reg.bindingCount("user:w-1/hp"));
    }

    // ──────────────────────────────────────────────────────────
    //  与姊妹类 TriggerRouter 的同步纪律对齐
    // ──────────────────────────────────────────────────────────

    /**
     * 重建类方法必须是方法级 synchronized。
     *
     * <p>索引本身是 CHM，但"清旧 + 重登"要作为一个整体原子：两个线程（Jetty 编辑线程的
     * persistWall 与脚本线程的 ElementPropertyApplier→persistWall）同时重建同一面墙时，
     * 会出现「A 已拿到 wallKeys 快照 → B 写入新 binding → A 按旧快照 removeIf 把 B 刚加的删掉」，
     * 结果 wallKeys 说已绑、byFullName 却是空的，该墙的变量 / 时刻表触发静默失效到下次 rebuild
     * （铁路屏不再随变量播，且没有任何报错）。同型的 {@code TriggerRouter} 从一开始就是全方法
     * synchronized，本类漏了——这条守卫钉住两边一致。</p>
     */
    @Test
    void rebuildMethodsAreSynchronized_likeTriggerRouter() {
        assertSynchronized(TimelineTriggerRegistry.class,
                "rebuildAll", "rebuildForWall", "removeWall", "registerWall");
        // 姊妹类同款纪律（防止哪天反过来把 TriggerRouter 的锁去掉）
        assertSynchronized(ac.haru.hikaricanvas.script.engine.TriggerRouter.class,
                "rebuildAll", "rebuildWall", "removeWall", "shutdown");
    }

    private static void assertSynchronized(Class<?> type, String... methodNames) {
        for (String name : methodNames) {
            boolean found = false;
            for (java.lang.reflect.Method m : type.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                found = true;
                assertTrue(java.lang.reflect.Modifier.isSynchronized(m.getModifiers()),
                        type.getSimpleName() + "." + name + " 必须 synchronized（清旧+重登要原子）");
            }
            assertTrue(found, "找不到方法 " + type.getSimpleName() + "." + name);
        }
    }
}
