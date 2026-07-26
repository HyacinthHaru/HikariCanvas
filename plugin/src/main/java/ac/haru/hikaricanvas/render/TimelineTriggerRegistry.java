package ac.haru.hikaricanvas.render;

import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.state.Timeline;
import ac.haru.hikaricanvas.state.TriggerConfig;
import ac.haru.hikaricanvas.state.TriggerType;
import ac.haru.hikaricanvas.storage.WallRepo;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * 时间轴触发器路由（docs/timeline.md §5.2 / §5.3）。
 *
 * <p>把"变量变化 → 播放绑定该变量的 wall 时间轴"这件事做成一个**薄索引 + 监听**：维护
 * {@code 解析后fullName → Set<(wallId, timelineId)>} 倒排表，变量变化事件到达时 O(1) 查表 →
 * 调 {@link TimelinePlayer#play} 重播。{@code MANUAL} 触发不进本表（由 AnimationTicker 的
 * "wall ready 自动播 LOOP"管）；{@code VARIABLE_CHANGE} / {@code SCHEDULE} 才登记。</p>
 *
 * <p>构造参数全是 seam（player / resolver / wallSource / clock），不直接依赖 AnimationTicker /
 * VariableStore 具体类型，可换实现。</p>
 *
 * <p><b>线程安全：</b>索引用 {@link ConcurrentHashMap} + {@link ConcurrentHashMap#newKeySet}，
 * 但"清旧 + 重登"必须是一个原子动作——{@link #rebuildAll} / {@link #rebuildForWall} /
 * {@link #removeWall} 与私有的 registerWall 一律<b>方法级 synchronized</b>，与姊妹类
 * {@code TriggerRouter} 对齐。两个线程（Jetty 编辑线程的 persistWall 与脚本线程的
 * ElementPropertyApplier→persistWall）同时重建同一面墙时，不同步会出现
 * 「A 已拿到 wallKeys 快照 → B 写入新 binding → A 按旧快照 removeIf 把 B 刚加的删掉」，
 * 结果 wallKeys 说已绑、byFullName 却是空的，该墙的变量/时刻表触发静默失效到下次 rebuild。</p>
 *
 * <p>{@link #onVariableChange} 走无锁读（CHM），rebuild 中途的瞬时 miss 可接受——
 * 同 TriggerRouter 纪律：读路径不进锁，避免触发链被重建阻塞。</p>
 */
public final class TimelineTriggerRegistry {

    /** 触发动作 seam（生产 = {@code AnimationTicker::play} 忽略 Result）。 */
    @FunctionalInterface
    public interface TimelinePlayer {
        void play(String wallId, String timelineId);
    }

    /** rawName + wallId → 内部 fullName seam（生产 = {@code VariableInterpolator::resolveFullName}）。 */
    @FunctionalInterface
    public interface FullNameResolver {
        String resolve(String rawName, String wallId);
    }

    /** 去抖窗口：同一 (wall,timeline) 在窗口内的重复触发只播一次，挡高频变量（eta_seconds 每秒变）thrash。 */
    static final long DEBOUNCE_MS = 200L;

    /** 触发器配置的变量名参数键（trigger.params 里）。 */
    public static final String PARAM_FULL_NAME = "fullName";

    private final AnimationTicker.WallSource wallSource;
    private final FullNameResolver resolver;
    private final TimelinePlayer player;
    private final Logger log;
    private volatile LongSupplier clock = System::currentTimeMillis;

    /** 解析后 fullName → 绑定集合（倒排表）。 */
    private final Map<String, Set<Binding>> byFullName = new ConcurrentHashMap<>();
    /** wallId → 它贡献的解析后 fullName 集合（增量清理用，避免全表扫）。 */
    private final Map<String, Set<String>> wallKeys = new ConcurrentHashMap<>();
    /** 去抖：{@code wallId:timelineId} → 上次触发时刻（ms）。 */
    private final Map<String, Long> lastFire = new ConcurrentHashMap<>();

    private record Binding(String wallId, String timelineId) {}

    public TimelineTriggerRegistry(AnimationTicker.WallSource wallSource,
                                   FullNameResolver resolver, TimelinePlayer player, Logger log) {
        this.wallSource = wallSource;
        this.resolver = resolver;
        this.player = player;
        this.log = log;
    }

    /** 测试 seam：替换去抖判定时钟。 */
    void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    /** 全库重建（启动期，autoRegisterAll 之后调一次）。 */
    public synchronized void rebuildAll() {
        byFullName.clear();
        wallKeys.clear();
        lastFire.clear();
        int n = 0;
        for (WallRepo.Wall w : wallSource.loadAll()) {
            if (w != null && w.state() != null && registerWall(w.wallId(), w.state())) n++;
        }
        if (n > 0) log.info("TimelineTriggerRegistry: " + n + " wall(s) with variable/schedule trigger");
    }

    /** 重建某 wall 的触发绑定（编辑持久化 / session 关闭后调）：清旧 + 按当前 activeTimeline 的 trigger 重登。 */
    public synchronized void rebuildForWall(String wallId) {
        if (wallId == null) return;
        removeWall(wallId);
        WallRepo.Wall w = wallSource.load(wallId);
        if (w == null || w.state() == null) return;
        registerWall(wallId, w.state());
    }

    /** 注销某 wall 的全部触发绑定（/canvas delete 后调）。 */
    public synchronized void removeWall(String wallId) {
        if (wallId == null) return;
        Set<String> keys = wallKeys.remove(wallId);
        if (keys != null) {
            for (String fn : keys) {
                Set<Binding> set = byFullName.get(fn);
                if (set == null) continue;
                set.removeIf(b -> b.wallId().equals(wallId));
                if (set.isEmpty()) byFullName.remove(fn);
            }
        }
        lastFire.keySet().removeIf(k -> k.startsWith(wallId + ":"));
    }

    /**
     * 变量变化 → 触发匹配 wall 的时间轴播放（带去抖）。调用方先按 ChangeType 过滤
     * （只在"值真的变了"——VALUE_SET / UPDATED / CREATED——时调），本方法只认 fullName。
     *
     * @param fullName 内部解析后 fullName（如 {@code user:w-1/hp} / {@code schedule:w-1/eta_seconds}）
     */
    public void onVariableChange(String fullName) {
        if (fullName == null) return;
        Set<Binding> bindings = byFullName.get(fullName);
        if (bindings == null || bindings.isEmpty()) return;
        long now = clock.getAsLong();
        for (Binding b : bindings) {
            String dk = b.wallId() + ":" + b.timelineId();
            Long last = lastFire.get(dk);
            if (last != null && now - last < DEBOUNCE_MS) {                // 去抖：窗口内重复触发吞掉
                log.fine("[timeline-trigger] variable " + fullName + " change within debounce window, skipping " + dk);
                continue;
            }
            lastFire.put(dk, now);
            log.fine("[timeline-trigger] variable " + fullName + " changed → playing timeline " + b.timelineId()
                    + " on wall " + b.wallId());
            try {
                player.play(b.wallId(), b.timelineId());
            } catch (Exception e) {
                log.warning("TimelineTriggerRegistry: play failed wall=" + b.wallId()
                        + " tl=" + b.timelineId() + " err=" + e.getMessage());
            }
        }
    }

    /**
     * 登记 wall 的 activeTimeline（若其 trigger 是 VARIABLE_CHANGE / SCHEDULE 且有 fullName）。
     * 返回是否登记了。仅由已 synchronized 的 rebuild 入口调用，自身也标 synchronized
     * （可重入，成本可忽略）保证任何调用路径都在锁内。
     */
    private synchronized boolean registerWall(String wallId, ProjectState state) {
        String activeId = state.activeTimelineId();
        if (activeId == null) return false;
        Timeline tl = findTimeline(state, activeId);
        if (tl == null) return false;
        TriggerConfig trig = tl.trigger();
        if (trig == null) return false;
        TriggerType type = trig.type();
        if (type != TriggerType.VARIABLE_CHANGE && type != TriggerType.SCHEDULE) return false;
        String raw = trig.params() == null ? null : trig.params().get(PARAM_FULL_NAME);
        if (raw == null || raw.isBlank()) return false;
        String resolved = resolver.resolve(raw, wallId);
        if (resolved == null || resolved.isBlank()) return false;
        byFullName.computeIfAbsent(resolved, k -> ConcurrentHashMap.newKeySet())
                .add(new Binding(wallId, tl.id()));
        wallKeys.computeIfAbsent(wallId, k -> ConcurrentHashMap.newKeySet()).add(resolved);
        log.fine("[timeline-trigger] bound: wall " + wallId + " timeline " + tl.id()
                + " ← watching variable " + resolved + " (trigger=" + type + ", param=" + raw + ")");
        return true;
    }

    private static Timeline findTimeline(ProjectState state, String timelineId) {
        if (state.timelines() == null) return null;
        for (Timeline t : state.timelines()) {
            if (t.id().equals(timelineId)) return t;
        }
        return null;
    }

    /** 测试观察：某解析后 fullName 当前绑定数。 */
    int bindingCount(String fullName) {
        Set<Binding> set = byFullName.get(fullName);
        return set == null ? 0 : set.size();
    }
}
