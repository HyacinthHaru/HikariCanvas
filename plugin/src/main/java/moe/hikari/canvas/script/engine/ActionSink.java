package moe.hikari.canvas.script.engine;

import moe.hikari.canvas.script.Action;

/**
 * {@link ScriptRunner} → 动作执行端的 seam（{@code ActionExecutor} 实现之；
 * 单测注 fake 记录调用）。
 *
 * <p>契约：实现<b>必须</b>三层异常隔离——任何动作失败返回 error {@link TraceStep}，
 * 不抛（Runner 侧仍有兜底 catch，但那是防御不是契约）。{@code wait} / {@code if}
 * 由 Runner 自己处理，不会进入本接口；防御性收到应返 error step。</p>
 */
public interface ActionSink {

    /**
     * 执行单个动作（在 ScriptRunner 单线程上调用）。
     *
     * @param wallId  规则归属 wall
     * @param blockId 树路径 blockId（进 trace）
     * @param action  动作（不会是 {@code Wait} / {@code If}，防御除外）
     * @return 本步轨迹；不得返回 null
     */
    TraceStep execute(String wallId, String blockId, Action action);

    /**
     * playTimelineAwait：返回时间轴一轮时长（ms），&gt;0 时 Runner 挂起续接。
     * 查无 / 不支持 → 0（不挂起）。default 0 让既有 fake 不破。
     */
    default long timelineDurationMs(String wallId, String timelineId) {
        return 0L;
    }
}
