package ac.haru.hikaricanvas.script.engine;

import org.jetbrains.annotations.Nullable;

/**
 * 一次触发的上下文。source 用于 trace/audit；chainDepth 用于 ABA 熔断
 * （{@code docs/scripting.md §3}）。detail 是触发方的自由描述（如变量 fullName /
 * timer 周期 / 事件玩家名），仅进 trace / 日志，<b>不参与任何判定，也不许被 parse</b>。
 *
 * <p>chainDepth 语义：0 = 非脚本引发（外部变量变化 / timer / wallReady / 游戏事件）；
 * 脚本动作写变量再触发下游脚本时由 TriggerRouter 读
 * {@link ScriptRunner#CHAIN_DEPTH} 得 depth+1。</p>
 *
 * <p><b>triggerPlayer</b>（"谁触发的"）与 detail 分开走：{@code sendMessage} /
 * {@code showTitle} 的 {@code target=trigger} 要按玩家名找人，必须有个结构化字段。
 * 早先复用 detail 当玩家名，遇到 playerKill（detail 是 {@code victim→killer} 拼接串）
 * 就永远找不到人，消息静默丢失。各触发源取值见 {@code scripting.md} 附录 A.1：
 * 进服 / 退服 / 靠近 / 离开 / 右键墙 = 事件里那名玩家；playerKill = 击杀者；
 * 变量变化 / 定时 / 墙就绪 / 试跑 = null（没有触发玩家）。</p>
 */
public record TriggerContext(Source source, int chainDepth, String detail,
                             @Nullable String triggerPlayer) {

    /** 无触发玩家的便捷构造（变量变化 / timer / wallReady / 试跑）。 */
    public TriggerContext(Source source, int chainDepth, String detail) {
        this(source, chainDepth, detail, null);
    }

    public enum Source { VARIABLE, TIMER, WALL_READY, TEST, PLAYER_JOIN, PLAYER_KILL, PLAYER_NEAR,
            RIGHT_CLICK_WALL, PLAYER_LEAVE_RANGE, PLAYER_QUIT }
}
