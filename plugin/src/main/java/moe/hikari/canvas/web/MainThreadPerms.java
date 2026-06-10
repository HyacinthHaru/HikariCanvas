package moe.hikari.canvas.web;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * P2-26：WS op dispatcher 的主线程权限解析助手。
 *
 * <p><b>背景</b>：{@code Bukkit.getPlayer(UUID)}（读 join/quit 时 mutate 的在线表）与
 * {@code Player.hasPermission}（读 attachment / LuckPerms reload 时主线程重算的
 * {@code PermissibleBase}）都是主线程专用 API。各 {@code *OpDispatcher.dispatch} 跑在
 * Jetty WS worker 线程（{@link WebServer#handleMessage} 同步派发，无主线程 hop），
 * 此前直接裸调这两个 API，与 {@link WebServer#handleAuth} 已有的
 * {@code callSyncMethod(...).get(2,SECONDS)} 修复自相矛盾，并发玩家上下线 / 权限重算时
 * 可能读到不一致在线表或半构造 PermissibleBase。本助手把两步收敛到一次主线程 hop，复用
 * auth 路径同款 {@code callSyncMethod} + 2s 超时。</p>
 *
 * <p><b>语义</b>（与各 dispatcher 旧 {@code player != null && player.hasPermission(node)}
 * 等价）：</p>
 * <ul>
 *   <li>{@code callerUuid == null} / 玩家离线（{@code getPlayer == null}）→ {@code false}。
 *       离线玩家不走 hasPermission，是否最终放行由调用方的 default-true / owner 逻辑决定
 *       （调用方在本助手返回 false 后仍可对 own / 默认节点放行，与既有惯例一致）。</li>
 *   <li>在线且持节点 → {@code true}；在线但无节点 → {@code false}。</li>
 *   <li>超时 / scheduler 关停 / 中断 / callable 异常 → <b>fail-closed</b> {@code false}。
 *       与 auth 路径对 base 节点的"保守放行"不同：dispatcher 节点既含 {@code .own}（default
 *       true，调用方会另行放行）也含 {@code .any} 提权节点，对提权节点宁可误拒不可误放，
 *       故统一 fail-closed；{@code .own} 路径不受影响（调用方兜底）。</li>
 * </ul>
 *
 * <p>{@code plugin == null}（部分测试装配）时退回直接调用（保持测试可跑，MockBukkit 单线程
 * 无主线程纪律问题）。</p>
 */
final class MainThreadPerms {

    private MainThreadPerms() {}

    /** 主线程权限解析超时；与 {@link WebServer#handleAuth} 一致。 */
    private static final long TIMEOUT_SECONDS = 2L;

    /**
     * 0.7.0-P2-1：包级静态测试 seam。非 null 时 {@link #resolve} 直接走它（绕开
     * Bukkit / 主线程 hop），让权限拒绝路径可在无 MockBukkit server 的纯 JVM 单测里
     * dispatch 级驱动（在线被收回节点 → PERMISSION_DENIED + audit 全链）。
     *
     * <p><b>为何不走 MockBukkit</b>：依赖虽在（M15 引入），但全仓零 ServerMock 实跑先例
     * （既有测试均注明"不引 MockBukkit"），且 ServerMock 与 Paper 1.21.11 API 的
     * 兼容性未验证——按任务"二选一"取 seam 路线。生产路径 seam 恒 null，
     * 仅一次 volatile 读零开销；测试 @AfterEach 必须复位 null。</p>
     */
    static volatile java.util.function.BiFunction<java.util.UUID, String[], Resolved>
            testResolver;

    /**
     * 在主线程解析 {@code callerUuid} 对应在线玩家是否持 {@code node}。
     *
     * @return {@code true} = 在线且持权限；其余（离线 / 无权限 / 超时 / 异常）= {@code false}
     */
    static boolean hasPermission(Plugin plugin, java.util.UUID callerUuid, String node) {
        if (callerUuid == null) return false;
        if (plugin == null) {
            // 测试装配（MockBukkit 单线程）：直接调，无主线程纪律风险。
            Player p = Bukkit.getPlayer(callerUuid);
            return p != null && p.hasPermission(node);
        }
        try {
            Boolean granted = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                Player p = Bukkit.getPlayer(callerUuid);
                return p != null && p.hasPermission(node);
            }).get(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            return Boolean.TRUE.equals(granted);
        } catch (InterruptedException ie) {
            // 复位中断标志后 fail-closed。
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException | ExecutionException
                | RejectedExecutionException | CancellationException e) {
            // 主线程繁忙 / scheduler 关停 / callable 异常：对提权节点 fail-closed。
            plugin.getLogger().warning(
                    "main-thread permission check failed for node=" + node + "; denying: " + e);
            return false;
        }
    }

    /**
     * P2-26：一次主线程 hop 解析在线玩家 + 多个节点的授权结果。
     *
     * <p>用于一个 op 需要查多个权限节点（如 {@code template.save} 同时查
     * {@code canvas.template.save} + {@code canvas.template.bypass-limit}）的场景，
     * 避免每节点各起一次 {@code callSyncMethod}。语义与 {@link #hasPermission} 一致：
     * 离线 / 超时 / 异常 → {@code online=false} 且全部节点 {@code false}（fail-closed）。</p>
     *
     * @param nodes 待查节点；返回的 {@code granted[i]} 对应 {@code nodes[i]}
     * @return {@link Resolved}：{@code online} = 玩家是否在线；{@code granted[i]} = 是否持 {@code nodes[i]}
     */
    static Resolved resolve(Plugin plugin, java.util.UUID callerUuid, String... nodes) {
        // 0.7.0-P2-1：测试 seam 优先（生产恒 null，单次 volatile 读）
        var seam = testResolver;
        if (seam != null) return seam.apply(callerUuid, nodes);
        boolean[] denied = new boolean[nodes.length];  // 全 false（fail-closed 默认值）
        if (callerUuid == null) return new Resolved(false, denied);
        if (plugin == null) {
            // 测试装配（MockBukkit 单线程）：直接调，无主线程纪律风险。
            return resolveDirect(callerUuid, nodes);
        }
        try {
            return Bukkit.getScheduler().callSyncMethod(plugin,
                    () -> resolveDirect(callerUuid, nodes))
                    .get(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new Resolved(false, denied);
        } catch (TimeoutException | ExecutionException
                | RejectedExecutionException | CancellationException e) {
            plugin.getLogger().warning(
                    "main-thread permission resolve failed; denying all: " + e);
            return new Resolved(false, denied);
        }
    }

    /** 主线程内（或测试单线程内）直接解析在线 + 各节点。 */
    private static Resolved resolveDirect(java.util.UUID callerUuid, String[] nodes) {
        Player p = Bukkit.getPlayer(callerUuid);
        boolean online = p != null;
        boolean[] granted = new boolean[nodes.length];
        if (online) {
            for (int i = 0; i < nodes.length; i++) {
                granted[i] = p.hasPermission(nodes[i]);
            }
        }
        return new Resolved(online, granted);
    }

    /**
     * 批量权限解析结果。
     *
     * @param online  玩家是否在线（{@code Bukkit.getPlayer != null}）
     * @param granted 与传入 {@code nodes} 同序的授权布尔数组（离线时全 false）
     */
    record Resolved(boolean online, boolean[] granted) {
        boolean granted(int idx) { return granted[idx]; }
    }
}
