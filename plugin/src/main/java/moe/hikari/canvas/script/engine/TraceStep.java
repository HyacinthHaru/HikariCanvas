package moe.hikari.canvas.script.engine;

/**
 * 单步执行轨迹（trace 基建；{@code docs/scripting.md §4.1 script.test}）。
 *
 * @param blockId 积木定位 id。{@link moe.hikari.canvas.script.ScriptRule} 没有
 *                per-action id——用<b>树路径</b>作 blockId（如 {@code "actions/0"}、
 *                {@code "actions/2/then/1"}），前端积木树同构可定位；
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
