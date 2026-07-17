package ac.haru.hikaricanvas.script.engine;

import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

/**
 * 脚本游戏事件入口（{@code docs/scripting.md §3}）：把 Bukkit
 * 事件转发给 {@link TriggerRouter} 的全局触发索引，全服脚本的进服 / 击杀事件
 * listener 集中在这一个类。
 *
 * <p><b>类本体只做转发，零逻辑</b>——索引查找 / store.find 最新规则 / enabled 判定 /
 * submit 全在 Router（可单测，见 {@code TriggerRouterTest}）。进服 / 击杀两个
 * handler 不强求单测：Bukkit event 构造重（PlayerDeathEvent 要真 Player + damage
 * source），而 handler 各自只有一行转发 + 一个 null 判，无分支逻辑可错。世界事件
 * 转发逻辑抽成包私有 {@code handleWorldLoad / handleWorldUnload}（单测直调，绕开
 * World 实例构造），见 {@code GameEventListenerHubTest}。</p>
 *
 * <p><b>MONITOR 优先级 + ignoreCancelled</b>：脚本触发是"观察"语义，不改事件结果；
 * 别的插件取消了进服 / 死亡（Paper 的 PlayerDeathEvent 可取消）就不该触发脚本。
 * PlayerJoinEvent / WorldLoadEvent 不可取消，ignoreCancelled 对它们是 no-op；
 * WorldUnloadEvent 可取消——取消的卸载不能把世界从快照表里删掉。
 * <b>唯一例外</b>：{@link #onPlayerInteractEntity}（右键墙）必须 {@code
 * ignoreCancelled=false}——HikariCanvas 自己的 {@code FrameProtectionListener}
 * 必然 cancel 所有 wall frame 右键，脚本 hub 不观察已取消事件就永远收不到右键墙
 * （见该 handler Javadoc）。</p>
 *
 * <p><b>击杀语义（scripting.md §2.2）</b>：playerKill = 玩家死亡且
 * {@code getKiller() != null}（被另一名玩家击杀）；环境死亡（摔落 / 岩浆等）
 * 不触发。两个 fire 都在主线程同步调，Router 内只做索引遍历 + submit（重活
 * 全在 runner 线程），主线程成本可忽略。</p>
 *
 * <p><b>右键墙 / 退服 handler</b>：</p>
 * <ul>
 *   <li><b>onPlayerQuit</b>（PlayerQuitEvent，不可取消）→ {@link #handlePlayerQuit}
 *       转发到 {@code router.firePlayerQuit}（全局 quit 索引）。</li>
 *   <li><b>onPlayerInteractEntity</b>（PlayerInteractEntityEvent，可取消——但本 handler 故意
 *       观察已取消事件，见下）→ 右键目标是
 *       {@link ItemFrame} 时经 {@link WallIdLookup}（生产 = {@code FrameDeployer::wallIdOf}，
 *       PDC 反查）拿 wallId，非 HikariCanvas 画框反查得 null → 跳过；命中 →
 *       {@code router.fireRightClickWall}（按墙索引）。{@link WallIdLookup} 是 Bukkit-type-free
 *       的注入 seam——{@code handleRightClickByWallId} 可纯 JVM 单测（不构造 ItemFrame），
 *       与 worldLoad/worldUnload 转发体同纪律。</li>
 * </ul>
 *
 * <p><b>世界名 → UUID 快照表维护</b>：playerNear 的墙原点解析
 * （originSource）可能在任意线程跑（WS 脚本 op 经 ScriptStore listener →
 * rebuildWall 在 Jetty 线程），不能调 {@code Bukkit.getWorld}（异步读
 * CraftServer.worlds 普通 LinkedHashMap，官方不保证线程安全）。改读装配层注入的
 * {@code ConcurrentHashMap<世界名, UUID>} 快照表：onEnable 用
 * {@code Bukkit.getWorlds()} 全量种子，之后由本类两个世界事件 handler（主线程）
 * 增量维护。WorldLoadEvent 额外回调 {@code onWorldChange}（生产 =
 * {@code TriggerRouter::rebuildAll}，主线程调便宜）——让"世界后加载的 near 规则"
 * 自动补登记，无需重保存规则或重启。</p>
 */
public final class GameEventListenerHub implements Listener {

    /**
     * ItemFrame → wallId 反查 seam（生产 = {@code FrameDeployer::wallIdOf}，PDC 读）。
     * 非 HikariCanvas 画框返 null。注入式 seam 让 {@code script.engine} 不依赖 {@code deploy}
     * 包，且右键转发体可纯 JVM 单测（不构造 ItemFrame）。
     */
    @FunctionalInterface
    public interface WallIdLookup {
        @Nullable String wallIdOf(ItemFrame frame);
    }

    private final TriggerRouter router;
    /** 世界名 → UUID 快照表（装配层持有；originSource 异步读，本类主线程写）。 */
    private final Map<String, UUID> worldUuidByName;
    /** 世界加载后的补登记回调（生产 = {@code TriggerRouter::rebuildAll}；可 null）。 */
    private final Runnable onWorldChange;
    /** 右键墙 ItemFrame 反查 wallId（生产 = {@code FrameDeployer::wallIdOf}；可 null）。 */
    private final @Nullable WallIdLookup wallIdLookup;

    public GameEventListenerHub(TriggerRouter router,
                                Map<String, UUID> worldUuidByName,
                                Runnable onWorldChange,
                                @Nullable WallIdLookup wallIdLookup) {
        this.router = router;
        this.worldUuidByName = worldUuidByName;
        this.onWorldChange = onWorldChange;
        this.wallIdLookup = wallIdLookup;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        router.firePlayerJoin(event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;   // 环境死亡不算击杀
        router.firePlayerKill(event.getEntity().getName(), killer.getName());
    }

    /** 玩家退服（PlayerQuitEvent 不可取消，ignoreCancelled 对它 no-op）。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        handlePlayerQuit(event.getPlayer().getName());
    }

    /** 包私有转发体（单测直调）：退服 → 全局 quit 索引。 */
    void handlePlayerQuit(String playerName) {
        if (router != null) router.firePlayerQuit(playerName);
    }

    /**
     * 玩家右键实体——只关心右键 {@link ItemFrame}（HikariCanvas 画框）。
     *
     * <p><b>MONITOR 但 {@code ignoreCancelled = false}（必须观察已取消事件）</b>：
     * HikariCanvas 自己的 {@code FrameProtectionListener.onPlayerInteractEntity}
     * （priority=HIGH）会对每一个 wall frame 右键 {@code setCancelled(true)}——这是
     * 防玩家旋转 / 改画框内容的保护逻辑，wall frame 在游戏内是惰性（inert）的。若本
     * handler 用 {@code ignoreCancelled=true}，右键墙事件永远被那个保护 cancel 挡掉，
     * {@code rightClickWall} 触发器永不触发（实测 bug 根因）。MONITOR 是"观察不
     * 修改"优先级，读取已被取消的事件正是其用途——脚本只 fire 不改事件结果，wall frame
     * 旋转早已被锁，观察这次右键无副作用。<br>
     * （边缘：玩家持 wand 时 {@code WandListener} 也会 cancel wall frame 右键——改成
     * 观察已取消事件后，持 wand 右键墙同样会触发脚本。判定可接受：wand 是编辑期工具，
     * 玩家正常游玩不持 wand，且本类在 {@code script.engine} 包不应耦合 wand 逻辑。）</p>
     *
     * <p><b>仅主手</b>：PlayerInteractEntityEvent 每次右键对主手 + 副手各派发一次——只认
     * {@code HAND} 避免一次右键触发脚本两次（副手事件丢弃）。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof ItemFrame frame)) return;
        handleRightClick(frame, event.getPlayer().getName());
    }

    /** 半私有转发体：ItemFrame → wallId 反查（{@link WallIdLookup}）→ 按墙 fire。 */
    void handleRightClick(ItemFrame frame, String playerName) {
        if (wallIdLookup == null || router == null) return;
        String wallId = wallIdLookup.wallIdOf(frame);   // 非 HikariCanvas 画框 → null
        handleRightClickByWallId(wallId, playerName);
    }

    /** 包私有 Bukkit-free 转发核心（单测直调）：wallId 命中则 fire；null → 跳过。 */
    void handleRightClickByWallId(@Nullable String wallId, String playerName) {
        if (router == null || wallId == null) return;   // 非 HikariCanvas frame → 不触发
        router.fireRightClickWall(wallId, playerName);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldLoad(WorldLoadEvent event) {
        handleWorldLoad(event.getWorld().getName(), event.getWorld().getUID());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        handleWorldUnload(event.getWorld().getName());
    }

    /** 包私有转发体（单测直调，绕开 World 实例构造）：登记 + 补登记回调。 */
    void handleWorldLoad(String worldName, UUID worldUid) {
        worldUuidByName.put(worldName, worldUid);
        // 后加载世界的 playerNear 规则此前因"世界未加载"被跳过登记——这里全量
        // rebuild 一次自动补上（主线程调，规则量受 per-wall 配额约束，成本可忽略）。
        if (onWorldChange != null) onWorldChange.run();
    }

    /** 包私有转发体（单测直调）：世界卸载只摘表，不触发 rebuild。 */
    void handleWorldUnload(String worldName) {
        worldUuidByName.remove(worldName);
    }
}
