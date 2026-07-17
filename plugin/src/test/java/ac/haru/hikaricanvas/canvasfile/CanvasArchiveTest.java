package ac.haru.hikaricanvas.canvasfile;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class CanvasArchiveTest {
    private static final CanvasArchive.Limits LIM = new CanvasArchive.Limits(1_000_000, 1_000_000, 2_000_000);

    /** 造一个 zip：entries = 条目名→字节。 */
    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (var e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    @Test
    void unpack_validProject_returnsEntries() throws Exception {
        byte[] z = zip(Map.of(
            "manifest.json", "{}".getBytes(),
            "project.json", "{\"version\":3}".getBytes(),
            "assets/aabbccddeeff0011.png", new byte[]{1, 2, 3}));
        Map<String, byte[]> out = CanvasArchive.unpack(z, LIM);
        assertEquals(Set.of("manifest.json", "project.json", "assets/aabbccddeeff0011.png"), out.keySet());
        assertArrayEquals("{\"version\":3}".getBytes(), out.get("project.json"));
    }

    @Test
    void unpack_zipOverSizeLimit_throws() throws Exception {
        byte[] z = zip(Map.of("project.json", "{}".getBytes()));
        CanvasArchive.Limits tiny = new CanvasArchive.Limits(10, 1_000_000, 2_000_000);
        CanvasImportException ex = assertThrows(CanvasImportException.class, () -> CanvasArchive.unpack(z, tiny));
        assertEquals("IMPORT_ZIP_TOO_LARGE", ex.code());
    }

    @Test
    void unpack_entryExceedsUncompressedLimit_throws() throws Exception {
        byte[] big = new byte[5000];
        byte[] z = zip(Map.of("project.json", big));
        CanvasArchive.Limits lim = new CanvasArchive.Limits(1_000_000, 1000, 2_000_000); // 单条目上限 1000
        CanvasImportException ex = assertThrows(CanvasImportException.class, () -> CanvasArchive.unpack(z, lim));
        assertEquals("IMPORT_ZIP_TOO_LARGE", ex.code());
    }

    @Test
    void unpack_pathTraversalEntry_throws() throws Exception {
        byte[] z = zip(Map.of("../../etc/passwd", "x".getBytes()));
        CanvasImportException ex = assertThrows(CanvasImportException.class, () -> CanvasArchive.unpack(z, LIM));
        assertEquals("IMPORT_BAD_ENTRY", ex.code());
    }

    @Test
    void unpack_absolutePathEntry_throws() throws Exception {
        byte[] z = zip(Map.of("/abs.json", "x".getBytes()));
        assertEquals("IMPORT_BAD_ENTRY",
            assertThrows(CanvasImportException.class, () -> CanvasArchive.unpack(z, LIM)).code());
    }

    @Test
    void unpack_nonWhitelistedEntry_throws() throws Exception {
        byte[] z = zip(Map.of("evil.sh", "rm -rf".getBytes()));
        assertEquals("IMPORT_BAD_ENTRY",
            assertThrows(CanvasImportException.class, () -> CanvasArchive.unpack(z, LIM)).code());
    }

    @Test
    void unpack_corruptZip_throwsMalformed() {
        assertEquals("IMPORT_MALFORMED",
            assertThrows(CanvasImportException.class,
                () -> CanvasArchive.unpack(new byte[]{0, 1, 2, 3}, LIM)).code());
    }
}
