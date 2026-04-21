package moe.hikari.canvas.session;

/**
 * 会话状态机，契约见 {@code docs/architecture.md §3.1}。
 */
public enum SessionState {
    /** 玩家进入了 selecting 状态但尚未选完两角，不占池、不挂物品框。 */
    SELECTING,
    /** `/canvas confirm` 后：池已借出、物品框已挂、token 已签发，尚未被 WS 消费。 */
    ISSUED,
    /** WS auth 成功，正在编辑。 */
    ACTIVE,
    /** 正在清理（commit 或 cancel 触发），进入不可逆路径。 */
    CLOSING
}
