package ac.haru.hikaricanvas.state;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.7.2-P2（F5/F10）：{@link EditSession#cloneElement} 行为验证。
 *
 * <p>覆盖：克隆加元素 + 新 id + 偏移生效 + 落到源 layer 末尾；缺失元素拒；
 * 锁定层拒；F10 配额超限拒；偏移越界钳位；add patch 形态。</p>
 */
class EditSessionCloneElementTest {

    private static EditSession newSession() {
        return new EditSession(new ProjectState(2, 1));
    }

    /** 加一个 text 元素，返回其 id。 */
    private static String addText(EditSession es, int x, int y) {
        EditSession.OpResult.Ok ok = (EditSession.OpResult.Ok)
                es.addElement("text", Map.of("text", "hi", "x", x, "y", y), null, null);
        return ((Map<?, ?>) ok.patch().ops().get(0).value()).get("id").toString();
    }

    private static String addedId(EditSession.OpResult.Ok ok) {
        return ((Map<?, ?>) ok.patch().ops().get(0).value()).get("id").toString();
    }

    private static Element find(EditSession es, String id) {
        for (Layer l : es.state().layers()) {
            for (Element e : l.elements()) {
                if (e.id().equals(id)) return e;
            }
        }
        return null;
    }

    @Test
    void clone_addsElement_withNewId_andOffset() {
        EditSession es = newSession();
        String srcId = addText(es, 10, 20);
        int before = es.state().layers().get(0).elements().size();

        EditSession.OpResult r = es.cloneElement(srcId, 5, 7);
        assertTrue(r instanceof EditSession.OpResult.Ok, () -> "应成功: " + r);
        EditSession.OpResult.Ok ok = (EditSession.OpResult.Ok) r;
        String newId = addedId(ok);

        assertEquals(before + 1, es.state().layers().get(0).elements().size(), "元素 +1");
        assertNotEquals(srcId, newId, "副本 id 不同");
        Element copy = find(es, newId);
        assertEquals(15, copy.x(), "x 偏移生效");
        assertEquals(27, copy.y(), "y 偏移生效");
        // 源元素未动
        assertEquals(10, find(es, srcId).x());
        assertEquals(20, find(es, srcId).y());
    }

    @Test
    void clone_zeroOffset_copiesAtSamePosition() {
        EditSession es = newSession();
        String srcId = addText(es, 30, 40);
        EditSession.OpResult.Ok ok = (EditSession.OpResult.Ok) es.cloneElement(srcId, 0, 0);
        Element copy = find(es, addedId(ok));
        assertEquals(30, copy.x());
        assertEquals(40, copy.y());
    }

    @Test
    void clone_appendsToOwningLayer_notActiveLayer() {
        EditSession es = newSession();
        // 在底层加源元素，再新建并激活一个上层
        String srcId = addText(es, 0, 0);
        es.createLayer("Top", null);
        // activeLayer 现在是新建的 Top（createLayer 设其为 active）
        es.cloneElement(srcId, 1, 1);
        // 副本应落在源元素所在的底层（index 0），不在 Top
        assertEquals(2, es.state().layers().get(0).elements().size(), "副本进底层");
        assertEquals(0, es.state().layers().get(1).elements().size(), "Top 层不受影响");
    }

    @Test
    void clone_missingElement_error() {
        EditSession es = newSession();
        EditSession.OpResult r = es.cloneElement("e-nope", 1, 1);
        assertEquals("INVALID_ELEMENT", ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void clone_nullElement_error() {
        EditSession es = newSession();
        EditSession.OpResult r = es.cloneElement(null, 1, 1);
        assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void clone_lockedLayer_error() {
        EditSession es = newSession();
        String srcId = addText(es, 0, 0);
        // 锁定源元素所在层
        String layerId = es.state().layers().get(0).id();
        es.updateLayer(layerId, Map.of("locked", true));
        EditSession.OpResult r = es.cloneElement(srcId, 1, 1);
        assertEquals("LAYER_LOCKED", ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void clone_quotaExceeded_error() {
        EditSession es = newSession();
        es.setMaxElementsPerWall(2);
        addText(es, 0, 0);
        String srcId = addText(es, 1, 1); // 现在 2 个元素 = 上限
        EditSession.OpResult r = es.cloneElement(srcId, 1, 1);
        assertEquals("QUOTA_EXCEEDED", ((EditSession.OpResult.Error) r).code());
        assertEquals(2, es.state().layers().get(0).elements().size(), "拒绝后元素数不变");
    }

    @Test
    void clone_quotaNotSet_allowsUnbounded() {
        EditSession es = newSession();
        String srcId = addText(es, 0, 0);
        for (int i = 0; i < 5; i++) {
            assertTrue(es.cloneElement(srcId, i, i) instanceof EditSession.OpResult.Ok);
        }
        assertEquals(6, es.state().layers().get(0).elements().size());
    }

    @Test
    void clone_offsetOverflow_clampsToBounds() {
        EditSession es = newSession();
        // 源 x=9999；offset +100 → 10099 应钳到 MAX_COORD=10000
        String srcId = addText(es, 9999, 0);
        EditSession.OpResult.Ok ok = (EditSession.OpResult.Ok) es.cloneElement(srcId, 100, 0);
        assertEquals(10_000, find(es, addedId(ok)).x(), "x 钳到 MAX_COORD");
    }

    @Test
    void clone_undo_restoresPreClone() {
        EditSession es = newSession();
        String srcId = addText(es, 0, 0);
        es.cloneElement(srcId, 1, 1);
        assertEquals(2, es.state().layers().get(0).elements().size());
        es.undo();
        assertEquals(1, es.state().layers().get(0).elements().size(), "undo 回到克隆前");
    }
}
