package moe.hikari.canvas.build;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.TreeMap;

/**
 * M20-T1：构建期从给定 TTF / OTF 字体导出 BMP 范围（U+0020 ~ U+FFFF）的 glyph advance 表。
 *
 * <p>替代 M5-D2 的 canonicalCharWidth 简化算法（ASCII=0.5×fontSize, CJK=1×fontSize）。
 * 简化算法对非等宽字体（思源黑体）严重不匹配：'M'/'W' 真实 ~0.7-0.8 × fontSize → 重叠；
 * 'i'/'l' ~0.2-0.3 × → 大空隙。本表给运行时真实 per-glyph advance。</p>
 *
 * <p>由 Gradle 任务 {@code generateGlyphMetrics} 调用；只读 AWT Font API，
 * 不依赖 Bukkit / Paper。</p>
 *
 * <p>BASE_SIZE = 12 与 PaletteLut 习惯一致；运行期按 {@code fontSize / 12} 等比缩放。</p>
 *
 * <p>输出 JSON（紧凑、无空格；典型字体 ~10k entries ≈ 50KB）：</p>
 * <pre>
 * {"fontId":"source_han_sans","baseSize":12,"ascent":11,"descent":3,
 *  "advances":{"32":3,"33":4,"34":5,...}}
 * </pre>
 *
 * <p>用法：{@code java -cp ... moe.hikari.canvas.build.GlyphMetricsGenerator
 *  <font-path> <font-id> <output-path>}</p>
 */
public final class GlyphMetricsGenerator {

    /** 与 PaletteLut 同基准；运行期按 fontSize/BASE_SIZE 等比缩放 advance。 */
    private static final int BASE_SIZE = 12;

    /** BMP 上限：U+0000 ~ U+FFFF。U+10000+ 涉及 surrogate pair，留 M20.x。 */
    private static final int BMP_END = 0xFFFF;

    private GlyphMetricsGenerator() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.err.println("usage: GlyphMetricsGenerator <font-path> <font-id> <output-path>");
            System.exit(2);
        }
        Path fontPath = Paths.get(args[0]);
        String fontId = args[1];
        Path outPath = Paths.get(args[2]);

        // 1. 加载字体。Font.TRUETYPE_FONT 同时支持 .ttf 和 .otf（Java AWT 内部按容器判断）。
        Font baseFont;
        try {
            baseFont = Font.createFont(Font.TRUETYPE_FONT, fontPath.toFile())
                    .deriveFont((float) BASE_SIZE);
        } catch (Exception e) {
            throw new IOException("failed to load font: " + fontPath, e);
        }

        // 2. FontMetrics 需要 Graphics2D context；用 1×1 BufferedImage 拿一个。
        //    注意：渲染期 antialiasing OFF（rendering.md §2.1 禁抗锯齿），
        //    但 charWidth 不受 RenderingHints 影响，只看字体 advance metric，无需配 hints。
        BufferedImage dummy = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dummy.createGraphics();
        try {
            g.setFont(baseFont);
            FontMetrics fm = g.getFontMetrics();

            // 3. 扫所有 BMP codepoint。canDisplay(cp) 比 charWidth 更准：
            //    charWidth 对缺字有时返默认 fallback 宽度而非 0，会污染表。
            //    O(N) ≈ 65k 次 canDisplay + charWidth；本机 ~200ms 级，构建期可忍。
            Map<Integer, Integer> advances = new TreeMap<>();
            int included = 0;
            int missing = 0;
            for (int cp = 0x20; cp <= BMP_END; cp++) {
                if (!baseFont.canDisplay(cp)) {
                    missing++;
                    continue;
                }
                int w = fm.charWidth(cp);
                if (w <= 0) {
                    missing++;
                    continue;
                }
                advances.put(cp, w);
                included++;
            }

            int ascent = fm.getAscent();
            int descent = fm.getDescent();

            // 4. 写紧凑 JSON。advance 表可能 ~10k entries，pretty 会到 MB 级；
            //    紧凑 ~50KB，diff 不友好但 generator 产物本就不入仓。
            Path parent = outPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            StringBuilder sb = new StringBuilder(advances.size() * 12);
            sb.append("{\"fontId\":\"").append(fontId).append("\",")
                    .append("\"baseSize\":").append(BASE_SIZE).append(",")
                    .append("\"ascent\":").append(ascent).append(",")
                    .append("\"descent\":").append(descent).append(",")
                    .append("\"advances\":{");
            boolean first = true;
            for (Map.Entry<Integer, Integer> e : advances.entrySet()) {
                if (!first) {
                    sb.append(",");
                }
                sb.append("\"").append(e.getKey()).append("\":").append(e.getValue());
                first = false;
            }
            sb.append("}}");
            Files.writeString(outPath, sb.toString());

            System.out.println("font=" + fontId
                    + " base=" + BASE_SIZE + "px"
                    + " asc=" + ascent
                    + " desc=" + descent
                    + " glyphs=" + included + "/" + (included + missing)
                    + " -> " + outPath);
        } finally {
            g.dispose();
        }
    }
}
