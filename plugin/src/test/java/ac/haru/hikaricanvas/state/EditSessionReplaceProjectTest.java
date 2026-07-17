package ac.haru.hikaricanvas.state;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.8-A2 Task 9：{@link EditSession#replaceProject(ProjectState)} 行为校验。
 *
 * <p>区别于 {@link EditSession#replaceContent(Fill, java.util.List)}（把内容拍平成单层），
 * {@code replaceProject} 整体替换为导入工程并<b>保留其多层 / 时间轴结构</b>，用于 .canvas 导入。</p>
 *
 * <p>注：plan 蓝图里的 {@code Layer.withId/withName}、{@code ProjectState.withLayers(list, id)}、
 * {@code EditSession.snapshot()} 在真实代码中不存在——真实路径是 {@link Layer} 的 canonical
 * 构造器 + {@link ProjectState#replaceAllLayers(List, String)} + {@link EditSession#state()}。</p>
 */
class EditSessionReplaceProjectTest {

    /** 造一个 layerCount=3 的 ProjectState（用真实 Layer 构造器 + replaceAllLayers）。 */
    private static ProjectState threeLayerState(int w, int h) {
        ProjectState imported = new ProjectState(w, h);
        List<Layer> layers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            layers.add(new Layer("layer-" + i, "L" + i,
                    true, false, 1.0f, BlendMode.NORMAL, new ArrayList<>()));
        }
        imported.replaceAllLayers(layers, "layer-0");
        return imported;
    }

    @Test
    void replaceProject_preservesMultipleLayers() {
        // 起始：单层空工程（2x1）
        EditSession es = new EditSession(new ProjectState(2, 1));
        assertEquals(1, es.state().layers().size(), "起始应为单层");

        // 导入：3 层工程（同尺寸）
        ProjectState imported = threeLayerState(2, 1);
        EditSession.OpResult r = es.replaceProject(imported);

        EditSession.OpResult.OkSnapshot ok = assertInstanceOf(
                EditSession.OpResult.OkSnapshot.class, r, "应返回结构跳变 snapshot");
        assertNotNull(ok.dirty(), "snapshot 应带 full-canvas dirty");

        ProjectState after = es.state();
        assertEquals(3, after.layers().size(), "导入的 3 层必须保留，不被拍平");
        assertEquals("layer-0", after.activeLayerId(), "采用导入的 activeLayerId");
        assertTrue(after.version() > 0, "version 应已 bump");
        assertEquals(after.version(), ok.version(), "OkSnapshot.version 与 state.version 一致");
    }

    @Test
    void replaceProject_adoptsImportedCanvas() {
        // 起始 2x1 默认背景；导入 1x1、背景 #112233
        EditSession es = new EditSession(new ProjectState(2, 1));
        ProjectState imported = new ProjectState(1, 1, "#112233");

        es.replaceProject(imported);

        ProjectState after = es.state();
        assertEquals(1, after.canvas().widthMaps(), "采用导入工程的 canvas 尺寸");
        assertEquals(1, after.canvas().heightMaps());
        assertEquals(new SolidFill("#112233"), after.canvas().background(), "采用导入工程的背景");
    }

    @Test
    void replaceProject_isUndoable() {
        EditSession es = new EditSession(new ProjectState(2, 1));
        es.replaceProject(threeLayerState(2, 1));
        assertEquals(3, es.state().layers().size());

        EditSession.OpResult undo = es.undo();
        assertInstanceOf(EditSession.OpResult.OkSnapshot.class, undo);
        assertEquals(1, es.state().layers().size(), "undo 应恢复导入前的单层结构");
    }
}
