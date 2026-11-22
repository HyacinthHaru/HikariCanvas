package ac.haru.hikaricanvas.state;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EditSession} 图层、画布级设置、锁定层拦截、工程整体替换与历史栈边界测试。
 */
class EditSessionLayerTest {

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

    // ──────────────────────────────────────────────────────────
    //  layer.* + canvas.grid / guides / resize + 跨层 undo
    // ──────────────────────────────────────────────────────────

    @Nested
    class LayerAndCanvasOps {

        @Test
        void createAppendsToTop() {
            EditSession es = newSession();
            assertInstanceOf(EditSession.OpResult.Ok.class, es.createLayer("UI", null));
            assertEquals(2, es.state().layers().size());
            assertEquals("UI", es.state().layers().get(1).name());
        }

        @Test
        void createWithAfterLayerIdInsertsAbove() {
            EditSession es = newSession();
            String l0 = es.state().activeLayerId();
            EditSession.OpResult.Ok r1 = (EditSession.OpResult.Ok) es.createLayer("Top", null);
            String l2 = layerIdOfPatch(r1);

            assertInstanceOf(EditSession.OpResult.Ok.class, es.createLayer("Mid", l0));
            assertEquals(3, es.state().layers().size());
            assertEquals("Mid", es.state().layers().get(1).name());
            assertEquals(l2, es.state().layers().get(2).id());
        }

        @Test
        void createWithUnknownAfterLayerIdRejected() {
            EditSession es = newSession();
            EditSession.OpResult r = es.createLayer("X", "l-nonexistent");
            assertEquals("LAYER_NOT_FOUND", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void createWithBlankNameGetsAutoName() {
            EditSession es = newSession();
            assertInstanceOf(EditSession.OpResult.Ok.class, es.createLayer("", null));
            assertEquals("Layer 2", es.state().layers().get(1).name());
        }

        @Test
        void deleteRemovesLayer() {
            EditSession es = newSession();
            EditSession.OpResult.Ok created = (EditSession.OpResult.Ok) es.createLayer("Top", null);
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
            assertEquals("LAST_LAYER", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void deleteUnknownLayerRejected() {
            EditSession es = newSession();
            es.createLayer("dummy", null);
            EditSession.OpResult r = es.deleteLayer("l-nonexistent");
            assertEquals("LAYER_NOT_FOUND", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void deleteActiveLayerTransfersActiveLayerIdAndEmitsPatch() {
            EditSession es = newSession();
            String l0 = es.state().activeLayerId();
            EditSession.OpResult.Ok created = (EditSession.OpResult.Ok) es.createLayer("Top", null);
            String topId = layerIdOfPatch(created);

            EditSession.OpResult.Ok r = (EditSession.OpResult.Ok) es.deleteLayer(l0);
            assertEquals(topId, es.state().activeLayerId());
            assertEquals(2, r.patch().ops().size());
            assertEquals("/activeLayerId", r.patch().ops().get(1).path());
            assertEquals(topId, r.patch().ops().get(1).value());
        }

        @Test
        void updateChangesFields() {
            EditSession es = newSession();
            String l0 = es.state().activeLayerId();
            assertInstanceOf(EditSession.OpResult.Ok.class,
                    es.updateLayer(l0, Map.of("name", "BG", "visible", false, "opacity", 0.5)));
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
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void updateUnknownBlendModeRejected() {
            EditSession es = newSession();
            String l0 = es.state().activeLayerId();
            EditSession.OpResult r = es.updateLayer(l0, Map.of("blendMode", "darken"));
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void updateUnknownLayerRejected() {
            EditSession es = newSession();
            EditSession.OpResult r = es.updateLayer("l-nonexistent", Map.of("name", "x"));
            assertEquals("LAYER_NOT_FOUND", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void updateAcceptsValidColorTag() {
            EditSession es = newSession();
            String l0 = es.state().activeLayerId();
            EditSession.OpResult.Ok r = (EditSession.OpResult.Ok)
                    es.updateLayer(l0, Map.of("colorTag", "red"));
            assertEquals("red", es.state().layers().get(0).colorTag());
            assertEquals("/layers/0/colorTag", r.patch().ops().get(0).path());
            assertEquals("red", r.patch().ops().get(0).value());
        }

        @Test
        void updateColorTagToEmptyStringClears() {
            EditSession es = newSession();
            String l0 = es.state().activeLayerId();
            es.updateLayer(l0, Map.of("colorTag", "blue"));
            es.updateLayer(l0, Map.of("colorTag", ""));
            assertNotNull(es.state().layers().get(0));
            assertNull(es.state().layers().get(0).colorTag(), "empty string should clear colorTag");
        }

        @Test
        void updateColorTagRejectsUnknownValue() {
            EditSession es = newSession();
            String l0 = es.state().activeLayerId();
            EditSession.OpResult r = es.updateLayer(l0, Map.of("colorTag", "fuchsia"));
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void layerDefaultsColorTagToNull() {
            EditSession es = newSession();
            assertNull(es.state().layers().get(0).colorTag(), "fresh layer should have no colorTag");
        }

        @Test
        void reorderMovesLayer() {
            EditSession es = newSession();
            String l0 = es.state().activeLayerId();
            es.createLayer("Top", null);
            assertInstanceOf(EditSession.OpResult.Ok.class, es.reorderLayer(l0, 1));
            assertEquals(l0, es.state().layers().get(1).id());
            assertEquals("Top", es.state().layers().get(0).name());
        }

        @Test
        void duplicateClonesElementsWithNewIds() {
            EditSession es = newSession();
            String l0 = es.state().activeLayerId();
            String e1 = addText(es, "hi");
            assertInstanceOf(EditSession.OpResult.Ok.class, es.duplicateLayer(l0));
            assertEquals(2, es.state().layers().size());
            Layer copy = es.state().layers().get(1);
            assertEquals(1, copy.elements().size());
            assertNotEquals(e1, copy.elements().get(0).id());
            TextElement copied = (TextElement) copy.elements().get(0);
            assertEquals("hi", copied.text());
        }

        @Test
        void duplicatedLayerIsUnlocked() {
            EditSession es = newSession();
            String l0 = es.state().activeLayerId();
            es.updateLayer(l0, Map.of("locked", true));
            assertInstanceOf(EditSession.OpResult.Ok.class, es.duplicateLayer(l0));
            Layer copy = es.state().layers().get(1);
            assertFalse(copy.locked(), "duplicated layer should default to unlocked");
        }

        @Test
        void duplicateLayer_keepsColorTag() {
            EditSession es = newSession();
            String srcLayer = es.state().activeLayerId();
            assertInstanceOf(EditSession.OpResult.Ok.class,
                    es.updateLayer(srcLayer, Map.of("colorTag", "mauve")));

            assertInstanceOf(EditSession.OpResult.Ok.class, es.duplicateLayer(srcLayer));
            List<Layer> layers = es.state().layers();
            assertEquals(2, layers.size());
            assertEquals("mauve", layers.get(1).colorTag(), "复制层应保留颜色标签");
        }

        @Test
        void setActiveSwitchesActiveLayerId() {
            EditSession es = newSession();
            EditSession.OpResult.Ok created = (EditSession.OpResult.Ok) es.createLayer("Top", null);
            String topId = layerIdOfPatch(created);
            EditSession.OpResult.Ok r = (EditSession.OpResult.Ok) es.setActiveLayer(topId);
            assertEquals(topId, es.state().activeLayerId());
            assertEquals("/activeLayerId", r.patch().ops().get(0).path());
        }

        @Test
        void setActiveDoesNotPushHistory() {
            EditSession es = newSession();
            EditSession.OpResult.Ok created = (EditSession.OpResult.Ok) es.createLayer("Top", null);
            String topId = layerIdOfPatch(created);

            es.setActiveLayer(topId);
            EditSession.OpResult undoR = es.undo();
            assertInstanceOf(EditSession.OpResult.OkSnapshot.class, undoR);
            assertEquals(1, es.state().layers().size(),
                    "undo should revert createLayer; set-active must not push history");
        }

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
            assertNull(es.state().canvas().gridSize());
        }

        @Test
        void gridSizeNegativeRejected() {
            EditSession es = newSession();
            EditSession.OpResult r = es.setGridSize(-1);
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

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
            EditSession.OpResult r = es.setGuides(List.of(Map.of("axis", "z", "position", 100)));
            assertEquals("INVALID_PAYLOAD", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void resizeCanvas_realResizeStillRejected() {
            EditSession es = newSession();
            EditSession.OpResult r = es.resizeCanvas(4, 4);
            assertEquals("POOL_EXHAUSTED",
                    assertInstanceOf(EditSession.OpResult.Error.class, r).code());
            assertNotEquals(4, es.state().canvas().widthMaps(), "尺寸不该被改动");
            assertInstanceOf(EditSession.OpResult.Ok.class, es.resizeCanvas(2, 1),
                    "等值 resize 为 no-op 放行");
        }

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
            EditSession.OpResult.Ok created = (EditSession.OpResult.Ok) es.createLayer("Top", null);
            String topId = layerIdOfPatch(created);
            es.deleteLayer(l0);
            assertEquals(topId, es.state().activeLayerId());
            es.undo();
            assertEquals(2, es.state().layers().size());
            assertEquals(l0, es.state().activeLayerId());
        }

        @Test
        void undoAfterMoveElementToLayer() {
            EditSession es = newSession();
            String e = addText(es, "hi");
            EditSession.OpResult.Ok created = (EditSession.OpResult.Ok) es.createLayer("Top", null);
            String topId = layerIdOfPatch(created);
            es.moveElementToLayer(e, topId, null);
            assertEquals(0, es.state().layers().get(0).elements().size());
            assertEquals(1, es.state().layers().get(1).elements().size());

            es.undo();
            assertEquals(1, es.state().layers().get(0).elements().size());
            assertEquals(0, es.state().layers().get(1).elements().size());
        }

        @Test
        void historyMarkUndoLoop_doesNotGrowFutureUnbounded() {
            EditSession es = newSession();
            addText(es, "hi");
            for (int i = 0; i < 200; i++) {
                assertInstanceOf(EditSession.OpResult.Ok.class, es.historyMark("m" + i));
                assertInstanceOf(EditSession.OpResult.OkSnapshot.class, es.undo());
            }
            assertTrue(es.futureDepthForTest() <= HistoryStack.MAX_HISTORY_TIMELINE,
                    "future 栈必须受容量约束，实际深度=" + es.futureDepthForTest());
        }
    }

    // ──────────────────────────────────────────────────────────
    //  锁定层拦截（LAYER_LOCKED）
    // ──────────────────────────────────────────────────────────

    @Nested
    class LockedLayerEnforcement {

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
            assertEquals("LAYER_LOCKED", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void updateInLockedLayerRejected() {
            EditSession es = newSession();
            String eid = addText(es, "hi");
            lockActive(es);
            EditSession.OpResult r = es.updateElement(eid, Map.of("text", "ho"));
            assertEquals("LAYER_LOCKED", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void deleteInLockedLayerRejected() {
            EditSession es = newSession();
            String eid = addText(es, "hi");
            lockActive(es);
            EditSession.OpResult r = es.deleteElement(eid);
            assertEquals("LAYER_LOCKED", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void reorderInLockedLayerRejected() {
            EditSession es = newSession();
            addText(es, "a");
            String bid = addText(es, "b");
            lockActive(es);
            EditSession.OpResult r = es.reorderElement(bid, 0);
            assertEquals("LAYER_LOCKED", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void transformInLockedLayerRejected() {
            EditSession es = newSession();
            String eid = addText(es, "hi");
            lockActive(es);
            EditSession.OpResult r = es.transformElement(eid, 10, 20, null, null, null);
            assertEquals("LAYER_LOCKED", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void moveToLayerFromLockedSourceRejected() {
            EditSession es = newSession();
            String eid = addText(es, "hi");
            EditSession.OpResult.Ok created = (EditSession.OpResult.Ok) es.createLayer("Top", null);
            String topLid = layerIdOfPatch(created);
            lockActive(es);

            EditSession.OpResult r = es.moveElementToLayer(eid, topLid, null);
            assertEquals("LAYER_LOCKED", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void moveToLayerToLockedTargetRejected() {
            EditSession es = newSession();
            String eid = addText(es, "hi");
            EditSession.OpResult.Ok created = (EditSession.OpResult.Ok) es.createLayer("Top", null);
            String topLid = layerIdOfPatch(created);
            es.updateLayer(topLid, Map.of("locked", true));

            EditSession.OpResult r = es.moveElementToLayer(eid, topLid, null);
            assertEquals("LAYER_LOCKED", ((EditSession.OpResult.Error) r).code());
        }

        @Test
        void lockedLayerCanStillBeUnlocked() {
            EditSession es = newSession();
            String lid = es.state().activeLayerId();
            lockActive(es);
            EditSession.OpResult r = es.updateLayer(lid, Map.of("locked", false));
            assertInstanceOf(EditSession.OpResult.Ok.class, r);
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
            es.updateLayer(lid, Map.of("locked", false));
            addText(es, "content");
            lockActive(es);
            EditSession.OpResult.Ok r2 = (EditSession.OpResult.Ok)
                    es.updateLayer(lid, Map.of("visible", true));
            assertNotNull(r2.dirty());
        }
    }

    // ──────────────────────────────────────────────────────────
    //  replaceProject（工程导入 / 模板套用全量替换与回滚）
    // ──────────────────────────────────────────────────────────

    @Nested
    class ReplaceProjectOps {

        private static ProjectState threeLayerState(int w, int h) {
            List<Layer> layers = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                layers.add(new Layer("layer-" + i, "L" + i,
                        true, false, 1.0f, BlendMode.NORMAL, new ArrayList<>()));
            }
            return new ProjectState(new ProjectState.Canvas(w, h), layers, "layer-0", null, null);
        }

        @Test
        void replaceProject_preservesMultipleLayers() {
            EditSession es = newSession();
            assertEquals(1, es.state().layers().size(), "起始应为单层");

            ProjectState imported = threeLayerState(2, 1);
            EditSession.OpResult r = es.replaceProject(imported);

            EditSession.OpResult.OkSnapshot ok = assertInstanceOf(
                    EditSession.OpResult.OkSnapshot.class, r, "应返回结构跳变 snapshot");
            assertNotNull(ok.dirty(), "snapshot 应带 full-canvas dirty");

            ProjectState after = es.state();
            assertEquals(3, after.layers().size(), "导入的 3 层必须保留");
            assertEquals("layer-0", after.activeLayerId(), "采用导入的 activeLayerId");
            assertTrue(after.version() > 0, "version 应已 bump");
            assertEquals(after.version(), ok.version(), "OkSnapshot.version 与 state.version 一致");
        }

        @Test
        void replaceProject_adoptsImportedCanvas() {
            EditSession es = newSession();
            ProjectState imported = new ProjectState(1, 1, "#112233");

            es.replaceProject(imported);

            ProjectState after = es.state();
            assertEquals(1, after.canvas().widthMaps(), "采用导入工程的 canvas 尺寸");
            assertEquals(1, after.canvas().heightMaps());
            assertEquals(new SolidFill("#112233"), after.canvas().background(), "采用导入工程的背景");
        }

        @Test
        void replaceProject_isUndoable() {
            EditSession es = newSession();
            es.replaceProject(threeLayerState(2, 1));
            assertEquals(3, es.state().layers().size());

            EditSession.OpResult undo = es.undo();
            assertInstanceOf(EditSession.OpResult.OkSnapshot.class, undo);
            assertEquals(1, es.state().layers().size(), "undo 应恢复导入前的单层结构");
        }

        @Test
        void undoAfterReplaceProject_restoresTweenFps() {
            EditSession es = newSession();
            assertInstanceOf(EditSession.OpResult.Ok.class, es.setTweenFps(12));

            ProjectState imported = new ProjectState(2, 1);
            imported.tweenFps(48);
            assertInstanceOf(EditSession.OpResult.OkSnapshot.class, es.replaceProject(imported));
            assertEquals(48, es.state().tweenFps());

            assertInstanceOf(EditSession.OpResult.OkSnapshot.class, es.undo());
            assertNotNull(es.state().tweenFps(), "undo 后 tweenFps 应回到导入前");
            assertEquals(12, es.state().tweenFps(), "undo 应将补间帧率一并回滚");
        }
    }
}
