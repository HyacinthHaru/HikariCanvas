package ac.haru.hikaricanvas.state;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 0.6 P1 / D7：{@link HistoryStack} coalescing + 容量提升的单测。契约见
 * {@code docs/timeline.md §7}（撤销 / 历史）与 {@code docs/protocol.md §5.5}。
 *
 * <p>同包访问 package-private {@link HistoryStack}（{@code setClock} / {@code pastDepth} /
 * {@code futureDepth} / {@code commitHistory*}）。时钟用可变 {@code long[]} 自控，
 * 不依赖墙钟，coalescing 窗口判定确定性可测。</p>
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>同 key 窗口内（≤500ms）连续 coalesced commit 不 push（pastDepth 保持）</li>
 *   <li>key 变化 / 时钟越窗 / key=null → 各自 push</li>
 *   <li>coalesced commit 清 future（弃 redo 分支）</li>
 *   <li>undo / 非合并 commitHistory 打断合并链</li>
 *   <li>容量：无激活时间轴 16、有激活时间轴提升到 64（D7）</li>
 * </ul>
 */
class HistoryStackCoalesceTest {

    /** coalesce key 形态 {@code elementId:keyframeId:property}（timeline.md §7.2）。 */
    private static final String KEY_A = "e-1:k-1:opacity";
    private static final String KEY_B = "e-1:k-2:opacity";

    private static ProjectState newState() {
        return new ProjectState(1, 1);
    }

    /** 给 state 装一条激活时间轴 —— 触发 64 容量分支（capacity 依赖 activeTimelineId）。 */
    private static void attachActiveTimeline(ProjectState state) {
        state.addTimeline(new Timeline("tl-1", "anim", 1000, 20,
                LoopMode.LOOP, TriggerConfig.MANUAL, Map.of()));
        state.activeTimelineId("tl-1");
    }

    // ---------- coalescing 合并窗口 ----------

    @Test
    void sameKeyWithinWindowCoalesces() {
        ProjectState state = newState();
        HistoryStack history = new HistoryStack(state);
        long[] clock = {1000L};
        history.setClock(() -> clock[0]);

        // 首次 coalesced commit 总 push（lastCoalesceKey 初始为 null）
        history.commitHistoryCoalesced(history.snapshotNow(), KEY_A);
        assertEquals(1, history.pastDepth());

        // 同 key + 窗口内（+100ms / +400ms ≤ 500ms）→ 不 push
        clock[0] = 1100L;
        history.commitHistoryCoalesced(history.snapshotNow(), KEY_A);
        clock[0] = 1400L;
        history.commitHistoryCoalesced(history.snapshotNow(), KEY_A);
        assertEquals(1, history.pastDepth(), "同 key 窗口内连续提交应合并为一步");
    }

    @Test
    void keyChangePushesNewEntry() {
        ProjectState state = newState();
        HistoryStack history = new HistoryStack(state);
        long[] clock = {1000L};
        history.setClock(() -> clock[0]);

        history.commitHistoryCoalesced(history.snapshotNow(), KEY_A);
        clock[0] = 1100L;
        history.commitHistoryCoalesced(history.snapshotNow(), KEY_B);
        assertEquals(2, history.pastDepth(), "key 变化应打断合并、push 新条目");
    }

    @Test
    void clockBeyondWindowPushesNewEntry() {
        ProjectState state = newState();
        HistoryStack history = new HistoryStack(state);
        long[] clock = {1000L};
        history.setClock(() -> clock[0]);

        history.commitHistoryCoalesced(history.snapshotNow(), KEY_A);
        clock[0] = 1501L; // 越过 500ms 窗口
        history.commitHistoryCoalesced(history.snapshotNow(), KEY_A);
        assertEquals(2, history.pastDepth(), "时钟越窗（>500ms）应 push 新条目");
    }

    @Test
    void clockRegressionPushesNewEntry() {
        ProjectState state = newState();
        HistoryStack history = new HistoryStack(state);
        long[] clock = {1000L};
        history.setClock(() -> clock[0]);

        history.commitHistoryCoalesced(history.snapshotNow(), KEY_A);
        clock[0] = 800L; // 时钟回拨：now < lastCommitAt，差值为负
        history.commitHistoryCoalesced(history.snapshotNow(), KEY_A);
        assertEquals(2, history.pastDepth(),
                "时钟回退（now<lastCommitAt）应 push 新条目，不得因负差值误合并");
    }

    @Test
    void nullKeyAlwaysPushes() {
        ProjectState state = newState();
        HistoryStack history = new HistoryStack(state);
        long[] clock = {1000L};
        history.setClock(() -> clock[0]);

        history.commitHistoryCoalesced(history.snapshotNow(), null);
        history.commitHistoryCoalesced(history.snapshotNow(), null);
        history.commitHistoryCoalesced(history.snapshotNow(), null);
        assertEquals(3, history.pastDepth(), "key=null 每次都 push（不可合并）");
    }

    // ---------- future 清理 ----------

    @Test
    void coalescedCommitClearsFuture() {
        ProjectState state = newState();
        HistoryStack history = new HistoryStack(state);
        long[] clock = {1000L};
        history.setClock(() -> clock[0]);

        // 先 commit + undo 制造 future
        history.commitHistory(history.snapshotNow());
        history.undo();
        assertEquals(1, history.futureDepth(), "undo 后应有一条 future");

        // 再 coalesced commit → future 清空
        clock[0] = 2000L;
        history.commitHistoryCoalesced(history.snapshotNow(), KEY_A);
        assertEquals(0, history.futureDepth(), "coalesced commit 应弃 redo 分支");
    }

    // ---------- 合并链打断 ----------

    @Test
    void undoBreaksCoalesceChain() {
        ProjectState state = newState();
        HistoryStack history = new HistoryStack(state);
        long[] clock = {1000L};
        history.setClock(() -> clock[0]);

        history.commitHistoryCoalesced(history.snapshotNow(), KEY_A);
        assertEquals(1, history.pastDepth());

        // undo 打断合并链（清 lastCoalesceKey）；undo 自身把栈顶移入 future
        history.undo();
        assertEquals(0, history.pastDepth());

        // 随后同 key coalesced → push 新条目（链已断）
        clock[0] = 1100L;
        history.commitHistoryCoalesced(history.snapshotNow(), KEY_A);
        assertEquals(1, history.pastDepth(), "undo 后同 key 应作为新条目 push");
    }

    @Test
    void plainCommitBreaksCoalesceChain() {
        ProjectState state = newState();
        HistoryStack history = new HistoryStack(state);
        long[] clock = {1000L};
        history.setClock(() -> clock[0]);

        history.commitHistoryCoalesced(history.snapshotNow(), KEY_A);
        // 非合并 commitHistory 重置 lastCoalesceKey
        history.commitHistory(history.snapshotNow());
        assertEquals(2, history.pastDepth());

        // 随后同 key coalesced → push（链被非合并提交打断）
        clock[0] = 1100L;
        history.commitHistoryCoalesced(history.snapshotNow(), KEY_A);
        assertEquals(3, history.pastDepth(), "非合并 commit 后同 key 应作为新条目 push");
    }

    // ---------- 容量 ----------

    @Test
    void capacityIs16WithoutActiveTimeline() {
        ProjectState state = newState();
        HistoryStack history = new HistoryStack(state);

        for (int i = 0; i < 20; i++) {
            history.commitHistory(history.snapshotNow());
        }
        assertEquals(HistoryStack.MAX_HISTORY, history.pastDepth(),
                "无激活时间轴 → 容量 16，超出踢掉最老");
    }

    @Test
    void capacityIs64WithActiveTimeline() {
        ProjectState state = newState();
        attachActiveTimeline(state);
        HistoryStack history = new HistoryStack(state);

        for (int i = 0; i < 70; i++) {
            history.commitHistory(history.snapshotNow());
        }
        assertEquals(HistoryStack.MAX_HISTORY_TIMELINE, history.pastDepth(),
                "有激活时间轴 → 容量提升到 64（D7）");
    }

    /**
     * P1 审查确认项：容量提升是会话内粘性的——删除（最后一条）时间轴、activeTimelineId
     * 回 null 后容量保持 64，已积累的历史不被当场 trim 掉。
     */
    @Test
    void capacityStays64AfterTimelineRemoved() {
        ProjectState state = newState();
        attachActiveTimeline(state);
        HistoryStack history = new HistoryStack(state);

        // 解锁 64 容量并积累 40 条历史
        for (int i = 0; i < 40; i++) {
            history.commitHistory(history.snapshotNow());
        }
        assertEquals(40, history.pastDepth());

        // 模拟删除最后一条时间轴：active 置 null 后再 commit（删除操作本身也 commit 一次）
        state.activeTimelineId(null);
        state.removeTimelineAt(0);
        history.commitHistory(history.snapshotNow());

        assertEquals(41, history.pastDepth(),
                "容量粘性：时间轴删除后仍按 64 容量，不得把 40 条历史 trim 到 16");
    }
}
