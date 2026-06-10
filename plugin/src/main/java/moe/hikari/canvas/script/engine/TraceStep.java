package moe.hikari.canvas.script.engine;

/**
 * 单步执行轨迹（K10 trace 基建；{@code docs/scripting.md §4.1 script.test}）。
 *
 * <p>P2 只进 FINE log 一行 summary；P3 接 {@code script.test} ack 返回完整轨迹。</p>
 *
 * @param blockId 积木定位 id。P1 的 {@link moe.hikari.canvas.script.ScriptRule} 没有
 *                per-action id——用<b>树路径</b>作 blockId（如 {@code "actions/0"}、
 *                {@code "actions/2/then/1"}），P3 前端积木树同构可定位；
 *                触发步固定 {@code "trigger"}
 * @param kind    {@code trigger | condition | action}
 * @param result  {@code ok | skipped | blocked | error}
 * @param detail  人读细节（错误原因 / 条件求值结果 / blocked 理由等），可 null
 */
public record TraceStep(String blockId, String kind, String result, String detail) {

    public static TraceStep ok(String blockId, String kind, String detail) {
        return new TraceStep(blockId, kind, "ok", detail);
    }

    public static TraceStep error(String blockId, String detail) {
        return new TraceStep(blockId, "action", "error", detail);
    }

    public static TraceStep blocked(String blockId, String detail) {
        return new TraceStep(blockId, "action", "blocked", detail);
    }
}
