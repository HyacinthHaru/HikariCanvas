package ac.haru.hikaricanvas.state;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M8-C：验证所有 element / canvas / layer op 发出的 StatePatch path 都符合 v2 形态
 * （元素路径 {@code /layers/{i}/elements/{j}/...}、层路径 {@code /layers/{i}/...}、
 * 活动层 {@code /activeLayerId}、画布字段 {@code /canvas/...}）。
 *
 * <p>这是 v2 协议契约的硬验证；任何回归到 v1 path 都应在这里挂。</p>
 */
class EditSessionV2PathTest {

    private static EditSession newSession() {
        return new EditSession(new ProjectState(2, 1));
    }

    private static String activeLayerId(EditSession es) {
        return es.state().activeLayerId();
    }

    @Test
    void elementAddEmitsV2Path() {
        EditSession es = newSession();
        EditSession.OpResult r = es.addElement("text",
                Map.of("text", "hi"), null, null);
        EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class, r);

        assertEquals(1, ok.patch().ops().size());
        PatchOp p = ok.patch().ops().get(0);
        assertEquals("add", p.op());
        // 第一个元素落到 /layers/0/elements/0（默认活动层 = 第 0 层）
        assertEquals("/layers/0/elements/0", p.path());
    }

    @Test
    void elementUpdateEmitsV2Path() {
        EditSession es = newSession();
        EditSession.OpResult.Ok added = (EditSession.OpResult.Ok)
                es.addElement("text", Map.of("text", "hi"), null, null);
        String eid = ((Map<?, ?>) added.patch().ops().get(0).value()).get("id").toString();

        EditSession.OpResult r = es.updateElement(eid, Map.of("text", "ho", "x", 30));
        EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class, r);
        // 2 个 patch op，path 必须含 /layers/0/elements/0/
        assertEquals(2, ok.patch().ops().size());
        for (PatchOp p : ok.patch().ops()) {
            assertTrue(p.path().startsWith("/layers/0/elements/0/"),
                    "unexpected path: " + p.path());
        }
    }

    @Test
    void elementDeleteEmitsV2Path() {
        EditSession es = newSession();
        EditSession.OpResult.Ok added = (EditSession.OpResult.Ok)
                es.addElement("text", Map.of("text", "hi"), null, null);
        String eid = ((Map<?, ?>) added.patch().ops().get(0).value()).get("id").toString();

        EditSession.OpResult.Ok r = (EditSession.OpResult.Ok) es.deleteElement(eid);
        assertEquals("remove", r.patch().ops().get(0).op());
        assertEquals("/layers/0/elements/0", r.patch().ops().get(0).path());
    }

    @Test
    void elementReorderEmitsV2RemoveAddPath() {
        EditSession es = newSession();
        es.addElement("text", Map.of("text", "a"), null, null);
        EditSession.OpResult.Ok b = (EditSession.OpResult.Ok)
                es.addElement("text", Map.of("text", "b"), null, null);
        String bid = ((Map<?, ?>) b.patch().ops().get(0).value()).get("id").toString();

        // 把 b（在 index 1）移到 index 0
        EditSession.OpResult.Ok r = (EditSession.OpResult.Ok) es.reorderElement(bid, 0);
        assertEquals(2, r.patch().ops().size());
        assertEquals("/layers/0/elements/1", r.patch().ops().get(0).path());
        assertEquals("/layers/0/elements/0", r.patch().ops().get(1).path());
    }

    @Test
    void canvasBackgroundPathStaysV2() {
        EditSession es = newSession();
        EditSession.OpResult.Ok r = (EditSession.OpResult.Ok) es.setBackground("#123456");
        assertEquals("/canvas/background", r.patch().ops().get(0).path());
    }

    @Test
    void canvasGridEmitsCanvasGridSizePath() {
        EditSession es = newSession();
        EditSession.OpResult.Ok r = (EditSession.OpResult.Ok) es.setGridSize(16);
        assertEquals("/canvas/gridSize", r.patch().ops().get(0).path());
        assertEquals(16, r.patch().ops().get(0).value());
    }

    @Test
    void canvasGridZeroNormalizesToNull() {
        EditSession es = newSession();
        EditSession.OpResult.Ok r = (EditSession.OpResult.Ok) es.setGridSize(0);
        assertEquals("/canvas/gridSize", r.patch().ops().get(0).path());
        assertEquals(null, r.patch().ops().get(0).value());
    }

    @Test
    void canvasGuidesEmitsCanvasGuidesPath() {
        EditSession es = newSession();
        EditSession.OpResult.Ok r = (EditSession.OpResult.Ok) es.setGuides(java.util.List.of(
                Map.of("axis", "x", "position", 100),
                Map.of("axis", "y", "position", 50)));
        assertEquals("/canvas/guides", r.patch().ops().get(0).path());
        // value 应该是 List<Guide>
        Object v = r.patch().ops().get(0).value();
        assertInstanceOf(java.util.List.class, v);
        assertEquals(2, ((java.util.List<?>) v).size());
    }

    @Test
    void layerCreateEmitsLayersPath() {
        EditSession es = newSession();
        EditSession.OpResult.Ok r = (EditSession.OpResult.Ok) es.createLayer("UI", null);
        assertEquals(1, r.patch().ops().size());
        PatchOp p = r.patch().ops().get(0);
        assertEquals("add", p.op());
        // 默认 1 层 + 新增的，afterLayerId=null → 插到末尾 = index 1
        assertEquals("/layers/1", p.path());
        assertNotNull(p.value());
    }

    @Test
    void layerUpdateEmitsLayerFieldPath() {
        EditSession es = newSession();
        EditSession.OpResult.Ok r = (EditSession.OpResult.Ok)
                es.updateLayer(activeLayerId(es), Map.of("name", "Background", "visible", false));
        // 2 个 patch op，path 形如 /layers/0/name 和 /layers/0/visible
        assertEquals(2, r.patch().ops().size());
        for (PatchOp p : r.patch().ops()) {
            assertTrue(p.path().startsWith("/layers/0/"),
                    "unexpected path: " + p.path());
        }
    }

    @Test
    void layerSetActiveEmitsActiveLayerIdPath() {
        EditSession es = newSession();
        // 先建一个新层
        EditSession.OpResult.Ok created = (EditSession.OpResult.Ok) es.createLayer("Top", null);
        Map<?, ?> newLayerVal = (Map<?, ?>) created.patch().ops().get(0).value();
        String newLid = newLayerVal.get("id").toString();

        EditSession.OpResult.Ok r = (EditSession.OpResult.Ok) es.setActiveLayer(newLid);
        assertEquals(1, r.patch().ops().size());
        PatchOp p = r.patch().ops().get(0);
        assertEquals("replace", p.op());
        assertEquals("/activeLayerId", p.path());
        assertEquals(newLid, p.value());
    }

    @Test
    void elementMoveToLayerEmitsCrossLayerRemoveAdd() {
        EditSession es = newSession();
        EditSession.OpResult.Ok created = (EditSession.OpResult.Ok) es.createLayer("Top", null);
        String topLid = ((Map<?, ?>) created.patch().ops().get(0).value()).get("id").toString();

        EditSession.OpResult.Ok added = (EditSession.OpResult.Ok)
                es.addElement("text", Map.of("text", "hi"), null, null);
        String eid = ((Map<?, ?>) added.patch().ops().get(0).value()).get("id").toString();

        EditSession.OpResult.Ok r = (EditSession.OpResult.Ok)
                es.moveElementToLayer(eid, topLid, null);
        assertEquals(2, r.patch().ops().size());
        assertEquals("remove", r.patch().ops().get(0).op());
        assertEquals("/layers/0/elements/0", r.patch().ops().get(0).path());
        assertEquals("add", r.patch().ops().get(1).op());
        // 目标 layer 在 index 1，index null → 落到 0
        assertEquals("/layers/1/elements/0", r.patch().ops().get(1).path());
    }
}
