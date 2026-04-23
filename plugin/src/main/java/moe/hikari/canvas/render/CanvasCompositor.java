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

        FontRegistry.Registered reg = fontRegistry.getOrDefault(t.fontId());
        if (reg == null) {
            log.warning("CanvasCompositor: no font available (id=" + t.fontId()
                    + " default=" + FontRegistry.DEFAULT_FONT_ID + "); skipping text " + t.id());
            return;
        }
        // M5-C5：像素字体启用最近邻缩放路径。TextLayout 的字符定位仍用 target-size
        // metrics（保证排字与非像素场景一致）；drawPixelatedGlyph 内部用 nativeSize
        // 字体画 mask，再 NEAREST_NEIGHBOR drawImage 缩放到 target。
        boolean useNearest = shouldUseNearestNeighbor(reg, t.fontSize());
        Font font = reg.derive(t.fontSize());
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics(font);

        // M4-T5 + M5-C6 + M5-D2：多行 + wrap + letterSpacing + 基线 + align + 竖排
        // 全由 TextLayout；自 M5-D2 起用 canonicalCharWidth，不再依赖 Java FontMetrics
        List<TextLayout.PositionedGlyph> glyphs = TextLayout.layout(t);
        if (glyphs.isEmpty()) return;

        Effects effects = t.effects();

        // M4-T10 发光：最底层。字形 mask → 盒模糊 alpha → 着色 → 合成到主画布
        if (effects != null && effects.glow() != null) {
            GlowRenderer.render(g, glyphs, font, effects.glow());
        }

        // M4-T9 阴影：drawString 到 (dx, dy) 偏移处
        if (effects != null && effects.shadow() != null) {
            Shadow sh = effects.shadow();
            g.setColor(parseColor(sh.color()));
            for (TextLayout.PositionedGlyph pg : glyphs) {
                if (useNearest) drawPixelatedGlyph(g, pg, reg, t.fontSize(), fm, sh.dx(), sh.dy());
                else drawGlyph(g, pg, sh.dx(), sh.dy());
            }
        }

        // M4-T8 描边：GlyphVector.getOutline + BasicStroke.draw
        if (effects != null && effects.stroke() != null && effects.stroke().width() > 0) {
            Stroke strokeCfg = effects.stroke();
            FontRenderContext frc = g.getFontRenderContext();
            java.awt.Stroke prev = g.getStroke();
            g.setStroke(new BasicStroke(strokeCfg.width(),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(parseColor(strokeCfg.color()));
            for (TextLayout.PositionedGlyph pg : glyphs) {
                drawGlyphOutline(g, pg, font, frc);
            }
            g.setStroke(prev);
        }

        // 最顶层：字形填充（正常颜色）
        g.setColor(parseColor(t.color()));
        for (TextLayout.PositionedGlyph pg : glyphs) {
            if (useNearest) drawPixelatedGlyph(g, pg, reg, t.fontSize(), fm, 0, 0);
            else drawGlyph(g, pg, 0, 0);
        }
    }

    /**
     * 判断是否启用像素字体最近邻缩放路径（rendering.md §2.4）：
     * 要求 {@link FontRegistry.Metadata#pixelated()} 且 {@code targetSize} 是
     * {@code nativeSize} 的整数倍（≥ 2）。否则走普通 {@code deriveFont} + {@code drawString}。
     */
    private static boolean shouldUseNearestNeighbor(FontRegistry.Registered reg, int targetSize) {
        FontRegistry.Metadata md = reg.metadata();
        if (!md.pixelated() || md.nativeSize() <= 0) return false;
        int scale = targetSize / md.nativeSize();
        return scale >= 2 && scale * md.nativeSize() == targetSize;
    }

    /**
     * 像素字体 fill/shadow 路径：用 {@link FontRegistry.Metadata#nativeSize()} 字体
     * 画 char mask → {@link java.awt.RenderingHints#VALUE_INTERPOLATION_NEAREST_NEIGHBOR}
     * {@link Graphics2D#drawImage} 缩放到 target 尺寸；保持像素字体的整数像素轮廓感。
     *
     * <p>mask 尺寸用 target 的 {@code chW × height}（让 drawImage 1:1 贴合 TextLayout
     * 的 target-size 排字），缩放比 = {@code targetChW / nativeChW}，多数场景是整数比。</p>
     */
    private static void drawPixelatedGlyph(Graphics2D g, TextLayout.PositionedGlyph pg,
                                           FontRegistry.Registered reg, int targetSize,
                                           FontMetrics targetFm, int dx, int dy) {
        Font nativeFont = reg.derive(reg.metadata().nativeSize());
        FontMetrics nativeFm = g.getFontMetrics(nativeFont);
        int nativeChW = Math.max(1, nativeFm.charWidth(pg.ch().charAt(0)));
        int nativeH = Math.max(1, nativeFm.getAscent() + nativeFm.getDescent());
        BufferedImage mask = new BufferedImage(nativeChW, nativeH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D mg = mask.createGraphics();
        try {
            applyHints(mg);
            mg.setFont(nativeFont);
            mg.setColor(g.getColor());
            mg.drawString(pg.ch(), 0, nativeFm.getAscent());
        } finally {
            mg.dispose();
        }

        // target 绘制目的盒：位置对齐 TextLayout 的 baseline / chW 语义
        int targetChW = Math.max(1, targetFm.charWidth(pg.ch().charAt(0)));
        int targetAscent = (int) Math.round(targetSize * TextLayout.ASCENT_RATIO);
        int targetH = Math.max(1, targetAscent + targetFm.getDescent());

        Object prevInterp = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        try {
            if (pg.rotated()) {
                AffineTransform saved = g.getTransform();
                g.translate(pg.x() + dx, pg.baselineY() + dy);
                g.rotate(Math.PI / 2);
                int ascent = targetAscent;
                g.drawImage(mask, -targetChW / 2, ascent - targetSize / 2, targetChW, targetH, null);
                g.setTransform(saved);
            } else {
                int drawX = pg.x() + dx;
                int drawY = pg.baselineY() + dy - targetAscent;
                g.drawImage(mask, drawX, drawY, targetChW, targetH, null);
            }
        } finally {
            if (prevInterp != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, prevInterp);
        }
    }

    /**
     * 绘制单个 glyph。M5-C6：{@code pg.rotated == true} 时绕 {@code (pg.x, pg.baselineY)}
     * 顺时针旋转 90°（CJK 竖排全角标点）。非旋转字符走标准 {@code drawString}。
     */
    private static void drawGlyph(Graphics2D g, TextLayout.PositionedGlyph pg, int offsetDx, int offsetDy) {
        if (!pg.rotated()) {
            g.drawString(pg.ch(), pg.x() + offsetDx, pg.baselineY() + offsetDy);
            return;
        }
        AffineTransform saved = g.getTransform();
        // pivot 是方格中心（TextLayout.layoutVertical 按此约定存 x/baselineY）
        g.translate(pg.x() + offsetDx, pg.baselineY() + offsetDy);
        g.rotate(Math.PI / 2);
        FontMetrics fm = g.getFontMetrics();
        int chW = fm.stringWidth(pg.ch());
        int fontSize = g.getFont().getSize();
        // 在 rotate 后的坐标系里：baseline 位于 y = fontSize/2 * 0.3 的 x 轴上
        // 为让字符落到方格中心：x = -chW/2；y = fontSize*0.8 - fontSize/2
        int ascent = (int) Math.round(fontSize * 0.8);
        g.drawString(pg.ch(), -chW / 2, ascent - fontSize / 2);
        g.setTransform(saved);
    }

    private static void drawGlyphOutline(Graphics2D g, TextLayout.PositionedGlyph pg,
                                         Font font, FontRenderContext frc) {
        if (!pg.rotated()) {
            GlyphVector gv = font.createGlyphVector(frc, pg.ch());
            g.draw(gv.getOutline(pg.x(), pg.baselineY()));
            return;
        }
        AffineTransform saved = g.getTransform();
        g.translate(pg.x(), pg.baselineY());
        g.rotate(Math.PI / 2);
        GlyphVector gv = font.createGlyphVector(frc, pg.ch());
        int chW = (int) Math.round(gv.getLogicalBounds().getWidth());
        int fontSize = font.getSize();
        int ascent = (int) Math.round(fontSize * 0.8);
        g.draw(gv.getOutline(-chW / 2f, ascent - fontSize / 2f));
        g.setTransform(saved);
    }

    private static Color parseColor(String hex) {
        if (hex == null) return Color.WHITE;
        Matcher m = HEX_RE.matcher(hex);
        if (!m.matches()) return Color.WHITE;
        int rgb = Integer.parseInt(m.group(1), 16);
        return new Color((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff);
    }

}
