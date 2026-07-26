package ac.haru.hikaricanvas.canvasfile;

import ac.haru.hikaricanvas.state.ProjectState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProjectMaterializerTest {
    @Test
    void materialize_validV3_returnsState() throws Exception {
        byte[] json = ("{\"version\":3,\"canvas\":{\"widthMaps\":2,\"heightMaps\":1,\"background\":\"#FFFFFF\"},"
            + "\"layers\":[{\"id\":\"l1\",\"name\":\"L\",\"visible\":true,\"locked\":false,\"opacity\":1.0,"
            + "\"blendMode\":\"normal\",\"elements\":[]}],\"activeLayerId\":\"l1\"}").getBytes();
        ProjectState s = ProjectMaterializer.materialize(json, 2, 1);
        assertEquals(2, s.canvas().widthMaps());
        assertEquals(1, s.layers().size());
    }

    @Test
    void materialize_exceedsSessionWall_throwsSizeMismatch() {
        byte[] json = ("{\"version\":3,\"canvas\":{\"widthMaps\":6,\"heightMaps\":2,\"background\":\"#FFFFFF\"},"
            + "\"layers\":[{\"id\":\"l1\",\"name\":\"L\",\"visible\":true,\"locked\":false,\"opacity\":1.0,"
            + "\"blendMode\":\"normal\",\"elements\":[]}],\"activeLayerId\":\"l1\"}").getBytes();
        CanvasImportException ex = assertThrows(CanvasImportException.class, () -> ProjectMaterializer.materialize(json, 2, 1));
        assertEquals("IMPORT_SIZE_MISMATCH", ex.code());
    }

    @Test
    void materialize_garbageJson_throwsMalformed() {
        assertEquals("IMPORT_MALFORMED",
            assertThrows(CanvasImportException.class, () -> ProjectMaterializer.materialize("xx".getBytes(), 4, 4)).code());
    }

    // ---------- 结构数量闸（docs/import-export.md §5.1a） ----------
    // zip 那层只数字节，一份合规的 10MB project.json 照样能塞几万个元素、上千个图层，
    // 而 replaceProject 本身零计数校验，等于绕开 element.add 路径上的全部配额。

    /** 拼一份工程 JSON：{@code layers} 段由调用方给。 */
    private static byte[] project(String layersJson) {
        return ("{\"version\":3,\"canvas\":{\"widthMaps\":4,\"heightMaps\":4,\"background\":\"#FFFFFF\"},"
                + "\"layers\":" + layersJson + ",\"activeLayerId\":\"l0\"}").getBytes();
    }

    private static String layer(String id, String elementsJson) {
        return "{\"id\":\"" + id + "\",\"name\":\"L\",\"visible\":true,\"locked\":false,"
                + "\"opacity\":1.0,\"blendMode\":\"normal\",\"elements\":" + elementsJson + "}";
    }

    private static String imageElement(String id, String source) {
        return "{\"type\":\"image\",\"id\":\"" + id + "\",\"x\":0,\"y\":0,\"w\":8,\"h\":8,"
                + "\"source\":\"" + source + "\"}";
    }

    private static String rectElement(String id) {
        return "{\"type\":\"rect\",\"id\":\"" + id + "\",\"x\":0,\"y\":0,\"w\":8,\"h\":8}";
    }

    @Test
    void materialize_tooManyLayers_throwsMalformed() {
        StringBuilder layers = new StringBuilder("[");
        for (int i = 0; i <= ProjectMaterializer.Limits.DEFAULT_MAX_LAYERS; i++) {
            if (i > 0) layers.append(',');
            layers.append(layer("l" + i, "[]"));
        }
        layers.append(']');
        CanvasImportException ex = assertThrows(CanvasImportException.class,
                () -> ProjectMaterializer.materialize(project(layers.toString()), 4, 4));
        assertEquals("IMPORT_MALFORMED", ex.code());
        assertTrue(ex.getMessage().contains("图层数"), ex.getMessage());
    }

    @Test
    void materialize_tooManyElements_throwsMalformed() {
        StringBuilder els = new StringBuilder("[");
        for (int i = 0; i <= ProjectMaterializer.Limits.DEFAULT_MAX_ELEMENTS; i++) {
            if (i > 0) els.append(',');
            els.append(rectElement("e" + i));
        }
        els.append(']');
        CanvasImportException ex = assertThrows(CanvasImportException.class,
                () -> ProjectMaterializer.materialize(
                        project("[" + layer("l0", els.toString()) + "]"), 4, 4));
        assertEquals("IMPORT_MALFORMED", ex.code());
        assertTrue(ex.getMessage().contains("元素总数"), ex.getMessage());
    }

    /** opacity>1 会让合成算出噪点像素，<0 让整层凭空消失——两头都得挡。 */
    @Test
    void materialize_layerOpacityOutOfRange_throwsMalformed() {
        for (String bad : new String[]{"2.5", "-0.5"}) {
            String l = "{\"id\":\"l0\",\"name\":\"L\",\"visible\":true,\"locked\":false,"
                    + "\"opacity\":" + bad + ",\"blendMode\":\"normal\",\"elements\":[]}";
            CanvasImportException ex = assertThrows(CanvasImportException.class,
                    () -> ProjectMaterializer.materialize(project("[" + l + "]"), 4, 4),
                    "opacity=" + bad + " 应被拒");
            assertTrue(ex.getMessage().contains("opacity"), ex.getMessage());
        }
    }

    @Test
    void materialize_layerNameTooLong_throwsMalformed() {
        String name = "n".repeat(ProjectMaterializer.Limits.DEFAULT_MAX_LAYER_NAME + 1);
        String l = "{\"id\":\"l0\",\"name\":\"" + name + "\",\"visible\":true,\"locked\":false,"
                + "\"opacity\":1.0,\"blendMode\":\"normal\",\"elements\":[]}";
        CanvasImportException ex = assertThrows(CanvasImportException.class,
                () -> ProjectMaterializer.materialize(project("[" + l + "]"), 4, 4));
        assertTrue(ex.getMessage().contains("图层名"), ex.getMessage());
    }

    /** 导入路径此前完全绕过 images.max-per-wall（该闸只在 element.add 上）。 */
    @Test
    void materialize_tooManyDistinctImages_throwsMalformed() {
        String els = "[" + imageElement("e1", "0000000000000001") + ","
                + imageElement("e2", "0000000000000002") + ","
                + imageElement("e3", "0000000000000003") + "]";
        var limits = ProjectMaterializer.Limits.withMaxImageSources(2);
        CanvasImportException ex = assertThrows(CanvasImportException.class,
                () -> ProjectMaterializer.materialize(
                        project("[" + layer("l0", els) + "]"), 4, 4, limits));
        assertTrue(ex.getMessage().contains("引用图片数"), ex.getMessage());
    }

    /** 按 source 去重——同一张图放 100 个元素只算 1 张（与 element.add 的口径一致）。 */
    @Test
    void materialize_sameImageReusedManyTimes_countsOnce() throws Exception {
        StringBuilder els = new StringBuilder("[");
        for (int i = 0; i < 50; i++) {
            if (i > 0) els.append(',');
            els.append(imageElement("e" + i, "00000000000000ff"));
        }
        els.append(']');
        ProjectState s = ProjectMaterializer.materialize(
                project("[" + layer("l0", els.toString()) + "]"), 4, 4,
                ProjectMaterializer.Limits.withMaxImageSources(1));
        assertEquals(50, s.layers().get(0).elements().size());
    }

    @Test
    void materialize_tooManyTimelines_throwsMalformed() {
        StringBuilder tls = new StringBuilder("[");
        for (int i = 0; i <= ProjectMaterializer.Limits.DEFAULT_MAX_TIMELINES; i++) {
            if (i > 0) tls.append(',');
            tls.append("{\"id\":\"tl").append(i).append("\",\"name\":\"T\",\"durationMs\":1000,")
               .append("\"fps\":20,\"loopMode\":\"loop\",\"tracks\":{}}");
        }
        tls.append(']');
        byte[] json = ("{\"version\":3,\"canvas\":{\"widthMaps\":4,\"heightMaps\":4,\"background\":\"#FFFFFF\"},"
                + "\"layers\":[" + layer("l0", "[]") + "],\"activeLayerId\":\"l0\","
                + "\"timelines\":" + tls + "}").getBytes();
        CanvasImportException ex = assertThrows(CanvasImportException.class,
                () -> ProjectMaterializer.materialize(json, 4, 4));
        assertTrue(ex.getMessage().contains("时间轴数"), ex.getMessage());
    }

    @Test
    void materialize_tooManyKeyframesInOneTrack_throwsMalformed() {
        StringBuilder kfs = new StringBuilder("[");
        for (int i = 0; i <= ProjectMaterializer.Limits.DEFAULT_MAX_KEYFRAMES_PER_TRACK; i++) {
            if (i > 0) kfs.append(',');
            kfs.append("{\"timeMs\":").append(i).append(",\"property\":\"x\",\"value\":1}");
        }
        kfs.append(']');
        byte[] json = ("{\"version\":3,\"canvas\":{\"widthMaps\":4,\"heightMaps\":4,\"background\":\"#FFFFFF\"},"
                + "\"layers\":[" + layer("l0", "[]") + "],\"activeLayerId\":\"l0\","
                + "\"timelines\":[{\"id\":\"tl0\",\"name\":\"T\",\"durationMs\":1000,\"fps\":20,"
                + "\"loopMode\":\"loop\",\"tracks\":{\"e1\":" + kfs + "}}]}").getBytes();
        CanvasImportException ex = assertThrows(CanvasImportException.class,
                () -> ProjectMaterializer.materialize(json, 4, 4));
        assertTrue(ex.getMessage().contains("单轨关键帧数"), ex.getMessage());
    }

    /** 正常规模的工程一路放行——闸不能误伤。 */
    @Test
    void materialize_normalSizedProject_passes() throws Exception {
        String els = "[" + rectElement("e1") + "," + imageElement("e2", "00000000000000ab") + "]";
        ProjectState s = ProjectMaterializer.materialize(
                project("[" + layer("l0", els) + "," + layer("l1", "[]") + "]"), 4, 4,
                ProjectMaterializer.Limits.withMaxImageSources(16));
        assertEquals(2, s.layers().size());
    }
}
