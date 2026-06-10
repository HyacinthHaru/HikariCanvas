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
 */
public final class ScriptPermissions {

    /** 基础节点：编辑脚本（恒查，不进 requiredFacets 返回值）。 */
    public static final String NODE_EDIT = "canvas.script.edit";
    /** 全局触发面：playerJoin / playerKill（影响全服而非 wall 附近）。 */
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
        if (rule.trigger() instanceof Trigger.PlayerJoin
                || rule.trigger() instanceof Trigger.PlayerKill) {
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
                default -> { }
            }
        }
    }
}
