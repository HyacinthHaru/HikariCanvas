package moe.hikari.canvas.render;

import moe.hikari.canvas.state.Effects;
import moe.hikari.canvas.state.Element;
import moe.hikari.canvas.state.ProjectState;
import moe.hikari.canvas.state.RectElement;
import moe.hikari.canvas.state.Shadow;
import moe.hikari.canvas.state.Stroke;
import moe.hikari.canvas.state.TextElement;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 {@link ProjectState} 光栅化成 ARGB 大图，再按 128×128 量化切片成 palette 字节数组。
 * 契约对应 {@code docs/rendering.md §1 / §4 / §6 / §7}。
 *
 * <h2>管线</h2>
 * <pre>
 *   ProjectState
 *      → {@link #rasterize} 产出 {@code widthMaps*128 × heightMaps*128} 的 RGB BufferedImage
 *      → {@link #toPaletteSlice} 按 mapIndex 切 128×128、逐像素 {@link PaletteLut#matchColor} 量化
 * </pre>
 *
 * <h2>M4-T4 简化（逐项由后续 Tn 补齐）</h2>
 * <ul>
 *   <li>文本：单行 {@code drawString}；{@code letterSpacing} / {@code lineHeight} / 多行 wrap 留 M4-T5</li>
 *   <li>{@code rotation != 0}：log WARN 一次，按 {@code 0} 渲染；真 rotation 留 M4-T6</li>
 *   <li>效果族（{@code stroke} / {@code shadow} / {@code glow}）：M4-T8/T9/T10 接入</li>
 *   <li>像素字体最近邻缩放：本 T4 先走 Graphics2D 自带 {@code deriveFont}；像素字体精细化留 M4-T5</li>
 * </ul>
 *
 * <h2>线程模型</h2>
 * 无可变状态（除首次 rotation warn 的 boolean 记忆）；{@link #rasterize} 每次分配新 BufferedImage，
 * 多线程并发调用安全。{@link PaletteLut} / {@link FontRegistry} 都在构造时传入、稳态只读。
 */
public final class CanvasCompositor {

    public static final int MAP_SIZE = 128;

    private static final Pattern HEX_RE = Pattern.compile("^#([0-9A-Fa-f]{6})([0-9A-Fa-f]{2})?$");

    private final PaletteLut paletteLut;
    private final FontRegistry fontRegistry;
    private final Logger log;
    private volatile boolean verticalWarned = false;

    public CanvasCompositor(PaletteLut paletteLut, FontRegistry fontRegistry, Logger log) {
        this.paletteLut = paletteLut;
        this.fontRegistry = fontRegistry;
        this.log = log;
    }

    /**
     * 把 {@link ProjectState} 渲染到整张大画布。返回 {@code TYPE_INT_RGB}（无 alpha）。
     *
     * <p>大小 = {@code (widthMaps*128) × (heightMaps*128)}；2×2 = 64 KiB、8×4 = 1 MiB、10×10 = 6.5 MiB。</p>
     */
    public BufferedImage rasterize(ProjectState state) {
        ProjectState.Canvas canvas = state.canvas();
        int widthPx = canvas.widthMaps() * MAP_SIZE;
        int heightPx = canvas.heightMaps() * MAP_SIZE;
        // TYPE_INT_RGB 省一个 byte/pixel 的 alpha；MC 地图原生不支持半透明，我们在 quantize
        // 阶段也硬截断，所以不用 ARGB 承载中间半透明
        BufferedImage img = new BufferedImage(widthPx, heightPx, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            applyHints(g);
            // 背景
            g.setColor(parseColor(canvas.background()));
            g.fillRect(0, 0, widthPx, heightPx);
            // 元素按 z-order（index 越大越上层）
            for (Element e : state.elements()) {
                if (!e.visible()) continue;
                // M4-T6：rotation ∈ {0, 90, 180, 270}。绕 element bbox 中心转
                // 用 AffineTransform save/restore 保证不污染后续 element 的绘制坐标系
                AffineTransform savedTx = null;
                if (e.rotation() != 0) {
                    savedTx = g.getTransform();
                    double cx = e.x() + e.w() / 2.0;
                    double cy = e.y() + e.h() / 2.0;
                    g.rotate(Math.toRadians(e.rotation()), cx, cy);
                }
                switch (e) {
                    case RectElement r -> drawRect(g, r);
                    case TextElement t -> drawText(g, t);
                }
                if (savedTx != null) {
                    g.setTransform(savedTx);
                }
            }
        } finally {
            g.dispose();
        }
        return img;
    }

    /**
     * 从整张大图切出 {@code mapIndex} 对应的 128×128 块，逐像素量化为调色板 byte。
     *
     * @param img       {@link #rasterize} 的产物
     * @param mapIndex  {@code row * widthMaps + col}（按 FrameDeployer slot 约定）
     * @param widthMaps 画布横向 map 数
     * @return {@code byte[128*128]}，可直接交给 {@link HikariCanvasRenderer#update}
     */
    public byte[] toPaletteSlice(BufferedImage img, int mapIndex, int widthMaps) {
        int col = mapIndex % widthMaps;
        int row = mapIndex / widthMaps;
        int offsetX = col * MAP_SIZE;
        int offsetY = row * MAP_SIZE;
        byte[] out = new byte[MAP_SIZE * MAP_SIZE];
        int[] rowBuf = new int[MAP_SIZE];
        for (int y = 0; y < MAP_SIZE; y++) {
            img.getRGB(offsetX, offsetY + y, MAP_SIZE, 1, rowBuf, 0, MAP_SIZE);
            int base = y * MAP_SIZE;
            for (int x = 0; x < MAP_SIZE; x++) {
                int rgb = rowBuf[x];
                int r = (rgb >> 16) & 0xff;
                int gg = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                out[base + x] = paletteLut.matchColor(r, gg, b);
            }
        }
        return out;
    }

    // ---------- Graphics2D 设置（rendering.md §4.2）----------

    private static void applyHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    // ---------- 元素绘制 ----------

    private void drawRect(Graphics2D g, RectElement r) {
        if (r.fill() != null) {
            g.setColor(parseColor(r.fill()));
            g.fillRect(r.x(), r.y(), r.w(), r.h());
        }
        Stroke s = r.stroke();
        if (s != null && s.width() > 0) {
            int sw = Math.min(s.width(), Math.max(1, Math.min(r.w(), r.h()) / 2));
            g.setColor(parseColor(s.color()));
            // BasicStroke 的 drawRect 会把线宽分摊在矩形边界两侧，像素对齐不精确。
            // 整数像素画法：用 4 个 fillRect 手工画边框，与 M3 行为一致、稳定可控
            g.fillRect(r.x(), r.y(), r.w(), sw);
            g.fillRect(r.x(), r.y() + r.h() - sw, r.w(), sw);
            g.fillRect(r.x(), r.y(), sw, r.h());
            g.fillRect(r.x() + r.w() - sw, r.y(), sw, r.h());
            g.setStroke(new BasicStroke(1)); // 还原默认
        }
    }

    private void drawText(Graphics2D g, TextElement t) {
        if (t.text() == null || t.text().isEmpty()) return;
        if (t.vertical()) warnVerticalOnce(t);

        FontRegistry.Registered reg = fontRegistry.getOrDefault(t.fontId());
        if (reg == null) {
            log.warning("CanvasCompositor: no font available (id=" + t.fontId()
                    + " default=" + FontRegistry.DEFAULT_FONT_ID + "); skipping text " + t.id());
            return;
        }
        Font font = reg.derive(t.fontSize());
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics(font);

        // M4-T5：多行 + wrap + letterSpacing + 基线 + align 走 TextLayout；
        // 逐字符 drawString 以支持 per-char letterSpacing（Graphics2D 无原生 per-char letterSpacing）
        List<TextLayout.PositionedGlyph> glyphs = TextLayout.layout(t, fm);
        if (glyphs.isEmpty()) return;

        Effects effects = t.effects();
        // M4-T10 预留槽：glow（盒模糊自实现），M4-T10 补
        // 暂时 skip，直接从 shadow 开始；顺序 rendering.md §5.4

        // M4-T9 阴影：rendering.md §5.2 约定"不用 Graphics2D 内置 shadow"；
        // 做法是把字形画一份到 (dx, dy) 偏移处、上阴影颜色。与浏览器端 fillText 对齐
        if (effects != null && effects.shadow() != null) {
            Shadow sh = effects.shadow();
            g.setColor(parseColor(sh.color()));
            for (TextLayout.PositionedGlyph pg : glyphs) {
                g.drawString(pg.ch(), pg.x() + sh.dx(), pg.baselineY() + sh.dy());
            }
        }

        // M4-T8 描边：rendering.md §5.1——glyph outline + BasicStroke.draw 画边框
        // FontRenderContext 来自当前 Graphics2D（继承 hints，确保与 fill 阶段一致）
        if (effects != null && effects.stroke() != null && effects.stroke().width() > 0) {
            Stroke strokeCfg = effects.stroke();
            FontRenderContext frc = g.getFontRenderContext();
            java.awt.Stroke prev = g.getStroke();
            g.setStroke(new BasicStroke(strokeCfg.width(),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(parseColor(strokeCfg.color()));
            for (TextLayout.PositionedGlyph pg : glyphs) {
                GlyphVector gv = font.createGlyphVector(frc, pg.ch());
                Shape outline = gv.getOutline(pg.x(), pg.baselineY());
                g.draw(outline);
            }
            g.setStroke(prev);
        }

        // 最顶层：字形填充（正常颜色）
        g.setColor(parseColor(t.color()));
        for (TextLayout.PositionedGlyph pg : glyphs) {
            g.drawString(pg.ch(), pg.x(), pg.baselineY());
        }
    }

    private void warnVerticalOnce(TextElement t) {
        if (verticalWarned) return;
        verticalWarned = true;
        log.log(Level.WARNING,
                "CanvasCompositor: vertical=true on " + t.id()
                + " rendered as horizontal (rendering.md §3.3 推迟到 M4.5/M7)");
    }

    private static Color parseColor(String hex) {
        if (hex == null) return Color.WHITE;
        Matcher m = HEX_RE.matcher(hex);
        if (!m.matches()) return Color.WHITE;
        int rgb = Integer.parseInt(m.group(1), 16);
        return new Color((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff);
    }

}
