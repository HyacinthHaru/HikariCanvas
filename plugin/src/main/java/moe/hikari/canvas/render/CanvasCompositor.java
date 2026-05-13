package moe.hikari.canvas.render;

import moe.hikari.canvas.state.BlendMode;
import moe.hikari.canvas.state.CircleElement;
import moe.hikari.canvas.state.Effects;
import moe.hikari.canvas.state.Element;
import moe.hikari.canvas.state.IconElement;
import moe.hikari.canvas.state.Layer;
import moe.hikari.canvas.state.PathElement;
import moe.hikari.canvas.state.ProjectState;
import moe.hikari.canvas.state.RectElement;
import moe.hikari.canvas.state.RenderMode;
import moe.hikari.canvas.state.Shadow;
import moe.hikari.canvas.state.ShapeElement;
import moe.hikari.canvas.state.Stroke;
import moe.hikari.canvas.state.TextElement;
import moe.hikari.canvas.template.asset.TemplateAssetService;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
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
    private final TemplateAssetService assetService;  // 可空：测试 / 历史调用方
    private final Logger log;

    public CanvasCompositor(PaletteLut paletteLut, FontRegistry fontRegistry, Logger log) {
        this(paletteLut, fontRegistry, null, log);
    }

    public CanvasCompositor(PaletteLut paletteLut, FontRegistry fontRegistry,
                            TemplateAssetService assetService, Logger log) {
        this.paletteLut = paletteLut;
        this.fontRegistry = fontRegistry;
        this.assetService = assetService;
        this.log = log;
    }

    /**
     * 把 {@link ProjectState} 渲染到整张大画布。返回 {@code TYPE_INT_RGB}（无 alpha）。
     *
     * <p>大小 = {@code (widthMaps*128) × (heightMaps*128)}；2×2 = 64 KiB、8×4 = 1 MiB、10×10 = 6.5 MiB。</p>
     *
     * <h3>M8-E 分层渲染</h3>
     * <ul>
     *   <li><b>fast path</b>：层 opacity = 1 + blendMode = NORMAL + 层内无 element opacity/blendMode
     *       → 直接 draw 到主 buffer，与 M7 之前行为完全等价（snapshot baseline 不漂移）</li>
     *   <li><b>slow path</b>：分配 ARGB 中间 buffer 画层内 element + element.opacity 用
     *       {@link AlphaComposite#SrcOver}.derive；最后用
     *       {@link BlendModes#applyBlendModeOver} 把层 buffer 按 layer.opacity / blendMode
     *       合成到主 buffer</li>
     * </ul>
     *
     * <p>层间 z-order = {@code state.layers()} 索引（0 = 底，越大越上）。</p>
     */
    public BufferedImage rasterize(ProjectState state) {
        ProjectState.Canvas canvas = state.canvas();
        int widthPx = canvas.widthMaps() * MAP_SIZE;
        int heightPx = canvas.heightMaps() * MAP_SIZE;
        // 主 buffer：TYPE_INT_RGB（无 alpha）—— MC 地图最终也无 alpha
        BufferedImage img = new BufferedImage(widthPx, heightPx, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            applyHints(g);
            // 背景
            g.setColor(parseColor(canvas.background()));
            g.fillRect(0, 0, widthPx, heightPx);

            // M8-E：分层渲染
            for (Layer layer : state.layers()) {
                if (!layer.visible() || layer.opacity() <= 0f) continue;
                if (layer.elements().isEmpty()) continue;

                if (canFastPath(layer)) {
                    drawElementsTo(g, layer.elements());
                } else {
                    BufferedImage layerBuf = renderLayerToBuffer(layer, widthPx, heightPx);
                    BlendModes.applyBlendModeOver(img, layerBuf, layer.opacity(), layer.blendMode());
                }
            }
        } finally {
            g.dispose();
        }
        return img;
    }

    /**
     * fast path 判定：层与层内所有 element 都为默认混合参数时直接画主 buffer，避免分配中间 ARGB
     * 缓冲与 per-pixel 合成。这是保证已有 fixture snapshot baseline 不漂移的关键。
     *
     * <p>M8-E 阶段 element 级 blendMode / renderMode 字段路径已打通但合成未实装（推到 M11
     * 与 dither 一起做）。fast path 检查这两个字段是<b>防御性</b>的 —— 一旦 M11 真接 dither，
     * renderMode=DITHER 的 element 必须走 slow path（per-pixel 抖动）；这里提前堵口避免
     * 集成时漏判。当前 element.blendMode 即使非 NORMAL 也走 fast path 等价处理（无合成）。</p>
     */
    private static boolean canFastPath(Layer layer) {
        if (layer.opacity() < 1.0f) return false;
        if (layer.blendMode() != BlendMode.NORMAL) return false;
        for (Element e : layer.elements()) {
            Float op = e.opacity();
            if (op != null && op < 1.0f) return false;
            RenderMode rm = e.renderMode();
            if (rm != null && rm != RenderMode.CLEAN) return false;
        }
        return true;
    }

    /** 在指定 Graphics2D 上按 z-order 画一组 element，含 element.opacity 处理。 */
    private void drawElementsTo(Graphics2D g, List<Element> elements) {
        Composite baseComposite = g.getComposite();
        for (Element e : elements) {
            if (!e.visible()) continue;
            float opacity = e.effectiveOpacity();

            AffineTransform savedTx = null;
            if (e.rotation() != 0) {
                savedTx = g.getTransform();
                double cx = e.x() + e.w() / 2.0;
                double cy = e.y() + e.h() / 2.0;
                g.rotate(Math.toRadians(e.rotation()), cx, cy);
            }
            if (opacity < 1.0f) {
                g.setComposite(AlphaComposite.SrcOver.derive(opacity));
            }
            switch (e) {
                case RectElement r -> drawRect(g, r);
                case TextElement t -> drawText(g, t);
                case IconElement ic -> drawIcon(g, ic);
                case PathElement p -> drawPath(g, p);
                case CircleElement cir -> drawCircle(g, cir);
                case ShapeElement sh -> drawShape(g, sh);
            }
            if (opacity < 1.0f) g.setComposite(baseComposite);
            if (savedTx != null) g.setTransform(savedTx);
        }
    }

    /** slow path：把 layer 内 element 画到独立 ARGB buffer（透明背景）。 */
    private BufferedImage renderLayerToBuffer(Layer layer, int widthPx, int heightPx) {
        BufferedImage buf = new BufferedImage(widthPx, heightPx, BufferedImage.TYPE_INT_ARGB);
        Graphics2D lg = buf.createGraphics();
        try {
            applyHints(lg);
            drawElementsTo(lg, layer.elements());
        } finally {
            lg.dispose();
        }
        return buf;
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

    /**
     * 绘制图标元素。{@code tint} 非空时对图标 alpha 做"source-in"染色 —— 保留 alpha 形状、
     * 整体替换为 tint 色（适合纯白图标 + 主题色染色）。
     */
    private void drawIcon(Graphics2D g, IconElement ic) {
        if (assetService == null) {
            log.warning("[compositor] IconElement '" + ic.id() + "' but no assetService bound");
            return;
        }
        BufferedImage img = assetService.loadIcon(ic.source());
        if (img == null) {
            // 占位：画个虚线方框 + ?，方便定位"图标 source 错"
            g.setColor(new Color(0xAAAAAA));
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    1f, new float[]{3f, 2f}, 0f));
            g.drawRect(ic.x(), ic.y(), Math.max(1, ic.w() - 1), Math.max(1, ic.h() - 1));
            g.setStroke(new BasicStroke(1f));
            g.drawString("?", ic.x() + ic.w() / 2 - 3, ic.y() + ic.h() / 2 + 4);
            return;
        }
        if (ic.tint() == null || ic.tint().isBlank()) {
            // 原色直接缩放绘制
            g.drawImage(img, ic.x(), ic.y(), ic.w(), ic.h(), null);
            return;
        }
        // 染色：先画 tinted 形状 (source-in 合成) 到临时图，再贴
        BufferedImage tinted = new BufferedImage(ic.w(), ic.h(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D tg = tinted.createGraphics();
        try {
            tg.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            tg.drawImage(img, 0, 0, ic.w(), ic.h(), null);
            tg.setComposite(AlphaComposite.SrcIn);
            tg.setColor(parseColor(ic.tint()));
            tg.fillRect(0, 0, ic.w(), ic.h());
        } finally {
            tg.dispose();
        }
        g.drawImage(tinted, ic.x(), ic.y(), null);
    }

    // ---------- M9-B：PathElement / CircleElement / ShapeElement 绘制 ----------

    /**
     * 绘制 path 元素。d 内坐标相对 element.(x, y)，所以临时 translate 后绘制；marker 在
     * path 端点上，方向由 {@link PathParser} 提取的切线决定。
     */
    private void drawPath(Graphics2D g, PathElement p) {
        if (p.d() == null || p.d().isEmpty()) return;
        PathParser.Result parsed = PathParser.parse(p.d());
        if (parsed.path() == null) return;

        AffineTransform savedTx = g.getTransform();
        g.translate(p.x(), p.y());

        // 填充
        if (p.fill() != null) {
            g.setColor(parseColor(p.fill()));
            g.fill(parsed.path());
        }

        // 描边
        Stroke s = p.stroke();
        Color strokeColor = null;
        int strokeWidth = 0;
        if (s != null && s.width() > 0) {
            strokeWidth = s.width();
            strokeColor = parseColor(s.color());
            g.setColor(strokeColor);
            java.awt.Stroke prevStroke = g.getStroke();
            g.setStroke(new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(parsed.path());
            g.setStroke(prevStroke);
        }

        // marker（需要 stroke 才有意义；color 沿用 stroke.color）
        if (parsed.hasSegments() && strokeColor != null) {
            if (p.markerEnd() != null) {
                drawMarker(g, p.markerEnd(),
                        parsed.endX(), parsed.endY(),
                        parsed.endTangentX(), parsed.endTangentY(),
                        strokeWidth, strokeColor);
            }
            if (p.markerStart() != null) {
                // markerStart 朝起点外 = startTangent 反向
                drawMarker(g, p.markerStart(),
                        parsed.startX(), parsed.startY(),
                        -parsed.startTangentX(), -parsed.startTangentY(),
                        strokeWidth, strokeColor);
            }
        }

        g.setTransform(savedTx);
    }

    private static void drawMarker(Graphics2D g, String type, double x, double y,
                                   double dirX, double dirY, int strokeWidth, Color color) {
        switch (type) {
            case "arrow" -> MarkerRenderer.drawArrow(g, x, y, dirX, dirY,
                    MarkerRenderer.arrowSize(strokeWidth), color);
            case "dot" -> MarkerRenderer.drawDot(g, x, y,
                    MarkerRenderer.dotRadius(strokeWidth), color);
            default -> { /* ignore unknown marker；EditSession 已限值，理论不可达 */ }
        }
    }

    /**
     * 绘制 circle / 椭圆。bbox 推 cx/cy/rx/ry：cx = x + w/2, cy = y + h/2, rx = w/2, ry = h/2。
     */
    private void drawCircle(Graphics2D g, CircleElement c) {
        java.awt.geom.Ellipse2D.Double e = new java.awt.geom.Ellipse2D.Double(
                c.x(), c.y(), c.w(), c.h());
        if (c.fill() != null) {
            g.setColor(parseColor(c.fill()));
            g.fill(e);
        }
        Stroke s = c.stroke();
        if (s != null && s.width() > 0) {
            g.setColor(parseColor(s.color()));
            java.awt.Stroke prevStroke = g.getStroke();
            g.setStroke(new BasicStroke(s.width(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            g.draw(e);
            g.setStroke(prevStroke);
        }
    }

    /**
     * 绘制正多边形 / 星。外接圆中心在 bbox 中心；半径 = min(w, h) / 2。
     * 第一个顶点朝上（角度 -π/2）；后续顶点等角度分布。star 时外内交替（外用 outerR，内用 outerR × innerRatio）。
     */
    private void drawShape(Graphics2D g, ShapeElement s) {
        java.awt.geom.Path2D.Double path = buildShapePath(s);
        if (s.fill() != null) {
            g.setColor(parseColor(s.fill()));
            g.fill(path);
        }
        Stroke st = s.stroke();
        if (st != null && st.width() > 0) {
            g.setColor(parseColor(st.color()));
            java.awt.Stroke prevStroke = g.getStroke();
            g.setStroke(new BasicStroke(st.width(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(path);
            g.setStroke(prevStroke);
        }
    }

    private static java.awt.geom.Path2D.Double buildShapePath(ShapeElement s) {
        double cx = s.x() + s.w() / 2.0;
        double cy = s.y() + s.h() / 2.0;
        double outerR = Math.min(s.w(), s.h()) / 2.0;
        boolean star = "star".equals(s.kind());
        double innerR = star
                ? outerR * (s.innerRatio() == null ? 0.5 : s.innerRatio())
                : 0;
        int sides = s.sides();
        int totalVerts = star ? sides * 2 : sides;

        java.awt.geom.Path2D.Double path = new java.awt.geom.Path2D.Double();
        for (int i = 0; i < totalVerts; i++) {
            double angle = -Math.PI / 2 + (Math.PI * 2 * i / totalVerts);
            double r = (star && (i & 1) == 1) ? innerR : outerR;
            double x = cx + Math.cos(angle) * r;
            double y = cy + Math.sin(angle) * r;
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        path.closePath();
        return path;
    }

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
     * 判断是否启用像素字体最近邻缩放路径（rendering.md §2.4）。
     *
     * <p>M5-D4 修 Bug 3/4：只要 {@link FontRegistry.Metadata#pixelated()} 就走 NN，
     * 取消原"{@code targetSize} 必须是 {@code nativeSize} 的整数倍"限制。原因：像素字体
     * 本就设计为 {@code nativeSize}（12）点阵，任何 target size 都是放大——非整数倍时
     * 走 {@code drawString} 会让 Java2D 按字号插值出灰阶像素，与前端 NN 不一致。现在
     * 双端都从 {@code nativeSize} mask 用 NEAREST 拉伸到 {@code targetSize}，保证像素锐利 + 双端像素对齐。</p>
     */
    private static boolean shouldUseNearestNeighbor(FontRegistry.Registered reg, int targetSize) {
        FontRegistry.Metadata md = reg.metadata();
        return md.pixelated() && md.nativeSize() > 0 && targetSize > 0;
    }

    /**
     * 像素字体 fill/shadow 路径。M5-D6 Bug 7 终版：扫 mask 实际字形边界 + 手工 per-pixel NN。
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
            applyHints(mg);
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

    /**
     * 解析 {@code #RRGGBB} 或 {@code #RRGGBBAA} 为 AWT Color。
     *
     * <p>M10：alpha 通道支持。alpha 字段缺省时 = 255（不透明）；非空时 0-255 控制半透明。
     * 在 {@code TYPE_INT_RGB} 主 buffer 上 fill 时 Graphics2D 走 Porter-Duff SrcOver，
     * alpha < 255 的色与底层像素叠加（"颜色变浅"语义，同 docs/rendering.md §6.5 element opacity）。</p>
     */
    private static Color parseColor(String hex) {
        if (hex == null) return Color.WHITE;
        Matcher m = HEX_RE.matcher(hex);
        if (!m.matches()) return Color.WHITE;
        int rgb = Integer.parseInt(m.group(1), 16);
        int alpha = m.group(2) != null ? Integer.parseInt(m.group(2), 16) : 255;
        return new Color((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff, alpha);
    }

}
