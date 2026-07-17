package ac.haru.hikaricanvas.state;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * M26-B：IconElement element.add 路径回归。
 *
 * <p><b>背景：</b> M26.1 升级 IconElement 为矢量 + Fill 联合类型时漏改 {@link EditSession#addElement}
 * 的 switch，所有 {@code type:"icon"} fall through default → {@code INVALID_ELEMENT}（前端拖入
 * IconLibrary 图标无反应的根因）。本测试保护该路径不再 regress。</p>
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>新协议 {@code fill}（SolidFill）入口</li>
 *   <li>旧协议 {@code tint} 自动升级为 SolidFill</li>
 *   <li>fill + tint 同时给：fill 优先</li>
 *   <li>都不给：fill = null（pack 默认色）</li>
 *   <li>非法 source 拒</li>
 *   <li>缺 source 拒</li>
 * </ul>
 */
class EditSessionIconTest {

    private static EditSession newSession() {
        return new EditSession(new ProjectState(2, 1));
    }

    @Test
    void addIconWithFill() {
        EditSession es = newSession();
        EditSession.OpResult r = es.addElement("icon", Map.of(
                "x", 10, "y", 20, "w", 32, "h", 32,
                "source", "fa-solid/heart",
                "fill", Map.of("type", "solid", "color", "#FF0000")
        ), null, null);
        EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class, r);
        assertNotNull(ok.patch());
        assertEquals(1, es.state().elements().size());
        IconElement ic = (IconElement) es.state().elements().get(0);
        assertEquals("fa-solid/heart", ic.source());
        assertNull(ic.tint());
        assertInstanceOf(SolidFill.class, ic.fill());
        assertEquals("#FF0000", ((SolidFill) ic.fill()).color());
    }

    @Test
    void addIconWithLegacyTintUpgradesToSolidFill() {
        EditSession es = newSession();
        EditSession.OpResult r = es.addElement("icon", Map.of(
                "source", "fa-solid/star",
                "tint", "#00FF00"
        ), null, null);
        assertInstanceOf(EditSession.OpResult.Ok.class, r);
        IconElement ic = (IconElement) es.state().elements().get(0);
        assertEquals("#00FF00", ic.tint());
        assertInstanceOf(SolidFill.class, ic.fill());
        assertEquals("#00FF00", ((SolidFill) ic.fill()).color());
    }

    @Test
    void addIconFillBeatsTintWhenBothPresent() {
        EditSession es = newSession();
        // 用 HashMap 因 Map.of 不允许重复 key 但允许混合不同 key —— 这里只需要保证 fill 不被 tint 覆盖
        Map<String, Object> p = new HashMap<>();
        p.put("source", "fa-solid/heart");
        p.put("tint", "#000000");
        p.put("fill", Map.of("type", "solid", "color", "#ABCDEF"));
        EditSession.OpResult r = es.addElement("icon", p, null, null);
        assertInstanceOf(EditSession.OpResult.Ok.class, r);
        IconElement ic = (IconElement) es.state().elements().get(0);
        assertEquals("#000000", ic.tint());
        // fill 优先：buildIcon 先读 parseFillNullable(p.get("fill"))，非 null 就不再从 tint 升级
        assertEquals("#ABCDEF", ((SolidFill) ic.fill()).color());
    }

    @Test
    void addIconWithoutFillOrTintHasNullFill() {
        EditSession es = newSession();
        EditSession.OpResult r = es.addElement("icon", Map.of(
                "source", "fa-solid/cog"
        ), null, null);
        assertInstanceOf(EditSession.OpResult.Ok.class, r);
        IconElement ic = (IconElement) es.state().elements().get(0);
        assertNull(ic.fill());
        assertNull(ic.tint());
    }

    @Test
    void addIconLegacySourceFormat() {
        // 不含 '/'，走 isLegacySource 路径（M7-M25 旧模板 PNG 形态）
        EditSession es = newSession();
        EditSession.OpResult r = es.addElement("icon", Map.of(
                "source", "heart"
        ), null, null);
        assertInstanceOf(EditSession.OpResult.Ok.class, r);
        IconElement ic = (IconElement) es.state().elements().get(0);
        assertEquals("heart", ic.source());
    }

    @Test
    void addIconInvalidSourceRejected() {
        EditSession es = newSession();
        // 大写 / 中文 / 多 slash 都不允许
        EditSession.OpResult r = es.addElement("icon", Map.of(
                "source", "FA-Solid/Heart"
        ), null, null);
        assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void addIconMissingSourceRejected() {
        EditSession es = newSession();
        // 不传 source；requireString(required=true) 抛 INVALID_PAYLOAD
        // 注意 Map.of 不能存 null，所以直接不放 source key
        EditSession.OpResult r = es.addElement("icon", Map.of(
                "x", 0, "y", 0
        ), null, null);
        assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void addIconWithMaterialPackSource() {
        // M27 material/ namespace —— IconElement.SOURCE_RE 已允许（pack=material name 含 dot）
        EditSession es = newSession();
        EditSession.OpResult r = es.addElement("icon", Map.of(
                "source", "material/star.fill"
        ), null, null);
        assertInstanceOf(EditSession.OpResult.Ok.class, r);
        IconElement ic = (IconElement) es.state().elements().get(0);
        assertEquals("material/star.fill", ic.source());
    }
}
