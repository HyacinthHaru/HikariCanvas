package moe.hikari.canvas.state;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6-D：{@link EditSession#replaceContent(String, java.util.List)} 行为校验。
 * 这是 {@code template.apply} replace 语义的落地点——清空 + 改背景 + 写入新 elements
 * + 推进 version + push 到 history 栈（让 undo 可回）。
 */
class EditSessionReplaceContentTest {

    private static EditSession session(int wMaps, int hMaps) {
        return new EditSession(new ProjectState(wMaps, hMaps));
    }

    private static TextElement text(String id, int x, int y, String content) {
        return new TextElement(id, x, y, 100, 20, 0, false, true,
                content, "ark_pixel", 12, "#000000", "left",
                0f, 1.2f, false, null);
    }

    private static RectElement rect(String id) {
        return new RectElement(id, 0, 0, 50, 50, 0, false, true,
                "#FF0000", null);
    }

    @Test
    void replacesBackgroundAndElements() {
        EditSession es = session(2, 1);
        // 先放一些旧 element
        es.addElement("text", java.util.Map.of("text", "old"), null);
        long versionBefore = es.state().version();
        int sizeBefore = es.state().elements().size();
        assertTrue(sizeBefore >= 1);

        List<Element> newElements = List.of(
                rect("e-r1"),
                text("e-t1", 10, 10, "new")
        );
        EditSession.OpResult result = es.replaceContent("#112233", newElements);
        EditSession.OpResult.OkSnapshot ok = assertInstanceOf(
                EditSession.OpResult.OkSnapshot.class, result);

        // canvas 背景已替换；保留 widthMaps/heightMaps
        assertEquals("#112233", es.state().canvas().background());
        assertEquals(2, es.state().canvas().widthMaps());
        assertEquals(1, es.state().canvas().heightMaps());

        // elements 已是新列表
        assertEquals(2, es.state().elements().size());
        assertEquals("e-r1", es.state().elements().get(0).id());

        // version 推进
        assertEquals(versionBefore + 1, ok.version());
        assertEquals(versionBefore + 1, es.state().version());

        // dirty = full canvas
        assertNotNull(ok.dirty());
    }

    @Test
    void undoRecoversPreReplaceState() {
        EditSession es = session(2, 1);
        es.addElement("text", java.util.Map.of("text", "before"), null);
        long preVersion = es.state().version();
        int preCount = es.state().elements().size();
        String preBg = es.state().canvas().background();

        es.replaceContent("#445566", List.<Element>of(rect("e-new")));
        // version 又被 undo 推进一次
        EditSession.OpResult undo = es.undo();
        assertInstanceOf(EditSession.OpResult.OkSnapshot.class, undo);
        assertEquals(preBg, es.state().canvas().background());
        assertEquals(preCount, es.state().elements().size());
        // version 单调递增；undo 不会回到 preVersion，但状态等效
        assertNotEquals(preVersion, es.state().version());
    }

    @Test
    void nullBackgroundKeepsPrevious() {
        EditSession es = session(2, 1);
        String origBg = es.state().canvas().background();
        es.replaceContent(null, List.<Element>of());
        assertEquals(origBg, es.state().canvas().background());
        assertEquals(0, es.state().elements().size());
    }

    @Test
    void emptyElementsClears() {
        EditSession es = session(2, 1);
        es.addElement("text", java.util.Map.of("text", "x"), null);
        assertTrue(es.state().elements().size() >= 1);
        es.replaceContent("#FFFFFF", List.<Element>of());
        assertEquals(0, es.state().elements().size());
    }
}
