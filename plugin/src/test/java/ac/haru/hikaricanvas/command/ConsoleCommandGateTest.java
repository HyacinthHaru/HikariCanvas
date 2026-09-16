package ac.haru.hikaricanvas.command;

import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.19：{@code canvas open / list / alias / delete / cancel} 的 sender 白名单。
 *
 * <p>白名单是 <b>Player + ConsoleCommandSender</b>，<b>命令方块必须被排除</b>。</p>
 *
 * <p>为什么不能图省事写成 {@code src.getSender().hasPermission(...)}：那样命令方块也能跑
 * {@code canvas open}，而这条命令会回显含 token 的编辑器链接 —— token 会被打进方块输出 /
 * 世界数据，门槛远低于控制台日志，而且服主完全无从察觉。控制台链接入日志是经评估接受的
 * 折衷（{@code docs/security.md §2.2}），命令方块<b>不在</b>那个折衷范围内。</p>
 *
 * <p>用 {@link Proxy} 伪造 sender：本仓的 MockBukkit 是死依赖（全仓引用皆注释），
 * 而这些接口方法极多、手写 stub 不现实。Proxy 只需答 {@code hasPermission}。</p>
 */
@DisplayName("0.9.19 控制台命令门禁")
class ConsoleCommandGateTest {

    private static final String NODE = "canvas.edit";

    @SuppressWarnings("unchecked")
    private static <T extends CommandSender> T fakeSender(Class<T> type, boolean granted) {
        return (T) Proxy.newProxyInstance(
                ConsoleCommandGateTest.class.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> granted;
                    case "isPermissionSet" -> granted;
                    case "getName" -> "fake-" + type.getSimpleName();
                    case "toString" -> "fake-" + type.getSimpleName();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> t) {
        if (!t.isPrimitive()) return null;
        if (t == boolean.class) return false;
        if (t == void.class) return null;
        if (t == double.class) return 0d;
        if (t == float.class) return 0f;
        if (t == long.class) return 0L;
        return 0;
    }

    @Test
    @DisplayName("玩家持权限 → 放行")
    void playerWithPermission() {
        assertTrue(CanvasCommand.senderAllowed(fakeSender(Player.class, true), NODE));
    }

    @Test
    @DisplayName("玩家无权限 → 拒绝")
    void playerWithoutPermission() {
        assertFalse(CanvasCommand.senderAllowed(fakeSender(Player.class, false), NODE));
    }

    @Test
    @DisplayName("控制台 → 放行（真实控制台的 hasPermission 恒 true）")
    void console() {
        assertTrue(CanvasCommand.senderAllowed(fakeSender(ConsoleCommandSender.class, true), NODE));
    }

    @Test
    @DisplayName("命令方块 → 拒绝，即便它「持有」该权限")
    void commandBlockRejectedEvenWithPermission() {
        // granted=true 是关键：这条证明拒绝来自 sender 类型白名单，而不是碰巧没权限。
        assertFalse(CanvasCommand.senderAllowed(fakeSender(BlockCommandSender.class, true), NODE));
    }

    @Test
    @DisplayName("RCON 远程控制台 → 拒绝（不在白名单内）")
    void remoteConsoleRejected() {
        // 这条不是笔误：RemoteConsoleCommandSender 不是 ConsoleCommandSender 的子类型，
        // 走的是 RCON 网络通道。含 token 的链接会经 RCON 明文回传，与本地控制台的
        // 威胁模型不同 —— 保守起见不纳入白名单。将来若要支持，是一次独立的安全评估。
        assertFalse(CanvasCommand.senderAllowed(
                fakeSender(RemoteConsoleCommandSender.class, true), NODE));
    }

    @Test
    @DisplayName("null sender → 拒绝")
    void nullSender() {
        assertFalse(CanvasCommand.senderAllowed(null, NODE));
    }
}
