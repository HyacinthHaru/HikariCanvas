package ac.haru.hikaricanvas.script.engine;

/**
 * 一次触发的上下文。source 用于 trace/audit；chainDepth 用于 ABA 熔断
 * （{@code docs/scripting.md §3}）。detail 是触发方的自由描述（如变量 fullName /
 * timer 周期 / 事件玩家名），仅进 trace / 日志，不参与任何判定。
 *
 * <p>chainDepth 语义：0 = 非脚本引发（外部变量变化 / timer / wallReady / 游戏事件）；
 * 脚本动作写变量再触发下游脚本时由 TriggerRouter 读
 * {@link ScriptRunner#CHAIN_DEPTH} 得 depth+1。</p>
 *
 * <p>游戏事件三来源——PLAYER_JOIN（detail=玩家名）/
 * PLAYER_KILL（detail={@code victim→killer}）/ PLAYER_NEAR（detail=玩家名）。
 * 事件玩家不注入动作参数（{@code {player}} 变量未支持），detail
 * 只进 trace / audit。</p>
 */
public record TriggerContext(Source source, int chainDepth, String detail) {
    public enum Source { VARIABLE, TIMER, WALL_READY, TEST, PLAYER_JOIN, PLAYER_KILL, PLAYER_NEAR,
            RIGHT_CLICK_WALL, PLAYER_LEAVE_RANGE, PLAYER_QUIT }
}
