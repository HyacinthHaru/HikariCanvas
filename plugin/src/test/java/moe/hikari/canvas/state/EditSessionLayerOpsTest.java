package moe.hikari.canvas.state;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M8-C：layer.* op 族 + element.move-to-layer + canvas.grid / canvas.guides.set 行为验证。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>create / delete / update / reorder / duplicate / set-active</li>
 *   <li>LAST_LAYER（最后一层禁删）</li>
 *   <li>LAYER_NOT_FOUND（不存在的 layerId）</li>
 *   <li>activeLayerId 在删除当前活动层时自动转移</li>
 *   <li>duplicate 元素 id 重生成；副本默认不锁</li>
 *   <li>undo 能回到 layer op 之前的状态</li>
 * </ul>
 */
class EditSessionLayerOpsTest {

    private static EditSession newSession() {
        return new EditSession(new ProjectState(2, 1));
    }

    private static String layerIdOfPatch(EditSession.OpResult.Ok ok) {
        return ((Map<?, ?>) ok.patch().ops().get(0).value()).get("id").toString();
    }

    private static String addText(EditSession es, String content) {
        EditSession.OpResult.Ok ok = (EditSession.OpResult.Ok)
                es.addElement("text", Map.of("text", content), null, null);
        return ((Map<?, ?>) ok.patch().ops().get(0).value()).get("id").toString();
    }

    // ---------- layer.create ----------

    @Test
    void createAppendsToTop() {
        EditSession es = newSession();
        EditSession.OpResult.Ok r = (EditSession.OpResult.Ok) es.createLayer("UI", null);
        assertEquals(2, es.state().layers().size());
        assertEquals("UI", es.state().layers().get(1).name());
    }

    @Test
    void createWithAfterLayerIdInsertsAbove() {
        EditSession es = newSession();
        // 已有 layer 0 = Default Layer
        String l0 = es.state().activeLayerId();
        EditSession.OpResult.Ok r1 = (EditSession.OpResult.Ok) es.createLayer("Top", null);
        String l2 = layerIdOfPatch(r1);

        // 在 l0 之上插一个 "Mid" → 应在 index 1，l2 被推到 index 2
        EditSession.OpResult.Ok r2 = (EditSession.OpResult.Ok) es.createLayer("Mid", l0);
        assertEquals(3, es.state().layers().size());
        assertEquals("Mid", es.state().layers().get(1).name());
        assertEquals(l2, es.state().layers().get(2).id());
    }

    @Test
    void createWithUnknownAfterLayerIdRejected() {
        EditSession es = newSession();
        EditSession.OpResult r = es.createLayer("X", "l-nonexistent");
        assertEquals("LAYER_NOT_FOUND",
                ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void createWithBlankNameGetsAutoName() {
        EditSession es = newSession();
        EditSession.OpResult.Ok r = (EditSession.OpResult.Ok) es.createLayer("", null);
        // 现有 1 层，新建后名应为 "Layer 2"
        assertEquals("Layer 2", es.state().layers().get(1).name());
    }

    // ---------- layer.delete ----------

    @Test
    void deleteRemovesLayer() {
        EditSession es = newSession();
        EditSession.OpResult.Ok created = (EditSession.OpResult.Ok)
                es.createLayer("Top", null);
        String topId = layerIdOfPatch(created);
        EditSession.OpResult.Ok r = (EditSession.OpResult.Ok) es.deleteLayer(topId);
        assertEquals(1, es.state().layers().size());
        assertEquals("remove", r.patch().ops().get(0).op());
    }

    @Test
    void deleteLastLayerRejected() {
        EditSession es = newSession();
        String l0 = es.state().activeLayerId();
        EditSession.OpResult r = es.deleteLayer(l0);
        assertEquals("LAST_LAYER",
                ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void deleteUnknownLayerRejected() {
        EditSession es = newSession();
        es.createLayer("dummy", null); // 让 layers > 1，否则 LAST_LAYER 先抢
        EditSession.OpResult r = es.deleteLayer("l-nonexistent");
        assertEquals("LAYER_NOT_FOUND",
                ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void deleteActiveLayerTransfersActiveLayerIdAndEmitsPatch() {
        EditSession es = newSession();
        String l0 = es.state().activeLayerId();
        EditSession.OpResult.Ok created = (EditSession.OpResult.Ok)
                es.createLayer("Top", null);
        String topId = layerIdOfPatch(created);

        // 把 active 设为 l0（默认就是 l0）。删除 l0，应自动转到剩余 layers.first
        EditSession.OpResult.Ok r = (EditSession.OpResult.Ok) es.deleteLayer(l0);
        assertEquals(topId, es.state().activeLayerId());
        // patch 应含 remove + replace /activeLayerId
        assertEquals(2, r.patch().ops().size());
        assertEquals("/activeLayerId", r.patch().ops().get(1).path());
        assertEquals(topId, r.patch().ops().get(1).value());
    }

    // ---------- layer.update ----------

    @Test
    void updateChangesFields() {
        EditSession es = newSession();
        String l0 = es.state().activeLayerId();
        EditSession.OpResult.Ok r = (EditSession.OpResult.Ok)
                es.updateLayer(l0, Map.of("name", "BG", "visible", false, "opacity", 0.5));
        Layer updated = es.state().layers().get(0);
        assertEquals("BG", updated.name());
        assertFalse(updated.visible());
        assertEquals(0.5f, updated.opacity(), 0.0001);
    }

    @Test
    void updateOpacityOutOfRangeRejected() {
        EditSession es = newSession();
        String l0 = es.state().activeLayerId();
        EditSession.OpResult r = es.updateLayer(l0, Map.of("opacity", 1.5));
        assertEquals("INVALID_PAYLOAD",
                ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void updateUnknownBlendModeRejected() {
        EditSession es = newSession();
        String l0 = es.state().activeLayerId();
        EditSession.OpResult r = es.updateLayer(l0, Map.of("blendMode", "darken"));
        assertEquals("INVALID_PAYLOAD",
                ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void updateUnknownLayerRejected() {
        EditSession es = newSession();
        EditSession.OpResult r = es.updateLayer("l-nonexistent",
                Map.of("name", "x"));
        assertEquals("LAYER_NOT_FOUND",
                ((EditSession.OpResult.Error) r).code());
    }

    // ---------- M8-TODO 项 2：colorTag ----------

    @Test
    void updateAcceptsValidColorTag() {
        EditSession es = newSession();
        String l0 = es.state().activeLayerId();
        EditSession.OpResult.Ok r = (EditSession.OpResult.Ok)
                es.updateLayer(l0, Map.of("colorTag", "red"));
        assertEquals("red", es.state().layers().get(0).colorTag());
        // patch 形态：/layers/0/colorTag replace
        assertEquals("/layers/0/colorTag", r.patch().ops().get(0).path());
        assertEquals("red", r.patch().ops().get(0).value());
    }

    @Test
    void updateColorTagToEmptyStringClears() {
        EditSession es = newSession();
        String l0 = es.state().activeLayerId();
        es.updateLayer(l0, Map.of("colorTag", "blue"));
        // 空字符串 = 清除（前端 "X" 按钮路径）
        es.updateLayer(l0, Map.of("colorTag", ""));
        assertNotNull(es.state().layers().get(0));
        assertTrue(es.state().layers().get(0).colorTag() == null,
                "empty string should clear colorTag");
    }

    @Test
    void updateColorTagRejectsUnknownValue() {
        EditSession es = newSession();
        String l0 = es.state().activeLayerId();
        EditSession.OpResult r = es.updateLayer(l0, Map.of("colorTag", "fuchsia"));
        assertEquals("INVALID_PAYLOAD",
                ((EditSession.OpResult.Error) r).code());
    }

    @Test
    void layerDefaultsColorTagToNull() {
        EditSession es = newSession();
        assertTrue(es.state().layers().get(0).colorTag() == null,
                "fresh layer should have no colorTag");
    }

    // ---------- layer.reorder ----------

    @Test
    void reorderMovesLayer() {
        EditSession es = newSession();
        String l0 = es.state().activeLayerId();
        es.createLayer("Top", null); // l1
        EditSession.OpResult.Ok r = (EditSession.OpResult.Ok) es.reorderLayer(l0, 1);
        // 现在 l0 在 index 1，Top 在 index 0
        assertEquals(l0, es.state().layers().get(1).id());
        assertEquals("Top", es.state().layers().get(0).name());
    }

    // ---------- layer.duplicate ----------

    @Test
    void duplicateClonesElementsWithNewIds() {
        EditSession es = newSession();
        String l0 = es.state().activeLayerId();
        String e1 = addText(es, "hi");
        EditSession.OpResult.Ok r = (EditSession.OpResult.Ok) es.duplicateLayer(l0);
        // 现在两层；duplicate 出来的层在 index 1
        assertEquals(2, es.state().layers().size());
        Layer copy = es.state().layers().get(1);
        assertEquals(1, copy.elements().size());
        // 元素 id 必须不同
        assertNotEquals(e1, copy.elements().get(0).id());
        // 内容应保留
        TextElement copied = (TextElement) copy.elements().get(0);
        assertEquals("hi", copied.text());
    }

    @Test
    void duplicatedLayerIsUnlocked() {
        EditSession es = newSession();
        String l0 = es.state().activeLayerId();
        es.updateLayer(l0, Map.of("locked", true));
        EditSession.OpResult.Ok r = (EditSession.OpResult.Ok) es.duplicateLayer(l0);
        Layer copy = es.state().layers().get(1);
        assertFalse(copy.locked(), "duplicated layer should default to unlocked");
    }

    // ---------- layer.set-active ----------

    @Test
    void setActiveSwitchesActiveLayerId() {
        EditSession es = newSession();
        EditSession.OpResult.Ok created = (EditSession.OpResult.Ok) es.createLayer("Top", null);
        String topId = layerIdOfPatch(created);
        EditSession.OpResult.Ok r = (EditSession.OpResult.Ok) es.setActiveLayer(topId);
        assertEquals(topId, es.state().activeLayerId());
        // patch 是 /activeLayerId replace
        assertEquals("/activeLayerId", r.patch().ops().get(0).path());
    }

    @Test
    void setActiveDoesNotPushHistory() {
        // M8-C 决策：set-active 不进 undo 栈
        EditSession es = newSession();
        String l0 = es.state().activeLayerId();
        EditSession.OpResult.Ok created = (EditSession.OpResult.Ok) es.createLayer("Top", null);
        String topId = layerIdOfPatch(created);
        // 此时 history past 含 createLayer 的 pre-snapshot

        es.setActiveLayer(topId);
        // undo 应跳过 set-active，直接撤销 createLayer
        EditSession.OpResult undoR = es.undo();
        assertInstanceOf(EditSession.OpResult.OkSnapshot.class, undoR);
        assertEquals(1, es.state().layers().size(),
                "undo should revert createLayer; set-active mustn't be on history stack");
    }

    // ---------- canvas.grid ----------

    @Test
    void gridSizeSetsValueAndPersists() {
        EditSession es = newSession();
        es.setGridSize(16);
        assertEquals(16, es.state().canvas().gridSize());
    }

    @Test
    void gridSizeZeroNormalizesToNullInState() {
        EditSession es = newSession();
        es.setGridSize(0);
        assertEquals(null, es.state().canvas().gridSize());
    }

    @Test
    void gridSizeNegativeRejected() {
        EditSession es = newSession();
        EditSession.OpResult r = es.setGridSize(-1);
        assertEquals("INVALID_PAYLOAD",
                ((EditSession.OpResult.Error) r).code());
    }

    // ---------- canvas.guides.set ----------

    @Test
    void guidesSetReplacesEntirely() {
        EditSession es = newSession();
        es.setGuides(List.of(
                Map.of("axis", "x", "position", 100),
                Map.of("axis", "y", "position", 60)));
        assertEquals(2, es.state().canvas().guides().size());

        es.setGuides(List.of(Map.of("axis", "x", "position", 200)));
        assertEquals(1, es.state().canvas().guides().size());
        assertEquals(200, es.state().canvas().guides().get(0).position());
    }

    @Test
    void guidesEmptyClears() {
        EditSession es = newSession();
        es.setGuides(List.of(Map.of("axis", "x", "position", 100)));
        es.setGuides(List.of());
        assertEquals(0, es.state().canvas().guides().size());
    }

    @Test
    void guidesInvalidAxisRejected() {
        EditSession es = newSession();
        EditSession.OpResult r = es.setGuides(List.of(
                Map.of("axis", "z", "position", 100)));
        assertEquals("INVALID_PAYLOAD",
                ((EditSession.OpResult.Error) r).code());
    }

    // ---------- undo 整树 ----------

    @Test
    void undoAfterLayerCreateRestoresLayerCount() {
        EditSession es = newSession();
        int before = es.state().layers().size();
        es.createLayer("Top", null);
        assertEquals(before + 1, es.state().layers().size());
        EditSession.OpResult r = es.undo();
        assertInstanceOf(EditSession.OpResult.OkSnapshot.class, r);
        assertEquals(before, es.state().layers().size());
    }

    @Test
    void undoAfterLayerDeleteRestoresLayerAndActiveId() {
        EditSession es = newSession();
        String l0 = es.state().activeLayerId();
        EditSession.OpResult.Ok created = (EditSession.OpResult.Ok)
                es.createLayer("Top", null);
        String topId = layerIdOfPatch(created);
        // 现在 active 仍是 l0；删除 l0 会切到 topId
        es.deleteLayer(l0);
        assertEquals(topId, es.state().activeLayerId());
        // undo
        es.undo();
        assertEquals(2, es.state().layers().size());
        assertEquals(l0, es.state().activeLayerId());
    }

    @Test
    void undoAfterMoveElementToLayer() {
        EditSession es = newSession();
        String l0 = es.state().activeLayerId();
        String e = addText(es, "hi");
        EditSession.OpResult.Ok created = (EditSession.OpResult.Ok)
                es.createLayer("Top", null);
        String topId = layerIdOfPatch(created);
        es.moveElementToLayer(e, topId, null);
        // 元素现应在 top layer
        assertEquals(0, es.state().layers().get(0).elements().size());
        assertEquals(1, es.state().layers().get(1).elements().size());

        es.undo();
        // 撤销后元素应回到 l0
        assertEquals(1, es.state().layers().get(0).elements().size());
        assertEquals(0, es.state().layers().get(1).elements().size());
    }
}
