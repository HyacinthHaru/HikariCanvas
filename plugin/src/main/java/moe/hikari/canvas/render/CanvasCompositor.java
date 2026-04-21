package moe.hikari.canvas.render;

import moe.hikari.canvas.state.Element;
import moe.hikari.canvas.state.ProjectState;
import moe.hikari.canvas.state.RectElement;
import moe.hikari.canvas.state.Stroke;
import moe.hikari.canvas.state.TextElement;
import org.bukkit.map.MapPalette;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 {@link ProjectState} 的 elements 光栅化成指定 mapIndex 的 128×128 palette 像素。
 * 契约对应 {@code docs/architecture.md §5 实时投影管线}。
 *
 * <p><b>坐标系：</b> 画布 (0,0) = 左上；mapIndex 按 {@code FrameDeployer.slotIndex
 * = row * widthMaps + col}（{@code row=0} 最上排、{@code col=0} 最左）。
 * {@code mapIndex} 的画布 offset：{@code (col*128, row*128)}。</p>
 *
 * <p><b>M3-T7 简化：</b></p>
 * <ul>
 *   <li>{@code rotation != 0} 按 {@code 0} 渲染（log WARN 一次）；真 rotation 留 M4</li>
 *   <li>text wrap 不实装，单行渲染；超出画布像素自然截掉</li>
 *   <li>align 只基于元素自身 {@code w}；{@code fontId} 只识别 {@code bitmap}（默认）</li>
 *   <li>fontSize 映射：scale = max(1, round(fontSize / 7.0))，即 7→1×，14→2×，21→3×</li>
 * </ul>
 *
 * <p><b>调色板：</b> 用 Bukkit {@link MapPalette#matchColor}（256 色全量）。
 * 同色命中缓存避免每次查表。</p>
 *
 * <p><b>线程：</b> 纯函数，无可变状态（调色板缓存 {@code ConcurrentMap}-like 读）。</p>
 */
public final class CanvasCompositor {

    public static final int MAP_SIZE = 128;

    private static final Pattern HEX_RE = Pattern.compile("^#([0-9A-Fa-f]{6})([0-9A-Fa-f]{2})?$");

    private final Logger log;
    private final Map<String, Byte> paletteCache = new HashMap<>();
    private boolean rotationWarned = false;

    public CanvasCompositor(Logger log) {
        this.log = log;
    }

    /**
     * 渲染单张 map。{@code mapIndex} 不在 {@code [0, widthMaps*heightMaps)}
     * 时抛 {@link IllegalArgumentException}。
     */
    public byte[] compose(ProjectState state, int mapIndex) {
        ProjectState.Canvas canvas = state.canvas();
        int widthMaps = canvas.widthMaps();
        int heightMaps = canvas.heightMaps();
        int total = widthMaps * heightMaps;
        if (mapIndex < 0 || mapIndex >= total) {
            throw new IllegalArgumentException(
                    "mapIndex " + mapIndex + " out of [0," + total + ")");
        }
        int mapCol = mapIndex % widthMaps;
        int mapRow = mapIndex / widthMaps;
        int offsetX = mapCol * MAP_SIZE;
        int offsetY = mapRow * MAP_SIZE;

        byte[] px = new byte[MAP_SIZE * MAP_SIZE];
        Arrays.fill(px, colorToPalette(canvas.background()));

        for (Element e : state.elements()) {
            if (!e.visible()) continue;
            if (e.rotation() != 0) warnRotationOnce(e);
            switch (e) {
                case RectElement r -> drawRect(px, r, offsetX, offsetY);
                case TextElement t -> drawText(px, t, offsetX, offsetY);
            }
        }
        return px;
    }

    // ---------- Rect ----------

    private void drawRect(byte[] px, RectElement r, int offsetX, int offsetY) {
        int localX = r.x() - offsetX;
        int localY = r.y() - offsetY;
        int w = r.w();
        int h = r.h();
        if (localX + w <= 0 || localY + h <= 0 || localX >= MAP_SIZE || localY >= MAP_SIZE) {
            return; // 完全在当前 map 之外
        }
        if (r.fill() != null) {
            fillRect(px, localX, localY, w, h, colorToPalette(r.fill()));
        }
        Stroke s = r.stroke();
        if (s != null && s.width() > 0) {
            byte sc = colorToPalette(s.color());
            int sw = s.width();
            int cappedSw = Math.min(sw, Math.max(1, Math.min(w, h) / 2));
            // top
            fillRect(px, localX, localY, w, cappedSw, sc);
            // bottom
            fillRect(px, localX, localY + h - cappedSw, w, cappedSw, sc);
            // left
            fillRect(px, localX, localY, cappedSw, h, sc);
            // right
            fillRect(px, localX + w - cappedSw, localY, cappedSw, h, sc);
        }
    }

    // ---------- Text ----------

    private void drawText(byte[] px, TextElement t, int offsetX, int offsetY) {
        if (t.text() == null || t.text().isEmpty()) return;
        int scale = Math.max(1, Math.round(t.fontSize() / (float) BitmapFont.CHAR_HEIGHT));
        int stride = (BitmapFont.CHAR_WIDTH + 1) * scale;
        int textPxWidth = Math.max(0, t.text().length() * stride - scale);

        int startXInCanvas = switch (t.align()) {
            case "center" -> t.x() + (t.w() - textPxWidth) / 2;
            case "right" -> t.x() + t.w() - textPxWidth;
            default -> t.x();
        };
        int startYInCanvas = t.y();

        int cursorXLocal = startXInCanvas - offsetX;
        int yLocal = startYInCanvas - offsetY;
        byte color = colorToPalette(t.color());

        for (int i = 0; i < t.text().length(); i++) {
            int[] glyph = BitmapFont.glyph(t.text().charAt(i));
            drawGlyph(px, cursorXLocal, yLocal, glyph, color, scale);
            cursorXLocal += stride;
            if (cursorXLocal >= MAP_SIZE) return; // 后续字符完全在右侧，跳出
        }
    }

    private static void drawGlyph(byte[] px, int x, int y, int[] glyph, byte color, int scale) {
        for (int row = 0; row < BitmapFont.CHAR_HEIGHT; row++) {
            int rowBits = glyph[row];
            for (int col = 0; col < BitmapFont.CHAR_WIDTH; col++) {
                boolean on = (rowBits & (1 << (BitmapFont.CHAR_WIDTH - 1 - col))) != 0;
                if (!on) continue;
                int px0 = x + col * scale;
                int py0 = y + row * scale;
                for (int dy = 0; dy < scale; dy++) {
                    int py = py0 + dy;
                    if (py < 0 || py >= MAP_SIZE) continue;
                    int base = py * MAP_SIZE;
                    for (int dx = 0; dx < scale; dx++) {
                        int pxx = px0 + dx;
                        if (pxx < 0 || pxx >= MAP_SIZE) continue;
                        px[base + pxx] = color;
                    }
                }
            }
        }
    }

    // ---------- 公用低层 ----------

    private static void fillRect(byte[] px, int x, int y, int w, int h, byte color) {
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(MAP_SIZE, x + w);
        int y1 = Math.min(MAP_SIZE, y + h);
        if (x1 <= x0 || y1 <= y0) return;
        for (int yy = y0; yy < y1; yy++) {
            int base = yy * MAP_SIZE;
            for (int xx = x0; xx < x1; xx++) {
                px[base + xx] = color;
            }
        }
    }

    /**
     * 调色板查表 + 缓存。未知格式 fallback 到白色。
     *
     * <p>{@link MapPalette#matchColor} 两个重载在 Paper 1.21.11 都标 deprecated/removal，
     * 但官方 API 没给替代；M4 接入真正调色板 LUT（{@code docs/rendering.md}）后会绕过此类。
     * M3 期间 suppress 警告保留调用。</p>
     */
    @SuppressWarnings("removal")
    private byte colorToPalette(String hex) {
        if (hex == null) hex = "#FFFFFF";
        Byte cached = paletteCache.get(hex);
        if (cached != null) return cached;
        int[] rgb = parseHex(hex);
        byte idx = MapPalette.matchColor(rgb[0], rgb[1], rgb[2]);
        paletteCache.put(hex, idx);
        return idx;
    }

    private static int[] parseHex(String hex) {
        Matcher m = HEX_RE.matcher(hex);
        if (!m.matches()) return new int[] {255, 255, 255};
        int rgb = Integer.parseInt(m.group(1), 16);
        return new int[] {(rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff};
    }

    private void warnRotationOnce(Element e) {
        if (rotationWarned) return;
        rotationWarned = true;
        log.log(Level.WARNING,
                "CanvasCompositor: rotation=" + e.rotation() + " on element " + e.id()
                + " not rendered in M3-T7 (renders as rotation=0); true rotation deferred to M4.");
    }
}
