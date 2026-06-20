package moe.hikari.canvas.canvasfile;

import moe.hikari.canvas.render.FontRegistry;
import moe.hikari.canvas.render.IconRegistry;
import moe.hikari.canvas.state.BlendMode;
import moe.hikari.canvas.state.Element;
import moe.hikari.canvas.state.IconElement;
import moe.hikari.canvas.state.Layer;
import moe.hikari.canvas.state.ProjectState;
import moe.hikari.canvas.state.RenderMode;
import moe.hikari.canvas.state.TextElement;
import moe.hikari.canvas.storage.UserVariableDao;
import moe.hikari.canvas.variable.VarType;
import moe.hikari.canvas.variable.VariableStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.8 Part A review 补缺：{@link MissingResourceScanner} 单测——导入工程引用本服缺资源时
 * 产出 {@code missing-font} / {@code missing-icon} / {@code missing-variable} warning。
 *
 * <p>装配真 {@link FontRegistry}（loadBuiltIn 拿到内置字体 id 全集）、真 {@link IconRegistry}
 * （手动注册一个 user 图标）、真 {@link VariableStore}（用 fake DAO 注入 userglobal 变量）。
 * 变量判定<b>仅扫 userglobal</b>——user/* wall-scoped 导入本就要重设、schedule/rail 由 provider
 * 兜底，皆不报（见 scanner 类注释）。</p>
 */
class MissingResourceScannerTest {

    private static final Logger LOG = Logger.getLogger("missing-resource-scanner-test");

    private FontRegistry fonts;
    private IconRegistry icons;
    private VariableStore variables;

    @BeforeEach
    void setUp() {
        fonts = new FontRegistry(LOG);
        fonts.loadBuiltIn();   // ark_pixel / inter / ... 内置 id 全集（缺时 jar 资源缺，但 id 已注册）

        icons = new IconRegistry(LOG);
        // 不依赖 jar /icons/*.icons.json（构建期生成、CI 不一定有），手动注册一个 user 图标。
        registerUserIcon(icons, "logo");

        variables = new VariableStore(new NoopUserVariableDao(), wid -> {});
        // 注入一个存在的 userglobal 变量
        variables.create(VariableStore.USER_GLOBAL_NAMESPACE, "red_score", VarType.NUMBER, "0", "manual");
    }

    // ──────────────────────────────────────────────────────────
    //  missing-font
    // ──────────────────────────────────────────────────────────

    @Test
    void scan_unknownFont_producesMissingFontWarning() {
        ProjectState state = stateWith(text("e1", "no_such_font", "hello"));
        List<ImportWarning> w = new MissingResourceScanner(fonts, icons, variables).scan(state);
        assertEquals(1, kindCount(w, "missing-font"));
        assertEquals("no_such_font", firstDetail(w, "missing-font"));
    }

    @Test
    void scan_builtInFont_noMissingFontWarning() {
        ProjectState state = stateWith(text("e1", "inter", "hello"));
        List<ImportWarning> w = new MissingResourceScanner(fonts, icons, variables).scan(state);
        assertEquals(0, kindCount(w, "missing-font"), "内置字体不应报缺");
    }

    @Test
    void scan_nullFontId_noMissingFontWarning() {
        // fontId == null → 渲染期走默认字体，不是"用户指定了某字体但缺"，不报。
        ProjectState state = stateWith(text("e1", null, "hello"));
        List<ImportWarning> w = new MissingResourceScanner(fonts, icons, variables).scan(state);
        assertEquals(0, kindCount(w, "missing-font"));
    }

    @Test
    void scan_sameMissingFontTwice_dedupes() {
        ProjectState state = stateWith(
                text("e1", "ghost_font", "a"),
                text("e2", "ghost_font", "b"));
        List<ImportWarning> w = new MissingResourceScanner(fonts, icons, variables).scan(state);
        assertEquals(1, kindCount(w, "missing-font"), "同一缺字体只报一次");
    }

    // ──────────────────────────────────────────────────────────
    //  missing-icon
    // ──────────────────────────────────────────────────────────

    @Test
    void scan_unknownUserIcon_producesMissingIconWarning() {
        ProjectState state = stateWith(icon("i1", "user/ghost"));
        List<ImportWarning> w = new MissingResourceScanner(fonts, icons, variables).scan(state);
        assertEquals(1, kindCount(w, "missing-icon"));
        assertEquals("user/ghost", firstDetail(w, "missing-icon"));
    }

    @Test
    void scan_registeredUserIcon_noMissingIconWarning() {
        ProjectState state = stateWith(icon("i1", "user/logo"));
        List<ImportWarning> w = new MissingResourceScanner(fonts, icons, variables).scan(state);
        assertEquals(0, kindCount(w, "missing-icon"), "已注册 user 图标不报缺");
    }

    @Test
    void scan_builtInFaIcon_noMissingIconWarning() {
        // fa-* 内置恒在，不查 registry（CI 上 jar 包可能没装内置图标，但绝不是"缺"）。
        ProjectState state = stateWith(
                icon("i1", "fa-solid/heart"),
                icon("i2", "fa-regular/star"),
                icon("i3", "fa-brands/github"));
        List<ImportWarning> w = new MissingResourceScanner(fonts, icons, variables).scan(state);
        assertEquals(0, kindCount(w, "missing-icon"), "内置 fa-* 不算缺");
    }

    @Test
    void scan_legacyPngIcon_noMissingIconWarning() {
        // legacy 不含 '/' 的 PNG 形态走 TemplateAssetService，不是 user 图标，不在本扫描范围。
        ProjectState state = stateWith(icon("i1", "heart"));
        List<ImportWarning> w = new MissingResourceScanner(fonts, icons, variables).scan(state);
        assertEquals(0, kindCount(w, "missing-icon"));
    }

    @Test
    void scan_sameMissingUserIconTwice_dedupes() {
        ProjectState state = stateWith(
                icon("i1", "user/ghost"),
                icon("i2", "user/ghost"));
        List<ImportWarning> w = new MissingResourceScanner(fonts, icons, variables).scan(state);
        assertEquals(1, kindCount(w, "missing-icon"), "同一缺图标只报一次");
    }

    // ──────────────────────────────────────────────────────────
    //  missing-variable（仅 userglobal）
    // ──────────────────────────────────────────────────────────

    @Test
    void scan_unknownUserGlobal_producesMissingVariableWarning() {
        ProjectState state = stateWith(text("e1", "inter", "score: ${var:userglobal/blue_score}"));
        List<ImportWarning> w = new MissingResourceScanner(fonts, icons, variables).scan(state);
        assertEquals(1, kindCount(w, "missing-variable"));
        assertEquals("userglobal/blue_score", firstDetail(w, "missing-variable"));
    }

    @Test
    void scan_existingUserGlobal_noMissingVariableWarning() {
        ProjectState state = stateWith(text("e1", "inter", "score: ${var:userglobal/red_score}"));
        List<ImportWarning> w = new MissingResourceScanner(fonts, icons, variables).scan(state);
        assertEquals(0, kindCount(w, "missing-variable"), "存在的 userglobal 变量不报缺");
    }

    @Test
    void scan_userScopedVariable_notReported() {
        // ${var:user/X} 是 wall-scoped；导入新墙本就要重设，不算"缺"——避免误报。
        ProjectState state = stateWith(text("e1", "inter", "${var:user/my_score}"));
        List<ImportWarning> w = new MissingResourceScanner(fonts, icons, variables).scan(state);
        assertEquals(0, kindCount(w, "missing-variable"), "user/* 不报缺");
    }

    @Test
    void scan_scheduleAndScoreboardAndSystem_notReported() {
        // schedule/rail/scoreboard/system 由本服 provider 兜底 resolve，不报（避免误报）。
        ProjectState state = stateWith(
                text("e1", "inter", "${var:schedule.next_departure}"),
                text("e2", "inter", "${var:scoreboard.kills.Steve}"),
                text("e3", "inter", "${var:wall.id}"),
                text("e4", "inter", "${var:server.time}"));
        List<ImportWarning> w = new MissingResourceScanner(fonts, icons, variables).scan(state);
        assertEquals(0, kindCount(w, "missing-variable"), "非 userglobal namespace 不报缺");
    }

    @Test
    void scan_userGlobalWithFallback_stillScansName() {
        // ${var:userglobal/X|fallback=...} 的 fallback 不改变"X 缺不缺"的判定。
        ProjectState state = stateWith(
                text("e1", "inter", "${var:userglobal/ghost|fallback=—}"));
        List<ImportWarning> w = new MissingResourceScanner(fonts, icons, variables).scan(state);
        assertEquals(1, kindCount(w, "missing-variable"));
        assertEquals("userglobal/ghost", firstDetail(w, "missing-variable"));
    }

    @Test
    void scan_sameMissingUserGlobalTwice_dedupes() {
        ProjectState state = stateWith(
                text("e1", "inter", "${var:userglobal/ghost}"),
                text("e2", "inter", "${var:userglobal/ghost} again ${var:userglobal/ghost}"));
        List<ImportWarning> w = new MissingResourceScanner(fonts, icons, variables).scan(state);
        assertEquals(1, kindCount(w, "missing-variable"), "同一缺变量只报一次");
    }

    // ──────────────────────────────────────────────────────────
    //  可空降级（裸装配）
    // ──────────────────────────────────────────────────────────

    @Test
    void scan_nullRegistries_skipsCorrespondingScans() {
        // FontRegistry / IconRegistry / VariableStore 任一 null → 跳过对应扫描（best-effort）。
        ProjectState state = stateWith(
                text("e1", "ghost_font", "${var:userglobal/ghost_var}"),
                icon("i1", "user/ghost_icon"));

        // 全 null → 完全无 warning
        assertTrue(new MissingResourceScanner(null, null, null).scan(state).isEmpty());

        // 只有字体 → 只报 missing-font
        List<ImportWarning> onlyFont = new MissingResourceScanner(fonts, null, null).scan(state);
        assertEquals(1, kindCount(onlyFont, "missing-font"));
        assertEquals(0, kindCount(onlyFont, "missing-icon"));
        assertEquals(0, kindCount(onlyFont, "missing-variable"));

        // 只有图标 → 只报 missing-icon
        List<ImportWarning> onlyIcon = new MissingResourceScanner(null, icons, null).scan(state);
        assertEquals(1, kindCount(onlyIcon, "missing-icon"));
        assertEquals(0, kindCount(onlyIcon, "missing-font"));

        // 只有变量 → 只报 missing-variable
        List<ImportWarning> onlyVar = new MissingResourceScanner(null, null, variables).scan(state);
        assertEquals(1, kindCount(onlyVar, "missing-variable"));
        assertEquals(0, kindCount(onlyVar, "missing-font"));
    }

    @Test
    void scan_emptyProject_noWarnings() {
        ProjectState state = new ProjectState(2, 1);
        List<ImportWarning> w = new MissingResourceScanner(fonts, icons, variables).scan(state);
        assertTrue(w.isEmpty(), "空工程无任何 missing-* warning");
    }

    @Test
    void scan_multiLayer_scansAllLayers() {
        // 缺资源分散在不同 layer，都要被扫到。
        TextElement t = text("e1", "ghost_font", "hi");
        IconElement ic = icon("i1", "user/ghost");
        Layer la = new Layer("la", "A", true, false, 1.0f, BlendMode.NORMAL, null,
                new ArrayList<>(List.of(t)));
        Layer lb = new Layer("lb", "B", true, false, 1.0f, BlendMode.NORMAL, null,
                new ArrayList<>(List.of(ic)));
        ProjectState state = stateWithLayers(List.of(la, lb));
        List<ImportWarning> w = new MissingResourceScanner(fonts, icons, variables).scan(state);
        assertEquals(1, kindCount(w, "missing-font"));
        assertEquals(1, kindCount(w, "missing-icon"));
    }

    // ──────────────────────────────────────────────────────────
    //  helpers
    // ──────────────────────────────────────────────────────────

    private static TextElement text(String id, String fontId, String text) {
        return new TextElement(id, 0, 0, 100, 50, 0, false, true,
                text, fontId, 24, "#000000", "left", 0f, 1.2f, false, null,
                1.0f, BlendMode.NORMAL, RenderMode.CLEAN, null, null);
    }

    private static IconElement icon(String id, String source) {
        return new IconElement(id, 0, 0, 32, 32, 0, false, true,
                source, null, 1.0f, BlendMode.NORMAL, RenderMode.CLEAN, null);
    }

    private static ProjectState stateWith(Element... els) {
        Layer layer = new Layer("l-1", "L", true, false, 1.0f, BlendMode.NORMAL, null,
                new ArrayList<>(List.of(els)));
        return stateWithLayers(List.of(layer));
    }

    private static ProjectState stateWithLayers(List<Layer> layers) {
        ProjectState.Canvas canvas = new ProjectState.Canvas(2, 1, (moe.hikari.canvas.state.Fill) null);
        return new ProjectState(0L, canvas,
                null, layers, layers.get(0).id(), null, null, null, null);
    }

    private static int kindCount(List<ImportWarning> w, String kind) {
        return (int) w.stream().filter(x -> kind.equals(x.kind())).count();
    }

    private static String firstDetail(List<ImportWarning> w, String kind) {
        return w.stream().filter(x -> kind.equals(x.kind())).map(ImportWarning::detail)
                .findFirst().orElse(null);
    }

    private static void registerUserIcon(IconRegistry reg, String id) {
        // IconRegistry.loadExternal 扫目录；测试里走它太重。直接用最小 SVG 经 loadExternal
        // 不便（要写临时文件）。改用反射注入 user pack —— 但 index/packs 私有。
        // 折衷：写一个临时目录 + 一个合法 SVG，让真 loadExternal 注册。
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("hc-icons-");
            java.nio.file.Path svg = dir.resolve(id + ".svg");
            java.nio.file.Files.writeString(svg,
                    "<svg viewBox=\"0 0 24 24\"><path d=\"M0 0 L24 24 Z\"/></svg>");
            reg.loadExternal(dir);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 内存 no-op DAO：scanner 测只往 userglobal namespace 注入变量，user 持久化路径不触发。 */
    private static final class NoopUserVariableDao extends UserVariableDao {
        NoopUserVariableDao() {
            super(LOG, null);
        }

        @Override
        public void upsert(String wallId, String name, VarType type,
                           String defaultValue, String currentValue, String boundTo,
                           long createdAt, long updatedAt) {
            // no-op
        }

        @Override
        public void delete(String wallId, String name) {
            // no-op
        }

        @Override
        public List<Row> loadAll() {
            return List.of();
        }
    }

}
