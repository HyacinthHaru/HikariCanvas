package ac.haru.hikaricanvas.command;

import ac.haru.hikaricanvas.i18n.Messages;
import ac.haru.hikaricanvas.pool.MapPool;
import ac.haru.hikaricanvas.session.WallKey;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.storage.WallRepo;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.2 Task 2：{@link DiagnosticsSubCommand#diagnose} 诊断链纯逻辑单测。
 *
 * <p>不引 MockBukkit / SQLite —— 用 {@link java.lang.reflect.Proxy} 造 CommandSender 捕获消息
 * （照 {@link VariableSubCommandTest#makeSender}），用窄构造器 {@code new DiagnosticsSubCommand(messages)}
 * 绕开 10 个 Bukkit 耦合子系统，诊断输入用 {@link DiagnosticsSubCommand.DiagnoseInputs} 直接造。</p>
 *
 * <p>覆盖：not-found（行不存在）+ 正常墙全 OK + 世界未加载（worldLoaded 返 false）+
 * 工程数据损坏（loadById 空但裸行存在）+ 地图数量对不上 + 活跃 session / 动画态 INFO。</p>
 */
class DiagnosticsSubCommandTest {

    private DiagnosticsSubCommand cmd;
    private Messages messages;
    private List<String> captured;
    private CommandSender sender;

    @BeforeEach
    void setUp() {
        messages = new Messages(Logger.getLogger("diag-test"));
        messages.loadBuiltIn();
        cmd = new DiagnosticsSubCommand(messages);
        captured = new ArrayList<>();
        sender = VariableSubCommandTest.makeSender(true, captured);
    }

    private String out() {
        return String.join("\n", captured);
    }

    /** 造一块"健康"墙：mapIds 数量与 width×height 一致，world=test_world。 */
    private static WallRepo.Wall healthyWall(String wallId, int w, int h) {
        WallKey key = new WallKey("test_world", 10, 64, 10, BlockFace.NORTH);
        ProjectState ps = new ProjectState(w, h);
        List<Integer> mapIds = new ArrayList<>();
        for (int i = 0; i < w * h; i++) mapIds.add(1000 + i);
        return new WallRepo.Wall(wallId, key, ps, mapIds, w, h,
                UUID.randomUUID(), "Owner", null, null,
                System.currentTimeMillis(), System.currentTimeMillis());
    }

    private static final Predicate<String> WORLD_LOADED = name -> true;
    private static final Predicate<String> WORLD_UNLOADED = name -> false;
    private static final MapPool.Stats POOL = new MapPool.Stats(64, 50, 14);

    // ── 1. not-found：墙不存在（裸行也不存在）──
    @Test
    void diagnose_notFound_stopsWithError() {
        DiagnosticsSubCommand.DiagnoseInputs in = new DiagnosticsSubCommand.DiagnoseInputs(
                Optional.empty(), /*rawRowExists*/ false, WORLD_LOADED,
                /*sessionActive*/ false, /*animating*/ false, POOL);
        cmd.diagnose(sender, "w-deadbeef", in);

        String o = out();
        assertTrue(o.contains("w-deadbeef"), "header should echo wallId");
        assertTrue(o.contains("Wall not found"), "should report not-found: " + o);
        // 停在第 1 环节 —— 不应再出现后续环节文案
        assertFalse(o.contains("Maps assigned"), "must stop after not-found");
        assertFalse(o.contains("World loaded"), "must stop after not-found");
        assertFalse(o.contains("All checks passed"), "must not summarise OK");
    }

    // ── 2. 正常墙全 OK ──
    @Test
    void diagnose_healthyWall_allOk() {
        DiagnosticsSubCommand.DiagnoseInputs in = new DiagnosticsSubCommand.DiagnoseInputs(
                Optional.of(healthyWall("w-abc12345", 2, 2)), /*rawRowExists*/ true,
                WORLD_LOADED, /*sessionActive*/ false, /*animating*/ false, POOL);
        cmd.diagnose(sender, "w-abc12345", in);

        String o = out();
        assertTrue(o.contains("Maps assigned: 4/4"), "maps-ok: " + o);
        assertTrue(o.contains("World loaded"), "world-loaded: " + o);
        assertTrue(o.contains("Editor session: none"), "session-none INFO: " + o);
        assertTrue(o.contains("Project data: parsed OK"), "state-ok: " + o);
        assertTrue(o.contains("Animation: static"), "anim-static INFO: " + o);
        assertTrue(o.contains("All checks passed"), "summary-ok: " + o);
        // 无任何 ERROR/WARN 文案
        assertFalse(o.contains("not loaded"), o);
        assertFalse(o.contains("corrupt"), o);
        assertFalse(o.contains("mismatch"), o);
    }

    // ── 3. 世界未加载 → ERROR + summary 指向 world ──
    @Test
    void diagnose_worldUnloaded_reportsError() {
        DiagnosticsSubCommand.DiagnoseInputs in = new DiagnosticsSubCommand.DiagnoseInputs(
                Optional.of(healthyWall("w-world000", 1, 1)), /*rawRowExists*/ true,
                WORLD_UNLOADED, /*sessionActive*/ false, /*animating*/ false, POOL);
        cmd.diagnose(sender, "w-world000", in);

        String o = out();
        assertTrue(o.contains("World not loaded"), "world-unloaded ERROR: " + o);
        assertTrue(o.contains("test_world"), "world name injected: " + o);
        // 世界未加载是首个问题 → summary-issues 指向它，不是 summary-ok
        assertFalse(o.contains("All checks passed"), "must not summarise OK: " + o);
        assertTrue(o.contains("Issues found"), "summary-issues: " + o);
    }

    // ── 4. 工程数据损坏：loadById 空但裸行存在 ──
    @Test
    void diagnose_stateCorrupt_whenRawRowExistsButLoadEmpty() {
        DiagnosticsSubCommand.DiagnoseInputs in = new DiagnosticsSubCommand.DiagnoseInputs(
                Optional.empty(), /*rawRowExists*/ true, WORLD_LOADED,
                false, false, POOL);
        cmd.diagnose(sender, "w-corrupt0", in);

        String o = out();
        assertTrue(o.contains("Project data: corrupt"), "state-corrupt: " + o);
        assertFalse(o.contains("Wall not found"), "row exists → not not-found: " + o);
        // 损坏即停，不跑后续环节
        assertFalse(o.contains("World loaded"), "must stop after corrupt: " + o);
    }

    // ── 5. 地图数量对不上 → WARN ──
    @Test
    void diagnose_mapsMismatch_reportsWarn() {
        WallKey key = new WallKey("test_world", 0, 64, 0, BlockFace.SOUTH);
        ProjectState ps = new ProjectState(3, 2);  // expected 6 maps
        List<Integer> mapIds = new ArrayList<>(List.of(1, 2, 3));  // only 3 assigned
        WallRepo.Wall wall = new WallRepo.Wall("w-maps0000", key, ps, mapIds, 3, 2,
                UUID.randomUUID(), "Owner", null, null,
                System.currentTimeMillis(), System.currentTimeMillis());
        DiagnosticsSubCommand.DiagnoseInputs in = new DiagnosticsSubCommand.DiagnoseInputs(
                Optional.of(wall), true, WORLD_LOADED, false, false, POOL);
        cmd.diagnose(sender, "w-maps0000", in);

        String o = out();
        assertTrue(o.contains("Maps mismatch"), "maps-missing WARN: " + o);
        assertTrue(o.contains("3") && o.contains("6"), "assigned/expected counts: " + o);
        // 后续环节仍跑（world / session / state / anim），只是总结指向首个问题
        assertTrue(o.contains("World loaded"), "should continue after WARN: " + o);
        assertFalse(o.contains("All checks passed"), "summary must flag the WARN: " + o);
        assertTrue(o.contains("Issues found"), "summary-issues: " + o);
    }

    // ── 6. 活跃 session + 在播动画的 INFO 文案 ──
    @Test
    void diagnose_sessionActiveAndAnimating_infoLines() {
        DiagnosticsSubCommand.DiagnoseInputs in = new DiagnosticsSubCommand.DiagnoseInputs(
                Optional.of(healthyWall("w-live0000", 1, 1)), true,
                WORLD_LOADED, /*sessionActive*/ true, /*animating*/ true, POOL);
        cmd.diagnose(sender, "w-live0000", in);

        String o = out();
        assertTrue(o.contains("Editor session: active"), "session-active INFO: " + o);
        assertTrue(o.contains("Animation: playing"), "anim-playing INFO: " + o);
        assertTrue(o.contains("All checks passed"), "still all-OK (INFO not errors): " + o);
    }
}
