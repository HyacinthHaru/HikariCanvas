package moe.hikari.canvas.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * M20-P2：per-font 字符 advance 查找表。
 *
 * <p>取代 {@link TextLayout#canonicalCharWidth} 简化模型，提供基于字体真实 metrics 的
 * 双端一致 advance。表由构建期 {@code generateGlyphMetrics} 生成，jar 路径
 * {@code /fonts/{fontId}.metrics.json}（baseSize=12 像素）。</p>
 *
 * <p>运行时 advance = round(baseAdvance × fontSize / baseSize)。缺字 / 未加载字体 / 表加载失败
 * 时 fallback 到 {@link TextLayout#canonicalCharWidth}。</p>
 *
 * <p>线程安全：tables 用 ConcurrentHashMap；load 是幂等 putIfAbsent。</p>
 */
public final class FontMetricsTable {

    private static final Logger log = Logger.getLogger(FontMetricsTable.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final ConcurrentHashMap<String, Table> tables = new ConcurrentHashMap<>();
    /** 没找到 JSON 的字体记 sentinel，避免重复尝试 IO。 */
    private static final Table MISSING = new Table(0, 0, 0, new int[0]);

    public record Table(int baseSize, int ascent, int descent, int[] advances) {
        /** advances[cp] 是 BMP codepoint 索引（0x0..0xFFFF），缺字为 -1。 */
        public int advance(int codePoint) {
            if (codePoint < 0 || codePoint >= advances.length) return -1;
            return advances[codePoint];
        }
    }

    private FontMetricsTable() {}

    /** 显式预加载（M20-P3 启动期由 HikariCanvas 调；test 可用 main classloader 直接读）。 */
    public static void preload(String fontId) {
        load(fontId);
    }

    /**
     * advance(fontId, codePoint, fontSize)：
     * - 表存在且含该 cp：返回 round(baseAdv × fontSize / baseSize)
     * - 表不存在 / cp 缺：返回 -1（调用方走 fallback）
     */
    public static int advance(String fontId, int codePoint, int fontSize) {
        if (fontId == null) return -1;
        Table t = load(fontId);
        if (t == MISSING || t.baseSize <= 0) return -1;
        int base = t.advance(codePoint);
        if (base <= 0) return -1;
        return Math.round((float) base * fontSize / t.baseSize);
    }

    /** 表加载，幂等。读 classpath /fonts/{fontId}.metrics.json。 */
    private static Table load(String fontId) {
        return tables.computeIfAbsent(fontId, id -> {
            try (InputStream in = FontMetricsTable.class.getResourceAsStream("/fonts/" + id + ".metrics.json")) {
                if (in == null) {
                    log.fine("metrics table missing for font " + id + " (will fallback to canonical)");
                    return MISSING;
                }
                JsonNode root = mapper.readTree(in);
                int baseSize = root.path("baseSize").asInt(12);
                int ascent = root.path("ascent").asInt(0);
                int descent = root.path("descent").asInt(0);
                int[] advances = new int[0x10000];
                java.util.Arrays.fill(advances, -1);
                JsonNode advNode = root.path("advances");
                advNode.fields().forEachRemaining(e -> {
                    try {
                        int cp = Integer.parseInt(e.getKey());
                        if (cp >= 0 && cp < advances.length) {
                            advances[cp] = e.getValue().asInt(-1);
                        }
                    } catch (NumberFormatException ignored) {}
                });
                log.info("loaded metrics for font " + id + " base=" + baseSize + "px asc=" + ascent
                        + " desc=" + descent);
                return new Table(baseSize, ascent, descent, advances);
            } catch (IOException e) {
                log.log(Level.WARNING, "metrics table load failed for font " + id, e);
                return MISSING;
            }
        });
    }

    /** 测试钩子：清缓存。 */
    static void clearCacheForTest() {
        tables.clear();
    }
}
