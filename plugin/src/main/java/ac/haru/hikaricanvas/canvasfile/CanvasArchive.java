package ac.haru.hikaricanvas.canvasfile;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** .canvas（zip）流式安全解包：三闸（包/单条目/总）+ 路径校验 + 白名单条目。 */
public final class CanvasArchive {
    public static final Set<String> ALLOWED_TOP =
        Set.of("manifest.json", "project.json", "scripts.json", "thumbnail.png");
    public static final String ASSETS_PREFIX = "assets/";

    /** 字节上限，单位 byte（由 ImportConfig 的 MB × 1024×1024 换算传入）。 */
    public record Limits(long maxZipBytes, long maxEntryBytes, long maxTotalBytes) {}

    private CanvasArchive() {}

    public static Map<String, byte[]> unpack(byte[] zipBytes, Limits limits) throws CanvasImportException {
        if (zipBytes.length > limits.maxZipBytes()) {
            throw new CanvasImportException("IMPORT_ZIP_TOO_LARGE",
                "包大小 " + zipBytes.length + " 超过上限 " + limits.maxZipBytes());
        }
        Map<String, byte[]> out = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (!isSafeEntryName(name)) {
                    throw new CanvasImportException("IMPORT_BAD_ENTRY", "非法条目名: " + name);
                }
                // 流式读 + 边读边计数（不信任 entry.getSize()）
                ByteArrayOutputStream entryOut = new ByteArrayOutputStream();
                long entryLen = 0;
                int n;
                while ((n = zis.read(buf)) > 0) {
                    entryLen += n;
                    total += n;
                    if (entryLen > limits.maxEntryBytes()) {
                        throw new CanvasImportException("IMPORT_ZIP_TOO_LARGE", "条目 " + name + " 解压超限");
                    }
                    if (total > limits.maxTotalBytes()) {
                        throw new CanvasImportException("IMPORT_ZIP_TOO_LARGE", "解压总量超限");
                    }
                    entryOut.write(buf, 0, n);
                }
                out.put(name, entryOut.toByteArray());
            }
        } catch (CanvasImportException e) {
            throw e;
        } catch (IOException e) {
            throw new CanvasImportException("IMPORT_MALFORMED", "无法解析 zip: " + e.getMessage());
        }
        if (!out.containsKey("manifest.json") || !out.containsKey("project.json")) {
            throw new CanvasImportException("IMPORT_MALFORMED", "缺少 manifest.json 或 project.json");
        }
        return out;
    }

    /** 路径穿越 + 绝对路径 + 反斜杠 + 白名单校验。 */
    static boolean isSafeEntryName(String name) {
        if (name == null || name.isBlank()) return false;
        if (name.startsWith("/") || name.contains("\\") || name.contains("..")) return false;
        if (name.contains("\0")) return false;
        if (ALLOWED_TOP.contains(name)) return true;
        // assets/ 下：禁再嵌子目录穿越，仅允许 assets/<file> 或 assets/icons/<file>
        if (name.startsWith(ASSETS_PREFIX) && !name.equals(ASSETS_PREFIX)) return true;
        return false;
    }
}
