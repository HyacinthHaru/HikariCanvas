package ac.haru.hikaricanvas.web;

import ac.haru.hikaricanvas.state.EditSession;
import ac.haru.hikaricanvas.template.TemplateEntry;
import ac.haru.hikaricanvas.template.TemplateSource;
import ac.haru.hikaricanvas.template.TemplateSpec;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code template.apply} 对 pack 条目的重路由（Slice 2 P1）——轻量单测层。
 *
 * <p>覆盖 pack 分支里<b>不需重装配</b>的路径：{@code projectImporter} 未装配（导入子系统缺）时
 * pack 套用降级为 {@code INTERNAL_ERROR}；并顺带覆盖 {@link TemplateEntry#pack} 构造 +
 * {@link TemplateEntry#isPack()} / {@link TemplateEntry#packBytes()} 访问器。</p>
 *
 * <p><b>不覆盖</b>成功路径（{@code applyPack} 真跑出 {@code OkSnapshot}）——那需要真实
 * {@code SessionManager}（final，不可 stub）+ 真实 {@code ProjectImporter}（final，temp DB / WallRepo /
 * AssetIngest 全装配）+ 一个已注册的 session，等同把 {@code ProjectImporterPackTest} 再经 dispatcher
 * 跑一遍。留给 controller 的 disk→registry→template.apply→materialized 端到端集成测试。</p>
 */
class EditOpDispatcherPackRerouteTest {

    /** 全 null 装配——只驱动不解引用这些依赖的降级分支（同 {@code CanvasTweenFpsDispatchTest} 范式）。 */
    private EditOpDispatcher freshDispatcher() {
        return new EditOpDispatcher(
                /*sessionManager=*/null, /*throttler=*/null, /*rateLimiter=*/null,
                /*templateRegistry=*/null, /*wallRepo=*/null, /*push=*/null,
                /*auditLog=*/null);
    }

    private static TemplateSpec syntheticPackSpec() {
        return new TemplateSpec(1, "subway", "Subway", null, null, null, null, null,
                null, Map.of(), null, null);
    }

    /** projectImporter 从未注入（无 assetIngest / importConfig 的降级装配）→ pack 套用返 INTERNAL_ERROR。 */
    @Test
    void packEntry_withoutImporter_returnsInternalError() {
        EditOpDispatcher dispatcher = freshDispatcher();
        TemplateEntry packEntry = TemplateEntry.pack(
                syntheticPackSpec(), TemplateSource.SERVER, "server:subway.canvas",
                java.util.Optional.empty(), new byte[]{1, 2, 3});

        // projectImporter 为 null → 立即降级，不解引用 sessionManager（故全 null 装配安全）
        EditSession.OpResult r = dispatcher.applyPackEntry(
                "sess-1", "subway", packEntry, Map.of(), null);

        EditSession.OpResult.Error err = assertInstanceOf(EditSession.OpResult.Error.class, r);
        assertEquals("INTERNAL_ERROR", err.code());
    }

    /** TemplateEntry.pack / isPack / packBytes 访问器语义。 */
    @Test
    void packEntry_accessors() {
        byte[] bytes = new byte[]{9, 8, 7};
        TemplateEntry packEntry = TemplateEntry.pack(
                syntheticPackSpec(), TemplateSource.SERVER, "label",
                java.util.Optional.empty(), bytes);
        assertTrue(packEntry.isPack());
        assertEquals(bytes, packEntry.packBytes());
    }

    /** YAML 条目（3 参构造）isPack()==false / packBytes()==null——pack 分支不误吞 YAML 路径。 */
    @Test
    void yamlEntry_isNotPack() {
        TemplateEntry yaml = new TemplateEntry(
                syntheticPackSpec(), TemplateSource.BUILTIN, "builtin:hello.yml");
        assertFalse(yaml.isPack());
        assertNull(yaml.packBytes());
    }
}
