package moe.hikari.canvas.render;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 字体注册表，契约见 {@code docs/rendering.md §2.2 / §2.3}。
 *
 * <h2>加载顺序（后者覆盖前者）</h2>
 * <ol>
 *   <li><b>内置字体</b>：jar 内 {@code /fonts/SourceHanSansSC-Regular.otf} 与
 *       {@code /fonts/ark-pixel-12px-monospaced-zh_cn.ttf}（由 M4-T3 Gradle
 *       {@code downloadFonts} 任务构建期拉取入 jar）</li>
 *   <li><b>外部字体</b>：{@code plugins/HikariCanvas/fonts/*.ttf}（{@code .otf}），
 *       扫描时 fileName 去扩展名作为 {@code fontId}；同名覆盖内置</li>
 * </ol>
 *
 * <h2>字号语义（rendering.md §2.4）</h2>
 * 字号单位 = 像素；{@link Font#deriveFont(float)} 的参数即字号。
 * 像素字体（{@link Metadata#pixelated()}）若用户字号非 {@code nativeSize} 的整数倍，
 * 由 CanvasCompositor 在 M4-T4 阶段选最近邻缩放模式（M4-T3 这里只管加载）。
 *
 * <h2>线程</h2>
 * 加载期有写入，稳态后全局只读；{@link #get} / {@link #getOrDefault} 纯读线程安全。
 */
public final class FontRegistry {

    /** 默认 fontId（TextElement.fontId 为 null 或未知时回退到这个）。 */
    public static final String DEFAULT_FONT_ID = "ark_pixel";

    /** 内置字体清单：fontId → (classpath 路径, 元数据)。顺序保留便于 log。 */
    private static final Map<String, BuiltIn> BUILT_IN = new LinkedHashMap<>();

    static {
        BUILT_IN.put("ark_pixel", new BuiltIn(
                "/fonts/ark-pixel-12px-monospaced-zh_cn.ttf",
                new Metadata("Ark Pixel 12px", true, 12)));
        BUILT_IN.put("source_han_sans", new BuiltIn(
                "/fonts/SourceHanSansSC-Regular.otf",
                new Metadata("思源黑体 SC Regular", false, 0)));
    }

    private final Logger log;
    private final Map<String, Registered> fonts = new HashMap<>();

    public FontRegistry(Logger log) {
        this.log = log;
    }

    /** 从 classpath 加载所有内置字体；缺失字体 log WARN，不抛异常（fallback 到存在的）。 */
    public void loadBuiltIn() {
        for (var e : BUILT_IN.entrySet()) {
            String id = e.getKey();
            BuiltIn bi = e.getValue();
            try (InputStream in = FontRegistry.class.getResourceAsStream(bi.resourcePath)) {
                if (in == null) {
                    log.warning("FontRegistry: built-in font missing from classpath: "
                            + id + " (" + bi.resourcePath + ")；确认 ./gradlew downloadFonts 已跑过");
                    continue;
                }
                int format = bi.resourcePath.endsWith(".otf") ? Font.TRUETYPE_FONT : Font.TRUETYPE_FONT;
                // AWT 对 TTF/OTF 统一用 TRUETYPE_FONT 常量；OpenType 是 TrueType 的超集
                Font font = Font.createFont(format, in);
                fonts.put(id, new Registered(id, font, bi.metadata, "classpath:" + bi.resourcePath));
                log.info("FontRegistry: loaded built-in '" + id + "' ("
                        + bi.metadata.displayName + ", pixelated=" + bi.metadata.pixelated + ")");
            } catch (IOException | FontFormatException ex) {
                log.log(Level.WARNING, "FontRegistry: failed to load built-in " + id, ex);
            }
        }
    }

    /**
     * 扫描给定目录加载外部字体；fileName 去扩展名作为 fontId。
     * 目录不存在或扫描失败不抛异常，仅 log。
     */
    public void loadExternal(Path fontsDir) {
        if (!Files.isDirectory(fontsDir)) {
            log.info("FontRegistry: external fonts dir not present: " + fontsDir);
            return;
        }
        try (var stream = Files.list(fontsDir)) {
            stream.forEach(path -> {
                String fileName = path.getFileName().toString();
                String lower = fileName.toLowerCase();
                if (!lower.endsWith(".ttf") && !lower.endsWith(".otf")) return;
                String id = fileName.substring(0, fileName.lastIndexOf('.'));
                try (InputStream in = Files.newInputStream(path)) {
                    Font font = Font.createFont(Font.TRUETYPE_FONT, in);
                    // 外部字体默认 pixelated=false；M7 接入 config.yml 后可按文件名前缀或配置项区分
                    fonts.put(id, new Registered(id, font,
                            new Metadata(id, false, 0),
                            path.toAbsolutePath().toString()));
                    log.info("FontRegistry: loaded external '" + id + "' (" + path + ")");
                } catch (IOException | FontFormatException ex) {
                    log.log(Level.WARNING, "FontRegistry: failed to load external " + path, ex);
                }
            });
        } catch (IOException ex) {
            log.log(Level.WARNING, "FontRegistry: scan failed for " + fontsDir, ex);
        }
    }

    /** 返回指定 id 的字体；不存在返 null。 */
    public Registered get(String fontId) {
        return fontId == null ? null : fonts.get(fontId);
    }

    /**
     * 返回指定 id 的字体；不存在则返 {@link #DEFAULT_FONT_ID}。
     * 如果连默认也没（例如 downloadFonts 没跑成功），返 null——调用方应检查并走系统字体 fallback。
     */
    public Registered getOrDefault(String fontId) {
        Registered r = get(fontId);
        if (r != null) return r;
        return fonts.get(DEFAULT_FONT_ID);
    }

    public int size() {
        return fonts.size();
    }

    public Map<String, Registered> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(fonts));
    }

    // ---------- 数据类型 ----------

    public record Metadata(String displayName, boolean pixelated, int nativeSize) {}

    /** 已注册的字体：fontId + AWT Font + 元数据 + 来源（调试用）。 */
    public record Registered(String fontId, Font font, Metadata metadata, String source) {

        /**
         * 按像素字号派生一个具体字号 Font 实例。像素字体用户字号非 native 整数倍时，
         * 在 M4-T4 CanvasCompositor 里走最近邻缩放；这里只负责 {@link Font#deriveFont(float)}。
         */
        public Font derive(float pixelSize) {
            return font.deriveFont(pixelSize);
        }
    }

    private record BuiltIn(String resourcePath, Metadata metadata) {}
}
