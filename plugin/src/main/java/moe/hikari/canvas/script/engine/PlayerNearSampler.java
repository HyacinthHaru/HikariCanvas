package moe.hikari.canvas.script.engine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * playerNear 触发器周期采样器（0.7.0-P3 B2 / K14；{@code docs/scripting.md §2 / §3}）。
 *
 * <p><b>本体零 Bukkit</b>：在线玩家位置 / near 规则条目 / 触发投递全部走注入 seam——
 * 装配层（HikariCanvas onEnable）用 lambda 包 {@code Bukkit.getOnlinePlayers()} +
 * {@code TriggerRouter::nearRules} + {@code TriggerRouter::firePlayerNear}，
 * 本类可纯 JVM 单测（照 {@code AnimationTicker.WallSource} 纪律）。</p>
 *
 * <p><b>调度形态</b>：生产挂 {@code Bukkit.getScheduler().runTaskTimer(plugin, sampler::tick,
 * 20L, 2L)}——主线程（读玩家位置必须）、固定 {@value #TASK_PERIOD_TICKS} tick 底层周期；
 * {@link #tick()} 内部按 {@link #sampleTicks}（volatile，{@code /canvas reload} 热更走
 * {@link #setSampleTicks}，无需重 schedule）做<b>跳帧计数</b>：累计游戏 tick 数到达设定值
 * 才真采样，其余调用 O(1) 直接返回。实际采样分辨率因此受底层 2 tick 周期限制
 * （sampleTicks=1 与 2 等效）。</p>
 *
 * <p><b>边沿状态机（edge-trigger）</b>：状态 key = {@code playerName + "|" + wallId + ":"
 * + ruleId}。同世界且距离平方 ≤ range² 视为"在内"。每个 {@link TriggerRouter.NearEntry}
 * 按 {@code leaveEdge} 分沿（0.7.1）：</p>
 * <ul>
 *   <li><b>{@code leaveEdge=false}（playerNear）</b>：<b>从不在内变为在内的那一轮</b>触发
 *       （进入范围一次），持续在内不重复，离开后重置（再进再触发）。</li>
 *   <li><b>{@code leaveEdge=true}（playerLeaveRange）</b>：<b>从在内变为不在内的那一轮</b>
 *       触发（离开范围一次）。离开沿要求该 (玩家,规则) 此前确曾被记录 in=true——首次出现在
 *       范围外不触发。</li>
 * </ul>
 * <p>每轮采样用"本轮观察到的 (规则 × 玩家) 全集"<b>整体替换</b>状态表——离线玩家与已删 /
 * 已禁用规则（不再出现在 {@code nearRules()} 快照里）的旧条目随之自然清掉，状态表不会
 * 无界膨胀；玩家离线后重新上线视同首次进入（playerNear 在范围内即触发一次；playerLeaveRange
 * 因丢失了 in=true 记录，下次走出范围才触发）。</p>
 *
 * <p><b>主线程成本</b>：每轮 O(规则数 × 在线玩家数) 次距离平方比较（零开方 / 零查库——
 * 墙原点已在 {@code TriggerRouter} rebuild 期解析进 {@link TriggerRouter.NearEntry}）；
 * 规则量受 per-wall 配额约束。无 near 规则或无玩家时一轮就是两次 supplier 调用。
 * 压测预算进 P6（{@code docs/scripting.md §7}）。</p>
 */
public final class PlayerNearSampler {

    /** 底层 Bukkit task 周期（tick）；{@link #tick()} 每次调用代表流逝这么多游戏 tick。 */
    static final int TASK_PERIOD_TICKS = 2;

    /** 采样间隔 clamp 下限（tick）。 */
    static final int MIN_SAMPLE_TICKS = 1;
    /** 采样间隔 clamp 上限（tick；10s——再迟钝就失去"靠近"语义）。 */
    static final int MAX_SAMPLE_TICKS = 200;

    /** 在线玩家位置快照（装配层主线程 lambda 读 Bukkit；name 唯一）。 */
    public record PlayerPos(String name, UUID worldId, double x, double y, double z) {}

    /** 进入沿触发回调 seam：生产 = {@code TriggerRouter::firePlayerNear}。 */
    @FunctionalInterface
    public interface NearFireSink {
        void fire(String wallId, String ruleId, String playerName);
    }

    private final Supplier<List<PlayerPos>> players;
    private final Supplier<List<TriggerRouter.NearEntry>> nearRules;
    private final NearFireSink sink;
    private final Logger log;

    /** 采样间隔（游戏 tick；volatile 热更，{@link #setSampleTicks}）。 */
    private volatile int sampleTicks;
    /** 距上次真采样累计流逝的游戏 tick（仅主线程 task 触碰，无需同步）。 */
    private int elapsedTicks;
    /** 状态表：key = {@code playerName|wallId:ruleId} → 上轮是否在范围内（仅主线程触碰）。 */
    private Map<String, Boolean> inside = new HashMap<>();

    public PlayerNearSampler(Supplier<List<PlayerPos>> players,
                             Supplier<List<TriggerRouter.NearEntry>> nearRules,
                             NearFireSink sink, Logger log, int sampleTicks) {
        this.players = players;
        this.nearRules = nearRules;
        this.sink = sink;
        this.log = log;
        this.sampleTicks = clamp(sampleTicks);
    }

    /** 采样间隔热更（clamp {@value #MIN_SAMPLE_TICKS}..{@value #MAX_SAMPLE_TICKS}；{@code /canvas reload}）。 */
    public void setSampleTicks(int ticks) {
        this.sampleTicks = clamp(ticks);
    }

    /** 当前采样间隔（测试观察）。 */
    int currentSampleTicks() {
        return sampleTicks;
    }

    /**
     * 周期入口（主线程 task，每 {@value #TASK_PERIOD_TICKS} tick 一次）。跳帧计数到
     * {@link #sampleTicks} 才真采样；异常自吞（runTaskTimer 任务抛异常会刷屏 + 本轮中断，
     * 不能让一次坏采样毁掉后续周期）。
     */
    public void tick() {
        elapsedTicks += TASK_PERIOD_TICKS;
        if (elapsedTicks < sampleTicks) return;
        elapsedTicks = 0;
        try {
            sample();
        } catch (Throwable t) {
            log.log(Level.WARNING, "PlayerNearSampler: sample tick error err=" + t.getMessage(), t);
        }
    }

    /** 真采样：规则 × 玩家全交叉 + 进入沿判定 + 状态表整体替换（见类注释）。 */
    private void sample() {
        List<TriggerRouter.NearEntry> rules = nearRules.get();
        if (rules == null || rules.isEmpty()) {
            if (!inside.isEmpty()) inside = new HashMap<>();
            return;
        }
        List<PlayerPos> online = players.get();
        if (online == null || online.isEmpty()) {
            if (!inside.isEmpty()) inside = new HashMap<>();
            return;
        }
        Map<String, Boolean> next = new HashMap<>();
        for (TriggerRouter.NearEntry rule : rules) {
            long rangeSq = (long) rule.rangeBlocks() * rule.rangeBlocks();
            for (PlayerPos p : online) {
                boolean in = false;
                if (rule.worldId().equals(p.worldId())) {
                    double dx = p.x() - rule.x();
                    double dy = p.y() - rule.y();
                    double dz = p.z() - rule.z();
                    in = dx * dx + dy * dy + dz * dz <= rangeSq;   // 边界含等于
                }
                String key = p.name() + "|" + rule.wallId() + ":" + rule.ruleId();
                Boolean wasIn = inside.get(key);
                boolean enter = in && !Boolean.TRUE.equals(wasIn);
                // 离开沿要求上轮确曾记录 in=true；首次出现在范围外（wasIn=null）不触发
                // leave（Boolean.TRUE.equals(null)=false，已正确）。
                boolean leave = !in && Boolean.TRUE.equals(wasIn);
                // playerNear（leaveEdge=false）只在进入沿触发；
                // playerLeaveRange（leaveEdge=true）只在离开沿触发。
                if ((!rule.leaveEdge() && enter) || (rule.leaveEdge() && leave)) {
                    try {
                        sink.fire(rule.wallId(), rule.ruleId(), p.name());
                    } catch (RuntimeException e) {
                        log.log(Level.WARNING, "PlayerNearSampler: trigger dispatch error wall="
                                + rule.wallId() + " rule=" + rule.ruleId()
                                + " err=" + e.getMessage(), e);
                    }
                }
                next.put(key, in);
            }
        }
        // 整体替换：离线玩家 / 已移除规则的旧条目自然清掉
        inside = next;
    }

    /** 状态表条目数（测试观察：离线清理 / 规则移除清理）。 */
    int stateSize() {
        return inside.size();
    }

    private static int clamp(int ticks) {
        return Math.min(MAX_SAMPLE_TICKS, Math.max(MIN_SAMPLE_TICKS, ticks));
    }
}
