package moe.hikari.canvas.state;

import moe.hikari.canvas.render.DirtyRegion;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 每会话历史栈：past / future + commit / undo / redo / mark。
 *
 * <p>从原 {@code EditSession} god class 抽出（2026-05-14 重构）。原本是
 * EditSession 自己持 {@code past / future} 两个 {@link Deque} + 私有 helper；
 * 现在把状态 + 行为打包成 {@link HistoryStack}，EditSession 通过
 * 字段委托调用。</p>
 *
 * <p><b>线程安全：</b>不自带锁。调用方（{@code EditSession.synchronized(this)}）
 * 已经保证单线程访问，此处不再加锁。</p>
 *
 * <p><b>容量：</b>{@link #MAX_HISTORY} 控制 past 栈深度；超过踢掉最老
 * （对应 docs/protocol.md §5.5）。future 不设上限——每次 commit 都会清空 future。</p>
 */
final class HistoryStack {

    /** T11 历史栈上限（每会话）；超过后踢掉最老的。 */
    static final int MAX_HISTORY = 16;

    private final ProjectState state;

    /** 过去快照栈：每条记录一次成功 op 的 pre-mutation 状态；push=头、pop=头。 */
    private final Deque<ProjectSnapshot> past = new ArrayDeque<>();
    /** 未来快照栈：undo 时从 past 出的快照入此栈，redo 取用；每次新 edit 会清空。 */
    private final Deque<ProjectSnapshot> future = new ArrayDeque<>();

    HistoryStack(ProjectState state) {
        this.state = state;
    }

    /**
     * 拍当前 {@link ProjectState} 快照（不入栈，仅生成）。EditSession op 在 mutation
     * 之前调一次拿到 preSnapshot，成功后再 {@link #commitHistory} 推进。
     */
    ProjectSnapshot snapshotNow() {
        return new ProjectSnapshot(
                state.canvas(), state.layers(), state.activeLayerId(), null);
    }

    /**
     * 把 {@code preSnapshot} 推进 past 栈，超过 {@link #MAX_HISTORY} 踢掉最老一条；
     * 清空 future 栈（标准 undo 语义：新 edit 弃用 redo 分支）。
     */
    void commitHistory(ProjectSnapshot preSnapshot) {
        past.push(preSnapshot);
        while (past.size() > MAX_HISTORY) past.removeLast();
        future.clear();
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
        ProjectSnapshot restoreTo = past.pop();
        state.restore(restoreTo);
        long v = state.bumpVersion();
        return new EditSession.OpResult.OkSnapshot(v, DirtyRegion.fullCanvas(state));
    }

    /** undo 的逆操作。future 栈为空时返回错。 */
    EditSession.OpResult redo() {
        if (future.isEmpty()) {
            return new EditSession.OpResult.Error("INVALID_PAYLOAD", "nothing to redo");
        }
        ProjectSnapshot preRedo = snapshotNow();
        past.push(preRedo);
        while (past.size() > MAX_HISTORY) past.removeLast();
        ProjectSnapshot restoreTo = future.pop();
        state.restore(restoreTo);
        long v = state.bumpVersion();
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
                state.canvas(), state.layers(), state.activeLayerId(), label);
        past.push(marked);
        while (past.size() > MAX_HISTORY) past.removeLast();
        long v = state.bumpVersion();
        return new EditSession.OpResult.Ok(new StatePatch(v, List.of()), null);
    }
}
