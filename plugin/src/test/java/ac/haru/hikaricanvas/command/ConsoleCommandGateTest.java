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
 * {@code canvas open / list / alias / delete / cancel} 的 sender 白名单。
 *
 * <p>白名单是 <b>Player + ConsoleCommandSender</b>，<b>命令方块必须被排除</b>。</p>
 *
 * <p>命令方块若能执行 {@code canvas open}，回显的含 token 编辑器链接会进入方块输出与世界数据，
 * 安全边界远低于控制台日志（{@code docs/security.md §2.2}）。</p>
 */
@DisplayName("控制台命令门禁")
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
