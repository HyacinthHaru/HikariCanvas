package ac.haru.hikaricanvas.state;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 必修 Bug 批的编辑会话侧守卫：撤销栈无界增长、换图绕配额、广播客户端原值、
 * fontId 无字符集校验、复制图层丢颜色标签、快照漏 tweenFps。
 *
 * <p>每条都写清了「回退成旧实现会怎么红」，方便日后做变异测试确认守卫不是摆设。</p>
 */
class EditSessionHardeningTest {

    private static EditSession newSession() {
        return new EditSession(new ProjectState(2, 1));
    }

    private static String addText(EditSession es, String content) {
        EditSession.OpResult.Ok ok = (EditSession.OpResult.Ok)
                es.addElement("text", Map.of("text", content), null, null);
        return ((Map<?, ?>) ok.patch().ops().get(0).value()).get("id").toString();
    }

    // ---------- future 栈上限（history.mark + undo 循环） ----------

    /**
     * {@code history.mark → undo} 循环：past 一进一出净零，future 每轮 +1。
     * future 以前完全没有上限（类注释的理由「每次 commit 都会清空 future」对 mark 不成立），
     * 每条又是整棵 layers 树的深拷贝 —— 单会话内可以一直涨到会话结束才释放。
     *
     * <p>去掉 {@code undo()} 里的 trimFuture 调用，本用例立刻红（深度会到 200）。</p>
     */
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

    // ---------- element.update 改 image source 要过单墙配额 ----------

    private static String addImage(EditSession es, String source) {
        EditSession.OpResult r = es.addElement("image",
                Map.of("source", source, "w", 32, "h", 32), null, null);
        EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class, r,
                "addElement(image, " + source + ") 应成功，实际 " + r);
        return ((Map<?, ?>) ok.patch().ops().get(0).value()).get("id").toString();
    }

    /**
     * 突破路径：上限 2 张，先用「复用已有 hash」合法地塞进第 3 个 image 元素
     * （去重数仍是 2，addElement 放行），再把它 update 成一个新 hash —— 去重数就变 3 了。
     * 以前 applyImagePatch 的 source 分支只查格式不查配额，这条路完全敞开
     * （内容寻址是全服共享的，引用别人已上传的文件即可，不需要自己传）。
     */
    @Test
    void elementUpdate_changingImageSource_isCountedAgainstPerWallQuota() {
        EditSession es = newSession();
        es.setMaxImagesPerWall(2);
        addImage(es, "a".repeat(16));
        addImage(es, "b".repeat(16));
        String third = addImage(es, "a".repeat(16));   // 复用已有 hash，去重数仍是 2

        EditSession.OpResult r = es.updateElement(third, Map.of("source", "c".repeat(16)));
        EditSession.OpResult.Error err = assertInstanceOf(EditSession.OpResult.Error.class, r,
                "换成第三个 hash 会让去重数变 3，应被单墙配额拒，实际 " + r);
        assertEquals("QUOTA_PER_WALL", err.code());
    }

    /** 换回工程里已经引用过的 hash 不增加去重数 → 即便卡在上限也必须放行。 */
    @Test
    void elementUpdate_reusingExistingSource_isAllowedAtQuota() {
        EditSession es = newSession();
        es.setMaxImagesPerWall(2);
        String first = addImage(es, "a".repeat(16));
        addImage(es, "b".repeat(16));

        assertInstanceOf(EditSession.OpResult.Ok.class,
                es.updateElement(first, Map.of("source", "b".repeat(16))),
                "复用已引用文件不增加去重数，不该被拒");
    }

    /**
     * 纯替换（旧 source 只有这一个元素在引用）在恰好卡满时也必须放行 ——
     * 统计要排除被改的元素自己，否则会误拒一次不增加总数的操作。
     */
    @Test
    void elementUpdate_swappingSoleReference_isAllowedAtQuota() {
        EditSession es = newSession();
        es.setMaxImagesPerWall(1);
        String only = addImage(es, "a".repeat(16));
        assertInstanceOf(EditSession.OpResult.Ok.class,
                es.updateElement(only, Map.of("source", "d".repeat(16))),
                "旧 source 换掉后就没人引用了，总数不变，不该被拒");
    }

    // ---------- element.update 广播规范化后的值 ----------

    /**
     * 客户端发 {@code x: 12.9}，权威态存的是 intValue 截断后的 12；
     * 广播必须回 12 而不是 12.9，否则发送方镜像从这一刻起就与权威态不一致。
     */
    @Test
    void elementUpdate_broadcastsNormalizedValues_notClientRaw() {
        EditSession es = newSession();
        String id = addText(es, "hi");

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("x", 12.9);
        patch.put("blendMode", "MULTIPLY");
        EditSession.OpResult.Ok ok = assertInstanceOf(EditSession.OpResult.Ok.class,
                es.updateElement(id, patch));

        Map<String, Object> byField = new LinkedHashMap<>();
        for (PatchOp op : ok.patch().ops()) {
            byField.put(op.path().substring(op.path().lastIndexOf('/') + 1), op.value());
        }
        assertEquals(12, ((Number) byField.get("x")).intValue(),
                "x 应广播服务端截断后的 12，不是客户端原值 12.9");
        assertEquals("multiply", byField.get("blendMode"),
                "blendMode 应广播小写归一后的 wire 值");
    }

    // ---------- fontId 字符集 ----------

    /**
     * fontId 没有字符集校验时，任意客户端串会经 {@code FontMetricsTable} 的
     * static map 永久驻留（删元素也不回收），且与 HTTP 侧的 {@code [a-zA-Z0-9_-]+}
     * 白名单口径不一致。
     */
    @Test
    void fontId_rejectsIllegalCharset_onCreateAndUpdate() {
        EditSession es = newSession();

        EditSession.OpResult bad = es.addElement("text",
                Map.of("text", "hi", "fontId", "../../etc/passwd"), null, null);
        assertEquals("INVALID_PAYLOAD",
                assertInstanceOf(EditSession.OpResult.Error.class, bad).code());

        String id = addText(es, "hi");
        EditSession.OpResult badPatch = es.updateElement(id, Map.of("fontId", "字体 name!"));
        assertEquals("INVALID_PAYLOAD",
                assertInstanceOf(EditSession.OpResult.Error.class, badPatch).code());

        assertInstanceOf(EditSession.OpResult.Ok.class,
                es.updateElement(id, Map.of("fontId", "source_han_sans")),
                "合法 id 必须照常通过");
    }

    @Test
    void fontId_rejectsOverlongId() {
        EditSession es = newSession();
        EditSession.OpResult bad = es.addElement("text",
                Map.of("text", "hi", "fontId", "a".repeat(65)), null, null);
        assertEquals("INVALID_PAYLOAD",
                assertInstanceOf(EditSession.OpResult.Error.class, bad).code());
    }

    // ---------- layer.duplicate 保留 colorTag ----------

    @Test
    void duplicateLayer_keepsColorTag() {
        EditSession es = newSession();
        String srcLayer = es.state().activeLayerId();
        assertInstanceOf(EditSession.OpResult.Ok.class,
                es.updateLayer(srcLayer, Map.of("colorTag", "mauve")));

        assertInstanceOf(EditSession.OpResult.Ok.class, es.duplicateLayer(srcLayer));
        List<Layer> layers = es.state().layers();
        assertEquals(2, layers.size());
        assertEquals("mauve", layers.get(1).colorTag(),
                "复制层必须带上颜色标签（7 参兼容构造器会把它代成 null）");
    }

    // ---------- 快照含 tweenFps ----------

    /**
     * {@code replaceProject}（导入 / 套模板）会改 tweenFps 并压快照。
     * 快照不带它的话，导入后按撤销就是「元素全回去了、帧率还留在导入文件那一档」的半回滚。
     */
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
        assertEquals(12, es.state().tweenFps(),
                "undo 必须把补间帧率一起回滚，否则是半回滚");
    }

    // ---------- canvas.resize 仍是未实装 stub（协议文档已标注） ----------

    @Test
    void resizeCanvas_realResizeStillRejected() {
        EditSession es = newSession();
        EditSession.OpResult r = es.resizeCanvas(4, 4);
        assertEquals("POOL_EXHAUSTED",
                assertInstanceOf(EditSession.OpResult.Error.class, r).code());
        assertFalse(es.state().canvas().widthMaps() == 4, "尺寸不该被改动");
        assertInstanceOf(EditSession.OpResult.Ok.class, es.resizeCanvas(2, 1),
                "等值 resize 仍是 no-op 放行");
    }
}
