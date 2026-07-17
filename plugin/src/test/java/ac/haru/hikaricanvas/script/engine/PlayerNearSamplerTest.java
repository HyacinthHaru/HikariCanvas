package ac.haru.hikaricanvas.script.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 0.7.0-P3 B2 / K14：{@link PlayerNearSampler} 状态机单测（进入沿 / 离开重置 / 跨世界 /
 * 边界 / 离线清理 / 多玩家多规则独立 / 跳帧计数 + 热更）。全 fake：玩家位置源 + near
 * 规则源 + 记录型 sink，零 Bukkit。Router 全链贯通见 {@link EndToEndScriptTest} ⑨。
 */
class PlayerNearSamplerTest {

    private static final Logger LOG = Logger.getLogger("test");
    private static final UUID WORLD_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID WORLD_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    /** 进入沿触发记录（"wallId:ruleId@playerName"）。 */
    private final List<String> fired = new ArrayList<>();
    /** 可变玩家位置表（模拟移动 / 离线）。 */
    private final List<PlayerNearSampler.PlayerPos> players = new ArrayList<>();
    /** 可变 near 规则表（模拟 rebuild 增删）。 */
    private final List<TriggerRouter.NearEntry> rules = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fired.clear();
        players.clear();
        rules.clear();
    }

    private PlayerNearSampler sampler(int sampleTicks) {
        return new PlayerNearSampler(
                () -> List.copyOf(players), () -> List.copyOf(rules),
                (wallId, ruleId, playerName) -> fired.add(wallId + ":" + ruleId + "@" + playerName),
                LOG, sampleTicks);
    }

    /** sampleTicks=1：底层每次 tick（2 游戏 tick）都真采样。 */
    private PlayerNearSampler eagerSampler() {
        return sampler(1);
    }

    /** 进入沿规则（leaveEdge=false；playerNear 语义）。 */
    private void rule(String wallId, String ruleId, int range, UUID worldId,
                      double x, double y, double z) {
        rules.add(new TriggerRouter.NearEntry(wallId, ruleId, range, worldId, x, y, z, false));
    }

    /** 离开沿规则（leaveEdge=true；playerLeaveRange 语义）。 */
    private void leaveRule(String wallId, String ruleId, int range, UUID worldId,
                           double x, double y, double z) {
        rules.add(new TriggerRouter.NearEntry(wallId, ruleId, range, worldId, x, y, z, true));
    }

    private void player(String name, UUID worldId, double x, double y, double z) {
        players.removeIf(p -> p.name().equals(name));
        players.add(new PlayerNearSampler.PlayerPos(name, worldId, x, y, z));
    }

    // ── ① 进入触发恰一次 ────────────────────────────────────────────

    @Test
    void enterRange_firesExactlyOnce() {
        rule("w-1", "r-1", 8, WORLD_A, 0, 0, 0);
        player("Steve", WORLD_A, 3, 0, 0);
        PlayerNearSampler s = eagerSampler();
        s.tick();
        assertEquals(List.of("w-1:r-1@Steve"), fired, "首轮即在范围内 = 进入沿，触发一次");
    }

    // ── ② 停留不重复 ────────────────────────────────────────────────

    @Test
    void stayInside_noRefire() {
        rule("w-1", "r-1", 8, WORLD_A, 0, 0, 0);
        player("Steve", WORLD_A, 3, 0, 0);
        PlayerNearSampler s = eagerSampler();
        s.tick();
        s.tick();
        s.tick();
        assertEquals(1, fired.size(), "持续在内不重复触发");
    }

    // ── ③ 离开重置（不触发"离开事件"） ───────────────────────────────

    @Test
    void leave_resetsWithoutFiring() {
        rule("w-1", "r-1", 8, WORLD_A, 0, 0, 0);
        player("Steve", WORLD_A, 3, 0, 0);
        PlayerNearSampler s = eagerSampler();
        s.tick();
        player("Steve", WORLD_A, 100, 0, 0);
        s.tick();
        assertEquals(1, fired.size(), "离开只重置状态，不触发");
    }

    // ── ④ 再进入再触发 ──────────────────────────────────────────────

    @Test
    void reenter_firesAgain() {
        rule("w-1", "r-1", 8, WORLD_A, 0, 0, 0);
        player("Steve", WORLD_A, 3, 0, 0);
        PlayerNearSampler s = eagerSampler();
        s.tick();                                  // 进入 → 1 次
        player("Steve", WORLD_A, 100, 0, 0);
        s.tick();                                  // 离开 → 重置
        player("Steve", WORLD_A, 0, 5, 0);
        s.tick();                                  // 再进入 → 第 2 次
        assertEquals(2, fired.size());
        assertEquals("w-1:r-1@Steve", fired.get(1));
    }

    // ── ⑤ 跨世界不触发 ──────────────────────────────────────────────

    @Test
    void differentWorld_sameCoords_noFire() {
        rule("w-1", "r-1", 8, WORLD_A, 0, 0, 0);
        player("Steve", WORLD_B, 0, 0, 0);         // 坐标重合但世界不同
        PlayerNearSampler s = eagerSampler();
        s.tick();
        assertEquals(0, fired.size(), "世界 UUID 不同 → 距离再近也不触发");
        // 换到同世界 → 触发
        player("Steve", WORLD_A, 0, 0, 0);
        s.tick();
        assertEquals(1, fired.size());
    }

    // ── ⑥ 边界：距离平方恰等于 range² 含在内 ─────────────────────────

    @Test
    void boundary_exactRangeSquared_inclusive() {
        rule("w-1", "r-1", 8, WORLD_A, 0, 0, 0);
        player("Edge", WORLD_A, 8, 0, 0);          // 距离² = 64 = 8² → 在内（含等于）
        PlayerNearSampler s = eagerSampler();
        s.tick();
        assertEquals(1, fired.size(), "距离恰等于 range → 触发");
        // 略超出（8.001）→ 视为离开重置；回到边界 → 再触发
        player("Edge", WORLD_A, 8.001, 0, 0);
        s.tick();
        assertEquals(1, fired.size(), "略超 range 不触发（且重置状态）");
        player("Edge", WORLD_A, 8, 0, 0);
        s.tick();
        assertEquals(2, fired.size(), "回到恰边界 → 再次进入沿");
    }

    // ── ⑦ 离线清理：离线丢状态，重新上线在内视同首次进入 ─────────────

    @Test
    void offlinePlayer_stateCleared_refiresOnReturn() {
        rule("w-1", "r-1", 8, WORLD_A, 0, 0, 0);
        player("Steve", WORLD_A, 3, 0, 0);
        PlayerNearSampler s = eagerSampler();
        s.tick();
        assertEquals(1, fired.size());
        assertEquals(1, s.stateSize());
        players.clear();                           // 离线
        s.tick();
        assertEquals(0, s.stateSize(), "离线玩家条目被清掉，状态表不膨胀");
        player("Steve", WORLD_A, 3, 0, 0);         // 重新上线，仍在范围内
        s.tick();
        assertEquals(2, fired.size(), "重新上线在内 = 新的进入沿");
    }

    // ── ⑧ 多玩家 × 多规则独立状态 ───────────────────────────────────

    @Test
    void multiPlayersMultiRules_independentEdges() {
        rule("w-1", "r-1", 8, WORLD_A, 0, 0, 0);
        rule("w-2", "r-2", 8, WORLD_A, 100, 0, 0);
        player("Steve", WORLD_A, 0, 0, 0);         // 在 w-1 内、w-2 外
        player("Alex", WORLD_A, 100, 0, 0);        // 在 w-2 内、w-1 外
        PlayerNearSampler s = eagerSampler();
        s.tick();
        assertEquals(2, fired.size());
        assertEquals(List.of("w-1:r-1@Steve", "w-2:r-2@Alex"), fired);
        // Steve 移到 w-2：对 w-2 是新进入沿（触发），对 w-1 是离开（重置不触发）；
        // Alex 留在 w-2 不重复
        player("Steve", WORLD_A, 98, 0, 0);
        s.tick();
        assertEquals(3, fired.size());
        assertEquals("w-2:r-2@Steve", fired.get(2), "同一玩家对不同规则的状态独立");
        assertEquals(4, s.stateSize(), "状态条目 = 2 规则 × 2 玩家");
    }

    // ── ⑨ 跳帧计数 + setSampleTicks 热更 + clamp ────────────────────

    @Test
    void frameSkipCounter_andHotUpdate() {
        rule("w-1", "r-1", 8, WORLD_A, 0, 0, 0);
        player("Steve", WORLD_A, 3, 0, 0);
        PlayerNearSampler s = sampler(10);         // 10 tick = 每 5 次 tick() 真采样一轮
        s.tick();
        s.tick();
        s.tick();
        s.tick();
        assertEquals(0, fired.size(), "累计 8 tick < 10 → 前 4 次调用不采样");
        s.tick();
        assertEquals(1, fired.size(), "第 5 次调用累计 10 tick → 真采样");
        // 热更到 2 tick：每次调用都采样（离开→再进验证生效）
        s.setSampleTicks(2);
        player("Steve", WORLD_A, 100, 0, 0);
        s.tick();                                  // 离开（立即采样）
        player("Steve", WORLD_A, 3, 0, 0);
        s.tick();                                  // 再进（立即采样）
        assertEquals(2, fired.size(), "热更后无需重 schedule 即生效");
        // clamp：越界值钳到 1..200
        s.setSampleTicks(0);
        assertEquals(1, s.currentSampleTicks(), "下限 clamp 1");
        s.setSampleTicks(9999);
        assertEquals(200, s.currentSampleTicks(), "上限 clamp 200");
        assertEquals(1, sampler(-5).currentSampleTicks(), "构造期同样 clamp（-5 → 1）");
    }

    // ── ⑩ 规则被移除（rebuild）→ 状态条目随之清理 + sink 异常不毁采样轮 ──

    @Test
    void ruleRemoved_stateCleared_andSinkExceptionContained() {
        rule("w-1", "r-1", 8, WORLD_A, 0, 0, 0);
        player("Steve", WORLD_A, 3, 0, 0);
        PlayerNearSampler s = eagerSampler();
        s.tick();
        assertEquals(1, s.stateSize());
        rules.clear();                             // 规则删除 / 禁用 → 快照空
        s.tick();
        assertEquals(0, s.stateSize(), "规则移除后旧状态条目清掉");
        // sink 抛异常：本条触发吞掉，同轮其他规则照常触发 + 后续轮不受影响
        rule("w-boom", "r-x", 8, WORLD_A, 0, 0, 0);
        rule("w-2", "r-2", 8, WORLD_A, 0, 0, 0);
        PlayerNearSampler throwing = new PlayerNearSampler(
                () -> List.copyOf(players), () -> List.copyOf(rules),
                (wallId, ruleId, playerName) -> {
                    if (wallId.equals("w-boom")) throw new IllegalStateException("boom");
                    fired.add(wallId + ":" + ruleId + "@" + playerName);
                },
                LOG, 1);
        assertDoesNotThrow(throwing::tick);
        assertEquals(2, fired.size(), "异常规则不影响同轮其他规则触发");
        assertEquals("w-2:r-2@Steve", fired.get(1));
    }

    // ── 0.7.1：离开沿（leaveEdge=true / playerLeaveRange） ──────────────

    /** ⑪ 离开沿：先在范围内（不触发，离开沿不管进入），走出范围 → 触发一次。 */
    @Test
    void leaveEdge_firesOnExitNotOnEnter() {
        leaveRule("w-1", "r-1", 8, WORLD_A, 0, 0, 0);
        player("Steve", WORLD_A, 3, 0, 0);
        PlayerNearSampler s = eagerSampler();
        s.tick();                                  // 在内 → 离开沿不触发
        assertEquals(0, fired.size(), "leaveEdge 进入范围不触发");
        player("Steve", WORLD_A, 100, 0, 0);
        s.tick();                                  // 走出 → 离开沿触发一次
        assertEquals(List.of("w-1:r-1@Steve"), fired, "走出范围 = 离开沿，触发一次");
    }

    /** ⑫ 离开沿：持续在范围外不重复触发（已离开后不再每轮触发）。 */
    @Test
    void leaveEdge_stayOutside_noRefire() {
        leaveRule("w-1", "r-1", 8, WORLD_A, 0, 0, 0);
        player("Steve", WORLD_A, 3, 0, 0);
        PlayerNearSampler s = eagerSampler();
        s.tick();                                  // 在内
        player("Steve", WORLD_A, 100, 0, 0);
        s.tick();                                  // 离开 → 1 次
        s.tick();                                  // 持续在外
        s.tick();
        assertEquals(1, fired.size(), "已离开后持续在外不重复触发");
    }

    /** ⑬ 离开沿：玩家首次出现就在范围外（从未 in=true）→ 不触发。 */
    @Test
    void leaveEdge_firstSeenOutside_noFire() {
        leaveRule("w-1", "r-1", 8, WORLD_A, 0, 0, 0);
        player("Steve", WORLD_A, 100, 0, 0);       // 一上来就在范围外
        PlayerNearSampler s = eagerSampler();
        s.tick();
        assertEquals(0, fired.size(), "从未进入过范围 → 离开沿不触发");
        // 进入再离开 → 触发一次
        player("Steve", WORLD_A, 3, 0, 0);
        s.tick();                                  // 进入（leaveEdge 不触发）
        player("Steve", WORLD_A, 100, 0, 0);
        s.tick();                                  // 离开 → 触发
        assertEquals(1, fired.size());
    }

    /** ⑭ 进入沿 + 离开沿混合：同墙两规则各自按沿独立触发。 */
    @Test
    void enterAndLeaveEdges_independentOnSameWall() {
        rule("w-1", "near", 8, WORLD_A, 0, 0, 0);        // 进入沿
        leaveRule("w-1", "leave", 8, WORLD_A, 0, 0, 0);  // 离开沿
        player("Steve", WORLD_A, 3, 0, 0);
        PlayerNearSampler s = eagerSampler();
        s.tick();                                  // 进入：near 触发，leave 不触发
        assertEquals(List.of("w-1:near@Steve"), fired);
        player("Steve", WORLD_A, 100, 0, 0);
        s.tick();                                  // 离开：leave 触发，near 不触发
        assertEquals(2, fired.size());
        assertEquals("w-1:leave@Steve", fired.get(1));
    }
}
