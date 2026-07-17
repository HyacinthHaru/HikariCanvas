package ac.haru.hikaricanvas.state;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * M8-C：locked 层校验。{@code element.add / update / delete / reorder / transform /
 * move-to-layer} 命中 locked layer 时必须返 {@code LAYER_LOCKED}；layer.update 本身可以
 * 改 locked 字段（包括 unlock 自己）。
 */
class EditSessionLockedLayerTest {

    private static EditSession newSession() {
        return new EditSession(new ProjectState(2, 1));
    }

    /** 工具：在 activeLayer 加一个 text 元素并返回 id；activeLayer 必须未锁。 */
    private static String addText(EditSession es, String content) {
        EditSession.OpResult.Ok ok = (EditSession.OpResult.Ok)
                es.addElement("text", Map.of("text", content), null, null);
        return ((Map<?, ?>) ok.patch().ops().get(0).value()).get("id").toString();
    }

    /** 工具：锁住 activeLayer。 */
    private static void lockActive(EditSession es) {
        es.updateLayer(es.state().activeLayerId(), Map.of("locked", true));
    }

    @Test
    void addToLockedLayerRejected() {
        EditSession es = newSession();
        lockActive(es);
        EditSession.OpResult r = es.addElement("text", Map.of("text", "x"), null, null);
        EditSession.OpResult.Error err = assertInstanceOf(EditSession.OpResult.Error.class, r);
        assertEquals("LAYER_LOCKED", err.code());
    }

    @Test
    void addExplicitLayerIdToLockedLayerRejected() {
        EditSession es = newSession();
        String lid = es.state().activeLayerId();
        lockActive(es);
        EditSession.OpResult r = es.addElement("text", Map.of("text", "x"), null, lid);
        assertEquals("LAYER_LOCKED",
                ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void updateInLockedLayerRejected() {
        EditSession es = newSession();
        String eid = addText(es, "hi");
        lockActive(es);
        EditSession.OpResult r = es.updateElement(eid, Map.of("text", "ho"));
        assertEquals("LAYER_LOCKED",
                ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void deleteInLockedLayerRejected() {
        EditSession es = newSession();
        String eid = addText(es, "hi");
        lockActive(es);
        EditSession.OpResult r = es.deleteElement(eid);
        assertEquals("LAYER_LOCKED",
                ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void reorderInLockedLayerRejected() {
        EditSession es = newSession();
        addText(es, "a");
        String bid = addText(es, "b");
        lockActive(es);
        EditSession.OpResult r = es.reorderElement(bid, 0);
        assertEquals("LAYER_LOCKED",
                ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void transformInLockedLayerRejected() {
        EditSession es = newSession();
        String eid = addText(es, "hi");
        lockActive(es);
        EditSession.OpResult r = es.transformElement(eid, 10, 20, null, null, null);
        assertEquals("LAYER_LOCKED",
                ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void moveToLayerFromLockedSourceRejected() {
        EditSession es = newSession();
        String eid = addText(es, "hi");
        EditSession.OpResult.Ok created = (EditSession.OpResult.Ok)
                es.createLayer("Top", null);
        String topLid = ((Map<?, ?>) created.patch().ops().get(0).value()).get("id").toString();
        lockActive(es);

        EditSession.OpResult r = es.moveElementToLayer(eid, topLid, null);
        assertEquals("LAYER_LOCKED",
                ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void moveToLayerToLockedTargetRejected() {
        EditSession es = newSession();
        String eid = addText(es, "hi");
        EditSession.OpResult.Ok created = (EditSession.OpResult.Ok)
                es.createLayer("Top", null);
        String topLid = ((Map<?, ?>) created.patch().ops().get(0).value()).get("id").toString();
        // 锁 target
        es.updateLayer(topLid, Map.of("locked", true));

        EditSession.OpResult r = es.moveElementToLayer(eid, topLid, null);
        assertEquals("LAYER_LOCKED",
                ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void lockedLayerCanStillBeUnlocked() {
        EditSession es = newSession();
        String lid = es.state().activeLayerId();
        lockActive(es);
        // 锁的层本身仍可更新（包括 unlock）
        EditSession.OpResult r = es.updateLayer(lid, Map.of("locked", false));
        assertInstanceOf(EditSession.OpResult.Ok.class, r);
        // 解锁后 element.add 重新可用
        EditSession.OpResult r2 = es.addElement("text", Map.of("text", "x"), null, null);
        assertInstanceOf(EditSession.OpResult.Ok.class, r2);
    }

    @Test
    void lockedLayerCanStillChangeVisibility() {
        EditSession es = newSession();
        String lid = es.state().activeLayerId();
        lockActive(es);
        EditSession.OpResult r = es.updateLayer(lid, Map.of("visible", false));
        assertInstanceOf(EditSession.OpResult.Ok.class, r);
        // visibility 改了，所以渲染受影响 — dirty 应非 null（即便层本身锁着）
        // 不过此刻层为空所以 dirty 也可能 null —— 加一个元素再测
        es.updateLayer(lid, Map.of("locked", false));
        addText(es, "content");
        lockActive(es);
        EditSession.OpResult.Ok r2 = (EditSession.OpResult.Ok)
                es.updateLayer(lid, Map.of("visible", true));
        // 此时 layer 非空 + visible 变化 → dirty 不应为 null
        assertNotNull(r2.dirty());
    }
}
