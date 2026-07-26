package ac.haru.hikaricanvas.state;

import ac.haru.hikaricanvas.render.DirtyRegion;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * 每会话历史栈：past / future + commit / undo / redo / mark。
 *
 * <p>由 EditSession 字段委托调用。</p>
 *
 * <p><b>线程安全：</b>不自带锁。调用方（{@code EditSession.synchronized(this)}）
 * 已经保证单线程访问，此处不再加锁。</p>
 *
 * <p><b>容量：</b>{@link #MAX_HISTORY} 控制 past 栈深度；超过踢掉最老
 * （对应 docs/protocol.md §5.5）。工程有激活时间轴时提升到
 * {@link #MAX_HISTORY_TIMELINE}（keyframe 编辑高频，16 步会被一次拖动序列吞光）。
 * <b>future 同上限</b>——「每次 commit 都会清空 future」这条曾被当成 future 不必设限的理由，
 * 但 {@link #historyMark} 是刻意<b>不</b>清 future 的（mark 只贴标签、不开新分支），
 * 于是 {@code history.mark → undo} 循环里 past 一进一出净零、future 每轮 +1 且永不回收。
 * 每条都是 {@link ProjectSnapshot} 深拷贝（逐 Layer 重建），几百个元素的工程刷起来就是几十 MB/小时，
 * 会话不结束不释放。</p>
 *
 * <p><b>coalescing（docs/timeline.md §7.2）：</b>同
 * {@code coalesceKey} 的连续提交在 {@link #COALESCE_WINDOW_MS} 窗口内合并为一步 ——
 * 不再 push 新快照（栈顶已是这段连续编辑起点的 pre-state），undo 一次回到拖动前。
 * 任何非可合并 op / undo / redo 都会打断合并链。</p>
 */
final class HistoryStack {

    /** 历史栈上限（每会话）；超过后踢掉最老的。 */
    static final int MAX_HISTORY = 16;

    /** 有激活时间轴的工程的提升上限。 */
    static final int MAX_HISTORY_TIMELINE = 64;

    /** 同 coalesce key 连续提交的合并窗口。 */
    static final long COALESCE_WINDOW_MS = 500L;

    private final ProjectState state;

    /** 过去快照栈：每条记录一次成功 op 的 pre-mutation 状态；push=头、pop=头。 */
    private final Deque<ProjectSnapshot> past = new ArrayDeque<>();
    /** 未来快照栈：undo 时从 past 出的快照入此栈，redo 取用；每次新 edit 会清空。 */
    private final Deque<ProjectSnapshot> future = new ArrayDeque<>();

    /** coalescing 状态：上次 commit 的 key + 时刻。非可合并路径会重置 key。 */
    private String lastCoalesceKey;
    private long lastCommitAt;

    /**
     * 容量粘性解锁：会话内一旦观察到激活时间轴即升 64 并保持，不随时间轴删除回落。
     * 否则「删最后一条时间轴」的 commit 自身就会按 16 trim，瞬间丢弃最多 48 条
     * 真实历史（且历史条目本身还存着时间轴状态，undo 找回时间轴需要这些条目）。
     */
    private boolean timelineCapacityUnlocked;

    /** 时钟注入 seam（同 PushRateLimiter 范式）：测试用 setClock 替换。 */
    private LongSupplier clock = System::currentTimeMillis;

    HistoryStack(ProjectState state) {
        this.state = state;
    }

    /** 测试 seam：替换 coalescing 窗口判定用的时钟。 */
    void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    /** 当前 past 栈容量上限：有过激活时间轴的会话 → 64（粘性），否则 16。 */
    private int capacity() {
        if (state.activeTimelineId() != null) timelineCapacityUnlocked = true;
        return timelineCapacityUnlocked ? MAX_HISTORY_TIMELINE : MAX_HISTORY;
    }

    private void trim() {
        int cap = capacity();
        while (past.size() > cap) past.removeLast();
    }

    /** future 栈同样受 {@link #capacity()} 约束；超出踢掉最老的（栈底 = 最早 undo 的那步）。 */
    private void trimFuture() {
        int cap = capacity();
        while (future.size() > cap) future.removeLast();
    }

    /**
     * 拍当前 {@link ProjectState} 快照（不入栈，仅生成）。EditSession op 在 mutation
     * 之前调一次拿到 preSnapshot，成功后再 {@link #commitHistory} 推进。
     */
    ProjectSnapshot snapshotNow() {
        return new ProjectSnapshot(
                state.canvas(), state.layers(), state.activeLayerId(),
                state.timelines(), state.activeTimelineId(), state.tweenFps(), null);
    }

    /**
     * 把 {@code preSnapshot} 推进 past 栈，超过容量踢掉最老一条；
     * 清空 future 栈（标准 undo 语义：新 edit 弃用 redo 分支）。
     * 同时打断 coalescing 链（非可合并 op 之后的 keyframe 拖动从新起点合并）。
     */
    void commitHistory(ProjectSnapshot preSnapshot) {
        past.push(preSnapshot);
        trim();
        future.clear();
        lastCoalesceKey = null;
    }

    /**
     * 可合并提交：与上次提交同 {@code coalesceKey} 且在
     * {@link #COALESCE_WINDOW_MS} 窗口内 → 不 push（栈顶保持这段连续编辑起点的
     * pre-state），仅失效 redo 分支；否则按 {@link #commitHistory} 常规推进并记录 key。
     *
     * <p>调用方：keyframe.update / keyframe.move（连续拖动高频小改）。
     * key 形态 {@code elementId:keyframeId:property}（timeline.md §7.2）。</p>
     */
    void commitHistoryCoalesced(ProjectSnapshot preSnapshot, String coalesceKey) {
        long now = clock.getAsLong();
        if (coalesceKey != null && coalesceKey.equals(lastCoalesceKey)
                && now >= lastCommitAt && now - lastCommitAt <= COALESCE_WINDOW_MS && !past.isEmpty()) {
            future.clear();
            lastCommitAt = now;
            return;
        }
        past.push(preSnapshot);
        trim();
        future.clear();
        lastCoalesceKey = coalesceKey;
        lastCommitAt = now;
    }

    /** 测试观察：past 栈当前深度。 */
    int pastDepth() {
        return past.size();
    }

    /** 测试观察：future 栈当前深度。 */
    int futureDepth() {
        return future.size();
    }

    /**
     * 撤销到最近一次成功 op 之前的状态。past 栈为空时返回错。
     * 恢复后下行 {@code state.snapshot}（跳变无法用 patch 简洁表达），像素层面全画布重绘。
     */
    EditSession.OpResult undo() {
        if (past.isEmpty()) {
            return new EditSession.OpResult.Error("INVALID_PAYLOAD", "nothing to undo");
        }
        future.push(snapshotNow());
        trimFuture();
        ProjectSnapshot restoreTo = past.pop();
        state.restore(restoreTo);
        long v = state.bumpVersion();
        lastCoalesceKey = null;
        return new EditSession.OpResult.OkSnapshot(v, DirtyRegion.fullCanvas(state));
    }

    /** undo 的逆操作。future 栈为空时返回错。 */
    EditSession.OpResult redo() {
        if (future.isEmpty()) {
            return new EditSession.OpResult.Error("INVALID_PAYLOAD", "nothing to redo");
        }
        ProjectSnapshot preRedo = snapshotNow();
        past.push(preRedo);
        trim();
        ProjectSnapshot restoreTo = future.pop();
        state.restore(restoreTo);
        long v = state.bumpVersion();
        lastCoalesceKey = null;
        return new EditSession.OpResult.OkSnapshot(v, DirtyRegion.fullCanvas(state));
    }

    /**
     * 在 past 栈顶加一个命名检查点（{@code docs/protocol.md §5.5}）。
     * <b>不</b>清空 future——mark 只给当前点贴标签，不创建新 edit 分支。
     */
    EditSession.OpResult historyMark(String label) {
        if (label == null || label.isEmpty()) {
            return new EditSession.OpResult.Error("INVALID_PAYLOAD", "label required");
        }
        if (label.length() > 64) {
            return new EditSession.OpResult.Error("INVALID_PAYLOAD", "label too long (max 64)");
        }
        ProjectSnapshot marked = new ProjectSnapshot(
                state.canvas(), state.layers(), state.activeLayerId(),
                state.timelines(), state.activeTimelineId(), state.tweenFps(), label);
        past.push(marked);
        trim();
        lastCoalesceKey = null;
        long v = state.bumpVersion();
        return new EditSession.OpResult.Ok(new StatePatch(v, List.of()), null);
    }
}
