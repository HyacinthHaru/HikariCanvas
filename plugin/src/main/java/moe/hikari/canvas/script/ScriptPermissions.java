package moe.hikari.canvas.script;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 脚本权限面映射（纯静态、零 Bukkit）。契约 {@code docs/scripting.md §2.1}。
 *
 * <p>{@link #requiredFacets(ScriptRule)} 递归扫描规则，返回该规则除基础节点
 * {@link #NODE_EDIT} 之外还需要的权限节点集合（dispatcher 侧逐一查 caller 权限）。
 * {@code NODE_EDIT} 是恒查的基础节点，不进返回值。</p>
 *
 * <p>注意：{@code scanActions} 的 switch 有意不写 {@code default}、显式穷举全部
 * Action 子类——未来新增子类时编译器会强制到这里补权限面判定（编译期守门）。</p>
 */
public final class ScriptPermissions {

    /** 基础节点：编辑脚本（恒查，不进 requiredFacets 返回值）。 */
    public static final String NODE_EDIT = "canvas.script.edit";
    /**
     * 全局触发面：playerJoin / playerKill / playerQuit / rightClickWall（影响全服而非
     * wall 附近——进服 / 击杀 / 退服是全服事件，右键墙虽带墙语义但属"任意玩家可触"，
     * 同列全局面）。0.7.1 加 playerQuit / rightClickWall。
     */
    public static final String NODE_TRIGGER_GLOBAL = "canvas.script.trigger.global";
    /** 播放声音面。 */
    public static final String NODE_SOUND = "canvas.script.sound";
    /** 执行命令模板面。 */
    public static final String NODE_COMMAND = "canvas.script.command";

    private ScriptPermissions() {
    }

    /**
     * 递归扫描规则需要的附加权限面（不含基础 {@link #NODE_EDIT}）。
     */
    public static Set<String> requiredFacets(ScriptRule rule) {
        Set<String> facets = new HashSet<>();
        // playerLeaveRange 不加（墙级，同 PlayerNear，仅基础 NODE_EDIT）
        if (rule.trigger() instanceof Trigger.PlayerJoin
                || rule.trigger() instanceof Trigger.PlayerKill
                || rule.trigger() instanceof Trigger.PlayerQuit
                || rule.trigger() instanceof Trigger.RightClickWall) {
            facets.add(NODE_TRIGGER_GLOBAL);
        }
        scanActions(rule.actions(), facets);
        return facets;
    }

    /** 递归扫动作（含 if 分支内嵌套）。 */
    private static void scanActions(List<Action> actions, Set<String> facets) {
        for (Action action : actions) {
            switch (action) {
                case Action.PlaySound ignored -> facets.add(NODE_SOUND);
                case Action.RunCommand ignored -> facets.add(NODE_COMMAND);
                case Action.If iff -> {
                    scanActions(iff.then(), facets);
                    scanActions(iff.elseActions(), facets);
                }
                // 0.7.1：Repeat 递归扫 body（循环体内动作的权限面照常生效）
                case Action.Repeat rep -> scanActions(rep.body(), facets);
                // 0.7.2-P3：RepeatUntil 递归扫 body（同 Repeat）
                case Action.RepeatUntil ru -> scanActions(ru.body(), facets);
                // 以下子类无附加权限面（显式列出而非 default，保证穷尽性）
                case Action.SetVariable ignored -> { }
                case Action.IncrementVariable ignored -> { }
                case Action.SetElementProperty ignored -> { }
                case Action.PlayTimeline ignored -> { }
                case Action.Wait ignored -> { }
                case Action.Log ignored -> { }
                // 0.7.1：6 个新 Action 子类，均无附加权限面（仍走基础 canvas.script.edit）
                case Action.SetElementProperties ignored -> { }
                case Action.NudgeElement ignored -> { }
                case Action.SendMessage ignored -> { }
                case Action.SetRandomVariable ignored -> { }
                case Action.ScaleVariable ignored -> { }
                case Action.PlayTimelineAwait ignored -> { }
                // 0.7.1-P5：粒子复用声音面（喷粒子是音视效果同档）；停止 / 等待直到仅基础 edit
                case Action.PlayParticle ignored -> facets.add(NODE_SOUND);
                case Action.StopScript ignored -> { }
                case Action.WaitUntil ignored -> { }
                // 0.7.2-P2：copy / append / clone / delete 均无附加权限面（仅基础 canvas.script.edit）
                case Action.CopyVariable ignored -> { }
                case Action.AppendVariable ignored -> { }
                case Action.CloneElement ignored -> { }
                case Action.DeleteElement ignored -> { }
                // tween：递归扫 body（body 里的属性动作权限面照常生效；包裹本身无附加权限面）
                case Action.TweenBlock tb -> scanActions(tb.body(), facets);
                // 0.7.3：随机分支递归扫 then/else；其余 3 个无附加权限面
                case Action.RandomBranch rb -> {
                    scanActions(rb.then(), facets);
                    scanActions(rb.elseActions(), facets);
                }
                case Action.SetElementLayer ignored -> { }
                case Action.RoundVariable ignored -> { }
                case Action.ShowTitle ignored -> { }
            }
        }
    }
}
