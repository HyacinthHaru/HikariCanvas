package moe.hikari.canvas.canvasfile;

import moe.hikari.canvas.state.ProjectState;
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
}
