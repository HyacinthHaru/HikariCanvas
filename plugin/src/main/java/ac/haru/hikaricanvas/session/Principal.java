package ac.haru.hikaricanvas.session;

/**
 * 编辑会话的<b>主体类型</b>——授权判定的唯一依据。
 *
 * <p><b>为什么需要这个类型，而不是"给控制台合成一个 UUID 就行"</b>：整条鉴权链的输入原本是
 * {@code UUID}，而"控制台"不是一个 UUID 能表达的主体。合成一个假 UUID 塞进去，会让全链继续
 * 以为自己在跟玩家打交道：</p>
 * <ul>
 *   <li>{@code Bukkit.getPlayer(假UUID)} 恒 {@code null} → 被当成<b>离线玩家</b>
 *       → {@code default: false} 的节点（{@code canvas.admin.*} / {@code delete.any} /
 *       {@code script.broadcast} / {@code template.use-others} / {@code canvas.upload}）一律拒；</li>
 *   <li>{@code wall.ownerUuid().equals(playerUuid)} 恒 false → {@code wall.lock} /
 *       {@code unlock} / {@code alias} 全部 {@code FORBIDDEN}。</li>
 * </ul>
 *
 * <p>于是控制台——服务器上<b>权力最大</b>的主体——会被判成权力最小的那个。这不是能靠若干处
 * 特判修好的问题：每新增一个权限节点都要记得再补一次，而漏补时的表现与正常拒绝完全一样、
 * 不会有人发现。</p>
 *
 * <p><b>纪律</b>：授权判断必须读本类型，<b>不得</b>从 {@link Session#playerUuid()} 反推主体。
 * 控制台会话携带的 {@link Session#CONSOLE_UUID} 只是三索引结构里的键，不是身份凭据。
 * 任何 {@code if (uuid.equals(CONSOLE_UUID))} 形态的鉴权都是退化——
 * {@code PrincipalAuthorityTest} 有一条专门守卫盯着它。</p>
 *
 * <p>契约见 {@code docs/architecture.md §3.6.3} + {@code docs/security.md §5.0}。</p>
 */
public enum Principal {

    /** 玩家发起的会话。逐节点查 {@code Bukkit.getPlayer(uuid).hasPermission(...)}。 */
    PLAYER,

    /**
     * 服务端控制台发起的会话。<b>全授予，不查任何权限节点</b>——与 Bukkit 对
     * {@code ConsoleCommandSender} 的既有语义一致（控制台本就持全部权限）。
     */
    CONSOLE;

    public boolean isConsole() {
        return this == CONSOLE;
    }
}
