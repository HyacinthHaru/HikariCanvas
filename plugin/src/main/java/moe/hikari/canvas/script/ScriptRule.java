package moe.hikari.canvas.script;

/**
 * 顶层规则。blockLayout = 前端积木坐标 JSON,后端不解析。契约 docs/scripting.md §2.1。
 *
 * <p>结构校验见 {@link ScriptRuleValidator}（数据模型本身只做 null 安全收敛），
 * 权限面映射见 {@link ScriptPermissions}。</p>
 */
public record ScriptRule(String id, String wallId, boolean enabled, String name,
                         Trigger trigger, java.util.List<Action> actions, String blockLayout) {
    public ScriptRule {
        actions = actions == null ? java.util.List.of() : java.util.List.copyOf(actions);
    }
}
