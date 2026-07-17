package ac.haru.hikaricanvas.render;

import ac.haru.hikaricanvas.state.Effects;
import ac.haru.hikaricanvas.state.Element;
import ac.haru.hikaricanvas.state.Shadow;
import ac.haru.hikaricanvas.state.Stroke;
import ac.haru.hikaricanvas.state.TextElement;

import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * 文本绘制（含 effects: glow / shadow / stroke）+ 像素字体最近邻路径。
 */
public final class TextRenderer implements ElementRenderer {

    @Override
    public void draw(Graphics2D g, Element e, RenderContext ctx) {
        TextElement t = (TextElement) e;
        if (t.text() == null || t.text().isEmpty()) return;

        // italic = AWT shear transform。在所有绘制 pass（glow / shadow / stroke / fill）
        // 外面包 shear；shear sx=-0.2 让字形上半部分向右倾斜（标准 italic 视觉）。
        // 与前端 Canvas 2D ctx.transform(1, 0, -0.2, 1, 0, 0) 数学等价 — 双端像素一致。
        boolean italic = Boolean.TRUE.equals(t.italic());
        java.awt.geom.AffineTransform savedItalic = null;
        if (italic) {
            savedItalic = g.getTransform();
            // shear 锚点在文本 bbox 顶部（y=t.y()）让字形整体倾斜可控；y < anchor 处 x 左移，
            // y > anchor 处 x 右移。这里用元素 y 作 baseline-ish 锚点（简化处理）
            g.translate(t.x(), t.y());
            g.shear(-0.2, 0);
            g.translate(-t.x(), -t.y());
        }
        try {
            drawInner(g, t, ctx);
        } finally {
            if (savedItalic != null) g.setTransform(savedItalic);
        }
    }

    private void drawInner(Graphics2D g, TextElement t, RenderContext ctx) {
        FontRegistry.Registered reg = ctx.fontRegistry().getOrDefault(t.fontId());
        if (reg == null) {
            ctx.log().warning("CanvasCompositor: no font available (id=" + t.fontId()
                    + " default=" + FontRegistry.DEFAULT_FONT_ID + "); skipping text " + t.id());
            return;
        }
        // 像素字体启用最近邻缩放路径。TextLayout 的字符定位仍用 target-size
        // metrics（保证排字与非像素场景一致）；drawPixelatedGlyph 内部用 nativeSize
        // 字体画 mask，再 NEAREST_NEIGHBOR drawImage 缩放到 target。
        boolean useNearest = shouldUseNearestNeighbor(reg, t.fontSize());
        Font font = reg.derive(t.fontSize());
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics(font);

        // 多行 + wrap + letterSpacing + 基线 + align + 竖排
        // 全由 TextLayout；用 canonicalCharWidth，不再依赖 Java FontMetrics
        List<TextLayout.PositionedGlyph> glyphs = TextLayout.layout(t);
        if (glyphs.isEmpty()) return;

        Effects effects = t.effects();

        // 发光：最底层。字形 mask → 盒模糊 alpha → 着色 → 合成到主画布
        if (effects != null && effects.glow() != null) {
            GlowRenderer.render(g, glyphs, font, effects.glow());
        }

        // 阴影：drawString 到 (dx, dy) 偏移处
        if (effects != null && effects.shadow() != null) {
            Shadow sh = effects.shadow();
            g.setColor(FillPaintBuilder.parseColor(sh.color()));
            for (TextLayout.PositionedGlyph pg : glyphs) {
                if (useNearest) drawPixelatedGlyph(g, pg, reg, t.fontSize(), fm, sh.dx(), sh.dy());
                else drawGlyph(g, pg, sh.dx(), sh.dy());
            }
        }

        // 描边：GlyphVector.getOutline + BasicStroke.draw
        if (effects != null && effects.stroke() != null && effects.stroke().width() > 0) {
            Stroke strokeCfg = effects.stroke();
            FontRenderContext frc = g.getFontRenderContext();
            java.awt.Stroke prev = g.getStroke();
            g.setStroke(new BasicStroke(strokeCfg.width(),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(FillPaintBuilder.parseColor(strokeCfg.color()));
            for (TextLayout.PositionedGlyph pg : glyphs) {
                drawGlyphOutline(g, pg, font, frc);
            }
            g.setStroke(prev);
        }

        // bold = 额外 stroke pass（color = text color，width = max(1.5, fontSize * 0.08)）。
        // 字形 outline 描边 + fill 叠加产生"加粗"视觉效果；与 effects.stroke 独立可叠加。
        // 像素字体（NN 路径）目前不支持 bold 描边（NN mask 路径走 BufferedImage 而非 outline）—
        // 简单跳过，让像素字体 bold 走原样 fill（已经够清晰）。
        if (Boolean.TRUE.equals(t.bold()) && !useNearest) {
            float boldWidth = Math.max(1.5f, t.fontSize() * 0.08f);
            FontRenderContext frc = g.getFontRenderContext();
            java.awt.Stroke prev = g.getStroke();
            g.setStroke(new BasicStroke(boldWidth,
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(FillPaintBuilder.parseColor(t.color()));
            for (TextLayout.PositionedGlyph pg : glyphs) {
                drawGlyphOutline(g, pg, font, frc);
            }
            g.setStroke(prev);
        }

        // 最顶层：字形填充（正常颜色）
        g.setColor(FillPaintBuilder.parseColor(t.color()));
        for (TextLayout.PositionedGlyph pg : glyphs) {
            if (useNearest) drawPixelatedGlyph(g, pg, reg, t.fontSize(), fm, 0, 0);
            else drawGlyph(g, pg, 0, 0);
        }
    }

    /**
     * 判断是否启用像素字体最近邻缩放路径（rendering.md §2.4）。
     *
     * <p>只要 {@link FontRegistry.Metadata#pixelated()} 就走 NN，
     * 不要求 {@code targetSize} 是 {@code nativeSize} 的整数倍。原因：像素字体
     * 本就设计为 {@code nativeSize}（12）点阵，任何 target size 都是放大——非整数倍时
     * 走 {@code drawString} 会让 Java2D 按字号插值出灰阶像素，与前端 NN 不一致。现在
     * 双端都从 {@code nativeSize} mask 用 NEAREST 拉伸到 {@code targetSize}，保证像素锐利 + 双端像素对齐。</p>
     */
    private static boolean shouldUseNearestNeighbor(FontRegistry.Registered reg, int targetSize) {
        FontRegistry.Metadata md = reg.metadata();
        return md.pixelated() && md.nativeSize() > 0 && targetSize > 0;
    }

    /**
     * 像素字体 fill/shadow 路径：扫 mask 实际字形边界 + 手工 per-pixel NN。
     *
     * <p>与前端 {@code PreviewRenderer.drawPixelatedGlyph} 同策略 —— 各端按自己字体引擎
     * 实际画出的字形宽度 scale，字形永远完整；layout cursor 仍按 canonical 推。</p>
     */
    private static void drawPixelatedGlyph(Graphics2D g, TextLayout.PositionedGlyph pg,
                                           FontRegistry.Registered reg, int targetSize,
                                           FontMetrics targetFm, int dx, int dy) {
        int nativeSize = reg.metadata().nativeSize();
        Font nativeFont = reg.derive(nativeSize);
        FontMetrics nativeFm = g.getFontMetrics(nativeFont);

        // 1) mask 全宽
        BufferedImage mask = new BufferedImage(nativeSize, nativeSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D mg = mask.createGraphics();
        try {
            CanvasCompositor.applyHints(mg);
            mg.setFont(nativeFont);
            mg.setColor(g.getColor());
            mg.drawString(pg.ch(), 0, nativeFm.getAscent());
        } finally {
            mg.dispose();
        }

        // 2) 扫实际字形右边界
        int maxCol = -1;
        for (int y = 0; y < nativeSize; y++) {
            for (int x = 0; x < nativeSize; x++) {
                if ((mask.getRGB(x, y) >>> 24) > 0 && x > maxCol) maxCol = x;
            }
        }
        int actualW = Math.max(1, maxCol + 1);

        // 3) dst 尺寸
        int dstW = Math.max(1, (int) Math.round(actualW * (double) targetSize / nativeSize));
        int dstH = targetSize;

        // 4) 手工 NN 填 out（src 只看 actualW 列）
        BufferedImage out = new BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_ARGB);
        for (int ty = 0; ty < dstH; ty++) {
            int sy = Math.min(nativeSize - 1, (ty * nativeSize) / dstH);
            for (int tx = 0; tx < dstW; tx++) {
                int sx = Math.min(actualW - 1, (tx * actualW) / dstW);
                out.setRGB(tx, ty, mask.getRGB(sx, sy));
            }
        }

        int targetAscent = (int) Math.round(targetSize * TextLayout.ASCENT_RATIO);
        if (pg.rotated()) {
            AffineTransform saved = g.getTransform();
            g.translate(pg.x() + dx, pg.baselineY() + dy);
            g.rotate(Math.PI / 2);
            g.drawImage(out, -dstW / 2, targetAscent - targetSize / 2, null);
            g.setTransform(saved);
        } else {
            g.drawImage(out, pg.x() + dx, pg.baselineY() + dy - targetAscent, null);
        }
    }

    /**
     * 绘制单个 glyph。{@code pg.rotated == true} 时绕 {@code (pg.x, pg.baselineY)}
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
}
