package ac.haru.hikaricanvas.state;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M13-A：{@link ImageElement} + {@link Mask} 通过 {@link EditSession} 的添加 / 更新行为校验。
 *
 * <p>测试覆盖：</p>
 * <ul>
 *   <li>合法 add：source 16 字符小写 hex；mask 可省略</li>
 *   <li>非法 source：长度 / 大小写 / 非 hex 字符 / 缺失</li>
 *   <li>mask 解析：含 d + inverted；非 Map / d 非 string / 非法 path / 超长 / inverted 非 boolean</li>
 *   <li>update：source 替换；mask 替换 / 清除；非白名单字段拒</li>
 *   <li>opacity / blendMode / renderMode 通过共通路径</li>
 * </ul>
 *
 * <p>真实文件解码 / 上传安全栈 / 渲染落地在 M13-B / M13-C / M13-D。</p>
 */
class EditSessionImageTest {

    private static final String VALID_SOURCE = "9f3a2b7e4c1d0a5f"; // 16 字符小写 hex

    private static EditSession newSession() {
        return new EditSession(new ProjectState(2, 1));
    }

    private static String addAndGetId(EditSession es, Map<String, Object> props) {
        EditSession.OpResult.Ok ok = (EditSession.OpResult.Ok)
                es.addElement("image", props, null, null);
        return ((Map<?, ?>) ok.patch().ops().get(0).value()).get("id").toString();
    }

    // ---------- add（无 mask） ----------

    @Test
    void addImageMinimal() {
        EditSession es = newSession();
        EditSession.OpResult r = es.addElement("image", Map.of(
                "x", 10, "y", 20, "w", 64, "h", 48,
                "source", VALID_SOURCE
        ), null, null);
        EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class, r);
        ImageElement im = (ImageElement) es.state().elements().get(0);
        assertEquals(10, im.x());
        assertEquals(48, im.h());
        assertEquals(VALID_SOURCE, im.source());
        assertNull(im.mask());
        assertTrue(im.visible());
        assertNotNull(ok.patch());
    }

    @Test
    void addImageMissingSourceRejected() {
        EditSession es = newSession();
        Map<String, Object> props = Map.of("w", 64, "h", 64);
        EditSession.OpResult r = es.addElement("image", props, null, null);
        assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void addImageSourceTooShortRejected() {
        EditSession es = newSession();
        EditSession.OpResult r = es.addElement("image", Map.of(
                "source", "9f3a2b7e"  // 8 字符，长度不足
        ), null, null);
        assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void addImageSourceUppercaseRejected() {
        EditSession es = newSession();
        EditSession.OpResult r = es.addElement("image", Map.of(
                "source", "9F3A2B7E4C1D0A5F"  // 大写 hex
        ), null, null);
        assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void addImageSourceNonHexRejected() {
        EditSession es = newSession();
        EditSession.OpResult r = es.addElement("image", Map.of(
                "source", "9f3a2b7e4c1d0a5g"  // g 不是 hex
        ), null, null);
        assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
    }

    // ---------- add with mask ----------

    @Test
    void addImageWithMask() {
        EditSession es = newSession();
        EditSession.OpResult r = es.addElement("image", Map.of(
                "source", VALID_SOURCE,
                "w", 100, "h", 100,
                "mask", Map.of(
                        "d", "M 0 0 L 100 0 L 100 100 L 0 100 Z",
                        "inverted", false
                )
        ), null, null);
        assertInstanceOf(EditSession.OpResult.Ok.class, r);
        ImageElement im = (ImageElement) es.state().elements().get(0);
        Mask m = im.mask();
        assertNotNull(m);
        assertEquals("M 0 0 L 100 0 L 100 100 L 0 100 Z", m.d());
        assertFalse(m.inverted());
    }

    @Test
    void addImageMaskInvertedDefaultsFalseWhenOmitted() {
        EditSession es = newSession();
        Map<String, Object> mask = new HashMap<>();
        mask.put("d", "M 0 0 L 50 50");
        // 不放 inverted
        EditSession.OpResult r = es.addElement("image", Map.of(
                "source", VALID_SOURCE, "mask", mask
        ), null, null);
        assertInstanceOf(EditSession.OpResult.Ok.class, r);
        ImageElement im = (ImageElement) es.state().elements().get(0);
        assertFalse(im.mask().inverted());
    }

    @Test
    void addImageMaskInvertedTrue() {
        EditSession es = newSession();
        EditSession.OpResult r = es.addElement("image", Map.of(
                "source", VALID_SOURCE,
                "mask", Map.of("d", "M 0 0 L 10 0", "inverted", true)
        ), null, null);
        assertInstanceOf(EditSession.OpResult.Ok.class, r);
        assertTrue(((ImageElement) es.state().elements().get(0)).mask().inverted());
    }

    @Test
    void addImageMaskNotObjectRejected() {
        EditSession es = newSession();
        EditSession.OpResult r = es.addElement("image", Map.of(
                "source", VALID_SOURCE,
                "mask", "circle"  // 不是 object
        ), null, null);
        assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void addImageMaskDNotStringRejected() {
        EditSession es = newSession();
        EditSession.OpResult r = es.addElement("image", Map.of(
                "source", VALID_SOURCE,
                "mask", Map.of("d", 123, "inverted", false)
        ), null, null);
        assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void addImageMaskDInvalidPathRejected() {
        EditSession es = newSession();
        EditSession.OpResult r = es.addElement("image", Map.of(
                "source", VALID_SOURCE,
                "mask", Map.of("d", "L 10 10", "inverted", false)  // 未以 M 开头
        ), null, null);
        assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void addImageMaskDTooLongRejected() {
        EditSession es = newSession();
        // PathDValidator MAX_LEN = 4096
        StringBuilder sb = new StringBuilder("M 0 0 ");
        while (sb.length() <= 4096) sb.append("L 1 1 ");
        EditSession.OpResult r = es.addElement("image", Map.of(
                "source", VALID_SOURCE,
                "mask", Map.of("d", sb.toString(), "inverted", false)
        ), null, null);
        assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void addImageMaskInvertedNotBooleanRejected() {
        EditSession es = newSession();
        EditSession.OpResult r = es.addElement("image", Map.of(
                "source", VALID_SOURCE,
                "mask", Map.of("d", "M 0 0 L 10 10", "inverted", "yes")
        ), null, null);
        assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
    }

    // ---------- 2026-05-25 项 2：mask featherPx ----------

    @Test
    void addImageMaskWithFeatherPx() {
        EditSession es = newSession();
        EditSession.OpResult r = es.addElement("image", Map.of(
                "source", VALID_SOURCE,
                "w", 64, "h", 64,
                "mask", Map.of(
                        "d", "M 0 0 L 64 0 L 64 64 L 0 64 Z",
                        "inverted", false,
                        "featherPx", 8
                )
        ), null, null);
        assertInstanceOf(EditSession.OpResult.Ok.class, r);
        Mask m = ((ImageElement) es.state().elements().get(0)).mask();
        assertNotNull(m);
        assertEquals(Integer.valueOf(8), m.featherPx());
        assertTrue(m.hasFeather());
        assertEquals(8, m.featherPxOrZero());
    }

    @Test
    void addImageMaskFeatherPxZeroNormalizedToNull() {
        EditSession es = newSession();
        EditSession.OpResult r = es.addElement("image", Map.of(
                "source", VALID_SOURCE,
                "mask", Map.of("d", "M 0 0 L 10 0 Z", "inverted", false, "featherPx", 0)
        ), null, null);
        assertInstanceOf(EditSession.OpResult.Ok.class, r);
        Mask m = ((ImageElement) es.state().elements().get(0)).mask();
        assertNotNull(m);
        // featherPx=0 应规范化为 null（向下兼容旧硬边路径）
        assertNull(m.featherPx());
        assertFalse(m.hasFeather());
    }

    @Test
    void addImageMaskFeatherPxOutOfRangeRejected() {
        EditSession es = newSession();
        EditSession.OpResult r = es.addElement("image", Map.of(
                "source", VALID_SOURCE,
                "mask", Map.of("d", "M 0 0 L 10 0 Z", "inverted", false, "featherPx", 100)
        ), null, null);
        assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void addImageMaskFeatherPxNegativeRejected() {
        EditSession es = newSession();
        EditSession.OpResult r = es.addElement("image", Map.of(
                "source", VALID_SOURCE,
                "mask", Map.of("d", "M 0 0 L 10 0 Z", "inverted", false, "featherPx", -5)
        ), null, null);
        assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void addImageMaskFeatherPxNonNumberRejected() {
        EditSession es = newSession();
        EditSession.OpResult r = es.addElement("image", Map.of(
                "source", VALID_SOURCE,
                "mask", Map.of("d", "M 0 0 L 10 0 Z", "inverted", false, "featherPx", "soft")
        ), null, null);
        assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
    }

    // ---------- update ----------

    @Test
    void updateImageSource() {
        EditSession es = newSession();
        String id = addAndGetId(es, Map.of("source", VALID_SOURCE));
        String newHash = "ffffffffffffffff";
        EditSession.OpResult r = es.updateElement(id, Map.of("source", newHash));
        assertInstanceOf(EditSession.OpResult.Ok.class, r);
        assertEquals(newHash, ((ImageElement) es.state().elements().get(0)).source());
    }

    @Test
    void updateImageSourceInvalidRejected() {
        EditSession es = newSession();
        String id = addAndGetId(es, Map.of("source", VALID_SOURCE));
        EditSession.OpResult r = es.updateElement(id, Map.of("source", "nope"));
        assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void updateImageSetMask() {
        EditSession es = newSession();
        String id = addAndGetId(es, Map.of("source", VALID_SOURCE, "w", 64, "h", 64));
        EditSession.OpResult r = es.updateElement(id, Map.of(
                "mask", Map.of("d", "M 0 0 L 64 64", "inverted", true)
        ));
        assertInstanceOf(EditSession.OpResult.Ok.class, r);
        Mask m = ((ImageElement) es.state().elements().get(0)).mask();
        assertNotNull(m);
        assertTrue(m.inverted());
    }

    @Test
    void updateImageClearMask() {
        EditSession es = newSession();
        String id = addAndGetId(es, Map.of(
                "source", VALID_SOURCE,
                "mask", Map.of("d", "M 0 0 L 10 0", "inverted", false)
        ));
        // patch 把 mask 设为 null → 清除
        Map<String, Object> patch = new HashMap<>();
        patch.put("mask", null);
        EditSession.OpResult r = es.updateElement(id, patch);
        assertInstanceOf(EditSession.OpResult.Ok.class, r);
        assertNull(((ImageElement) es.state().elements().get(0)).mask());
    }

    @Test
    void updateImageMaskInvalidPathRejected() {
        EditSession es = newSession();
        String id = addAndGetId(es, Map.of("source", VALID_SOURCE));
        EditSession.OpResult r = es.updateElement(id, Map.of(
                "mask", Map.of("d", "garbage", "inverted", false)
        ));
        assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void updateImageUnknownFieldRejected() {
        EditSession es = newSession();
        String id = addAndGetId(es, Map.of("source", VALID_SOURCE));
        EditSession.OpResult r = es.updateElement(id, Map.of("foo", "bar"));
        assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void updateImageOpacityBlendRenderMode() {
        EditSession es = newSession();
        String id = addAndGetId(es, Map.of("source", VALID_SOURCE));
        EditSession.OpResult r = es.updateElement(id, Map.of(
                "opacity", 0.5,
                "blendMode", "multiply",
                "renderMode", "dither"
        ));
        assertInstanceOf(EditSession.OpResult.Ok.class, r);
        ImageElement im = (ImageElement) es.state().elements().get(0);
        assertEquals(0.5f, im.opacity(), 1e-6f);
        assertEquals(BlendMode.MULTIPLY, im.blendMode());
        assertEquals(RenderMode.DITHER, im.renderMode());
    }

    @Test
    void updateImageGeometry() {
        EditSession es = newSession();
        String id = addAndGetId(es, Map.of("source", VALID_SOURCE));
        EditSession.OpResult r = es.updateElement(id, Map.of(
                "x", 100, "y", 200, "w", 256, "h", 128, "rotation", 45
        ));
        assertInstanceOf(EditSession.OpResult.Ok.class, r);
        ImageElement im = (ImageElement) es.state().elements().get(0);
        assertEquals(100, im.x());
        assertEquals(256, im.w());
        assertEquals(45, im.rotation());
    }
}
