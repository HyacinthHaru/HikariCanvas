package ac.haru.hikaricanvas.build;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 构建期 Font Awesome Free SVG → JSON 矢量包生成器（M26 引入）。
 *
 * <p>用法：</p>
 * <pre>
 *   IconLibraryGenerator &lt;fa-zip&gt; &lt;out-dir&gt;
 * </pre>
 *
 * <p>读 Font Awesome Free Web zip（{@code svgs/solid/*.svg} 等），输出 3 个 JSON：</p>
 * <ul>
 *   <li>{@code <out-dir>/fa-solid.icons.json}</li>
 *   <li>{@code <out-dir>/fa-regular.icons.json}</li>
 *   <li>{@code <out-dir>/fa-brands.icons.json}</li>
 * </ul>
 *
 * <p><b>JSON schema：</b></p>
 * <pre>
 *   {
 *     "pack": "fa-solid",
 *     "version": "6.7.2",
 *     "license": "CC BY 4.0",
 *     "viewBox": "0 0 512 512",
 *     "icons": {
 *       "heart": { "d": "M256,..." },
 *       ...
 *     }
 *   }
 * </pre>
 *
 * <p><b>License：</b> Font Awesome Free 图标本身 CC BY 4.0，要求归属署名。本生成器在 JSON
 * 文件 {@code license} 字段写入 {@code "CC BY 4.0"}；运行期 IconRegistry 加载到 IconPack.license
 * 后由前端 i18n 在 IconPicker 显示 "Font Awesome Free by @fontawesome — CC BY 4.0"。</p>
 *
 * <p>仅内置 FA Free；接别的图标源走独立 generator 段。</p>
 */
public final class IconLibraryGenerator {

    private static final String FA_VERSION = "6.7.2";
    private static final String FA_LICENSE = "CC BY 4.0";
    private static final String FA_VIEWBOX = "0 0 512 512";

    /**
     * FA SVG 取 path d 的 regex。FA Free 每个 SVG 都是单一 path：
     * {@code <svg xmlns="..." viewBox="0 0 512 512"><path d="..."/></svg>}
     */
    private static final Pattern PATH_D_RE =
            Pattern.compile("<path\\b[^>]*?\\sd=([\"'])(.*?)\\1", Pattern.DOTALL);

    /**
     * zip 内 SVG 路径"中间段"标记 → 输出 pack id。Font Awesome Free zip 实际带顶层目录
     * {@code fontawesome-free-6.7.2-web/svgs/<style>/<name>.svg}，所以匹配按"包含
     * /svgs/&lt;style&gt;/"做。
     */
    private static final String[][] PACK_MAPPING = {
            { "/svgs/solid/",   "fa-solid"   },
            { "/svgs/regular/", "fa-regular" },
            { "/svgs/brands/",  "fa-brands"  },
    };

    private IconLibraryGenerator() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: IconLibraryGenerator <fa-zip> <out-dir>");
            System.exit(2);
        }
        Path zipPath = Path.of(args[0]);
        Path outDir = Path.of(args[1]);
        if (!Files.isRegularFile(zipPath)) {
            System.err.println("input zip not found: " + zipPath);
            System.exit(2);
        }
        Files.createDirectories(outDir);

        // 收集每个 pack 的 icons：name → path d。TreeMap 保字母序，便于 git diff 审查。
        java.util.Map<String, TreeMap<String, String>> packs = new java.util.LinkedHashMap<>();
        for (String[] m : PACK_MAPPING) packs.put(m[1], new TreeMap<>());

        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                String matchedPack = null;
                int matchedPrefixEnd = -1;
                if (name.endsWith(".svg")) {
                    for (String[] mp : PACK_MAPPING) {
                        int idx = name.indexOf(mp[0]);
                        if (idx >= 0) {
                            int restStart = idx + mp[0].length();
                            String rest = name.substring(restStart);
                            // 平铺：rest 不应再包含 '/'
                            if (rest.indexOf('/') < 0) {
                                matchedPack = mp[1];
                                matchedPrefixEnd = restStart;
                                break;
                            }
                        }
                    }
                }
                if (matchedPack == null) continue;

                String iconName = name.substring(matchedPrefixEnd,
                        name.length() - ".svg".length());
                // FA 命名已是 [a-z0-9-]+；防御性 trim
                iconName = iconName.toLowerCase(java.util.Locale.ROOT);
                if (iconName.isEmpty()) continue;

                String svg;
                try (InputStream in = zip.getInputStream(entry)) {
                    svg = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                String d = firstPathD(svg);
                if (d == null) {
                    System.err.println("  [skip] no <path d> in " + name);
                    continue;
                }
                packs.get(matchedPack).put(iconName, d);
            }
        }

        int total = 0;
        for (var e : packs.entrySet()) {
            String packId = e.getKey();
            TreeMap<String, String> icons = e.getValue();
            Path out = outDir.resolve(packId + ".icons.json");
            writePackJson(out, packId, FA_VERSION, FA_LICENSE, FA_VIEWBOX, icons);
            System.out.println("wrote " + icons.size() + " icons -> " + out);
            total += icons.size();
        }
        System.out.println("total " + total + " icons across " + packs.size() + " pack(s)");
    }

    private static String firstPathD(String svgContent) {
        Matcher m = PATH_D_RE.matcher(svgContent);
        return m.find() ? m.group(2) : null;
    }

    /**
     * 写出 pack JSON。每个 icon 一行（git diff 友好），手工 JSON 序列化（避免 generator 引
     * Jackson 依赖；palette.json 也是同款手工写）。path d 字符串走 {@link #jsonEscape}。
     */
    private static void writePackJson(Path out, String pack, String version, String license,
                                       String viewBox, TreeMap<String, String> icons) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write("{\n");
            w.write("  \"pack\": " + jsonString(pack) + ",\n");
            w.write("  \"version\": " + jsonString(version) + ",\n");
            w.write("  \"license\": " + jsonString(license) + ",\n");
            w.write("  \"viewBox\": " + jsonString(viewBox) + ",\n");
            w.write("  \"icons\": {\n");
            List<java.util.Map.Entry<String, String>> entries = new ArrayList<>(icons.entrySet());
            for (int i = 0; i < entries.size(); i++) {
                var e = entries.get(i);
                w.write("    " + jsonString(e.getKey()) + ": {\"d\": " + jsonString(e.getValue()) + "}");
                w.write(i < entries.size() - 1 ? ",\n" : "\n");
            }
            w.write("  }\n");
            w.write("}\n");
        } catch (IOException io) {
            throw new UncheckedIOException(io);
        }
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

}
