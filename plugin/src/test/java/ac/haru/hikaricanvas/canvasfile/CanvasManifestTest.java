package ac.haru.hikaricanvas.canvasfile;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CanvasManifestTest {
    @Test
    void parse_validProjectManifest() throws Exception {
        byte[] json = "{\"spec\":1,\"kind\":\"project\",\"created_at\":123,\"wall\":{\"width\":2,\"height\":1}}".getBytes();
        CanvasManifest m = CanvasManifest.parse(json, 1);
        assertEquals(1, m.spec());
        assertEquals("project", m.kind());
        assertEquals(2, m.wallWidth());
        assertEquals(1, m.wallHeight());
    }

    @Test
    void parse_specAboveMax_throwsUnsupported() {
        byte[] json = "{\"spec\":99,\"kind\":\"project\",\"wall\":{\"width\":1,\"height\":1}}".getBytes();
        CanvasImportException ex = assertThrows(CanvasImportException.class, () -> CanvasManifest.parse(json, 1));
        assertEquals("IMPORT_SPEC_UNSUPPORTED", ex.code());
    }

    @Test
    void parse_packManifest_accepted() throws Exception {
        // template-pack.md D1：kind=pack 是模板包，与 project 同走导入管线。
        byte[] json = "{\"spec\":1,\"kind\":\"pack\",\"wall\":{\"width\":1,\"height\":1}}".getBytes();
        CanvasManifest m = CanvasManifest.parse(json, 1);
        assertEquals("pack", m.kind());
    }

    @Test
    void parse_unknownKind_throwsMalformed() {
        byte[] json = "{\"spec\":1,\"kind\":\"widget\",\"wall\":{\"width\":1,\"height\":1}}".getBytes();
        assertEquals("IMPORT_MALFORMED",
            assertThrows(CanvasImportException.class, () -> CanvasManifest.parse(json, 1)).code());
    }

    @Test
    void parse_garbage_throwsMalformed() {
        assertEquals("IMPORT_MALFORMED",
            assertThrows(CanvasImportException.class, () -> CanvasManifest.parse("not json".getBytes(), 1)).code());
    }
}
