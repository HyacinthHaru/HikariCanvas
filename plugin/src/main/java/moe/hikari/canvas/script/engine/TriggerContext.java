package moe.hikari.canvas.script.engine;

/**
 * 一次触发的上下文。source 用于 trace/audit；chainDepth 用于 ABA 熔断（K1，
 * {@code docs/scripting.md §3}）。detail 是触发方的自由描述（如变量 fullName /
 * timer 周期），仅进 trace / 日志，不参与任何判定。
 *
 * <p>chainDepth 语义：0 = 非脚本引发（外部变量变化 / timer / wallReady）；
 * 脚本动作写变量再触发下游脚本时由 TriggerRouter（批次 3）读
 * {@link ScriptRunner#CHAIN_DEPTH} 得 depth+1。</p>
 */
public record TriggerContext(Source source, int chainDepth, String detail) {
    public enum Source { VARIABLE, TIMER, WALL_READY, TEST }
}
