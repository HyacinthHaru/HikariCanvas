package moe.hikari.canvas.render;

import moe.hikari.canvas.state.BlendMode;
import moe.hikari.canvas.state.BrushStrokeElement;
import moe.hikari.canvas.state.CircleElement;
import moe.hikari.canvas.state.Element;
import moe.hikari.canvas.state.ElementValidator;
import moe.hikari.canvas.state.IconElement;
import moe.hikari.canvas.state.ImageElement;
import moe.hikari.canvas.state.Layer;
import moe.hikari.canvas.state.PathElement;
import moe.hikari.canvas.state.ProjectState;
import moe.hikari.canvas.state.RectElement;
import moe.hikari.canvas.state.RenderMode;
import moe.hikari.canvas.state.ShapeElement;
import moe.hikari.canvas.state.TextElement;
import moe.hikari.canvas.template.asset.TemplateAssetService;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.logging.Logger;

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
 * <h2>架构（2026-05-14 god class 拆分后）</h2>
 * 本类负责：rasterize / toPaletteSlice 公共 API；按 layer/element 分发；fast vs slow path
 * 选择；element rotation / opacity / dither 装饰；layer 合成。各 element 几何由
 * {@link ElementRenderer} 子类（{@link TextRenderer} / {@link RectRenderer} / {@link IconRenderer} /
 * {@link PathRenderer} / {@link CircleRenderer} / {@link ShapeRenderer} / {@link BrushRenderer} /
 * {@link ImageRenderer}）承担，共享 {@link RenderContext} + {@link FillPaintBuilder}。
 *
 * <h2>线程模型</h2>
 * 无可变状态（除 {@link #imageLoader} volatile 引用）；{@link #rasterize} 每次分配新 BufferedImage，
 * 多线程并发调用安全。{@link PaletteLut} / {@link FontRegistry} 都在构造时传入、稳态只读。
 */
public final class CanvasCompositor {

    public static final int MAP_SIZE = 128;

    /**
     * M13 ImageElement 文件加载 SAM。生产环境注入 {@code ImageStorage::load}；测试可注入
     * 从 test resources 加载的 lambda。返回 {@code null} → drawImage 走占位路径。
     */
    @FunctionalInterface
    public interface ImageLoader {
        BufferedImage load(String source);
    }

    private final PaletteLut paletteLut;
    private final RenderContext ctx;
    /** M13：图片加载器；null = 所有 ImageElement 走占位。 */
    private volatile ImageLoader imageLoader;

    // 8 个 element renderer，构造时一次性 instantiate
    private final ElementRenderer textRenderer = new TextRenderer();
    private final ElementRenderer rectRenderer = new RectRenderer();
    private final ElementRenderer iconRenderer = new IconRenderer();
    private final ElementRenderer pathRenderer = new PathRenderer();
    private final ElementRenderer circleRenderer = new CircleRenderer();
    private final ElementRenderer shapeRenderer = new ShapeRenderer();
    private final ElementRenderer brushRenderer = new BrushRenderer();
    private final ElementRenderer imageRenderer = new ImageRenderer();

    public CanvasCompositor(PaletteLut paletteLut, FontRegistry fontRegistry, Logger log) {
        this(paletteLut, fontRegistry, null, null, log);
    }

    public CanvasCompositor(PaletteLut paletteLut, FontRegistry fontRegistry,
                            TemplateAssetService assetService, Logger log) {
        this(paletteLut, fontRegistry, assetService, null, log);
    }

    /**
     * M26.2：完整构造，含 {@link IconRegistry} 注入（矢量 icon 渲染需要）。生产路径
     * （{@code HikariCanvas.onEnable}）走这条；测试 / 老 fixture 仍可走前两个无 iconRegistry
     * 构造（IconRenderer 对 SVG 元素退占位）。
     */
    public CanvasCompositor(PaletteLut paletteLut, FontRegistry fontRegistry,
                            TemplateAssetService assetService, IconRegistry iconRegistry,
                            Logger log) {
        this.paletteLut = paletteLut;
        this.ctx = new RenderContext(paletteLut, fontRegistry, assetService, iconRegistry,
                log, () -> this.imageLoader);
    }

    /** M13：启动期由 {@code HikariCanvas.onEnable} 注入；测试可传 lambda。 */
    public void setImageLoader(ImageLoader loader) {
        this.imageLoader = loader;
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
            // 背景：M17 F5 起 background 是 Fill 联合类型（solid / linear / radial），
            // 由 FillPaintBuilder.fillToPaint 渲染——bbox 取全画布以便渐变铺满。
            g.setPaint(FillPaintBuilder.fillToPaint(canvas.background(), 0, 0, widthPx, heightPx));
            g.fillRect(0, 0, widthPx, heightPx);

            // M8-E：分层渲染
            for (Layer layer : state.layers()) {
                if (!layer.visible()) continue;
                // M16 P3.2：NaN-aware opacity 兜底；layer.opacity 非 finite → fallback 1.0
                float layerOpacity = ElementValidator.finiteOr(layer.opacity(), 1.0f);
                if (layerOpacity <= 0f) continue;
                if (layer.elements().isEmpty()) continue;

                if (canFastPath(layer, layerOpacity)) {
                    drawElementsTo(g, layer.elements(), widthPx, heightPx);
                } else {
                    BufferedImage layerBuf = renderLayerToBuffer(layer, widthPx, heightPx);
                    BlendModes.applyBlendModeOver(img, layerBuf, layerOpacity, layer.blendMode());
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
    private static boolean canFastPath(Layer layer, float sanitizedLayerOpacity) {
        if (sanitizedLayerOpacity < 1.0f) return false;
        if (layer.blendMode() != BlendMode.NORMAL) return false;
        for (Element e : layer.elements()) {
            Float op = e.opacity();
            // M16 P3.2：NaN opacity 视为非默认（避开 fast path），让 slow path 兜底 clamp
            if (op != null && (!Float.isFinite(op) || op < 1.0f)) return false;
            RenderMode rm = e.renderMode();
            if (rm != null && rm != RenderMode.CLEAN) return false;
        }
        return true;
    }

    /**
     * 在指定 Graphics2D 上按 z-order 画一组 element，含 element.opacity 与 renderMode=DITHER 处理。
     *
     * <p><b>dither element 路径（M11-B）</b>：分配一张 canvas 尺寸的 ARGB 中间 buffer，
     * 在其上画 element body（仍走 drawRect/Path/etc）+ rotation；然后跑
     * {@link BayerDither#apply} 量化抖动；最后用 element.opacity 透明地 drawImage 到 {@code g}。
     * 这种做法保证 dither 仅作用于该 element 自身的像素，不污染相邻 element 或层背景。</p>
     */
    private void drawElementsTo(Graphics2D g, List<Element> elements,
                                int widthPx, int heightPx) {
        Composite baseComposite = g.getComposite();
        for (Element e : elements) {
            if (!e.visible()) continue;
            // M16 P3.2：opacity 经 ElementValidator.parseOpacityNullable 入口已挡 NaN，
            // 但模板 raw_state 反序列化绕过路径可能漏；finiteOr 兜底到 1.0
            float opacity = ElementValidator.finiteOr(e.effectiveOpacity(), 1.0f);
            // clamp 入 [0, 1]：协议入口允许 [0,1]，这里防御性 clamp 保证 AlphaComposite 不抛
            if (opacity < 0f) opacity = 0f;
            else if (opacity > 1f) opacity = 1f;

            if (e.effectiveRenderMode() == RenderMode.DITHER) {
                drawDitheredElement(g, e, opacity, widthPx, heightPx);
                continue;
            }

            AffineTransform savedTx = null;
            // rotation() 是 int 不会 NaN；只判 != 0 即可
            if (e.rotation() != 0) {
                savedTx = g.getTransform();
                double cx = e.x() + e.w() / 2.0;
                double cy = e.y() + e.h() / 2.0;
                g.rotate(Math.toRadians(e.rotation()), cx, cy);
            }
            if (opacity < 1.0f) {
                g.setComposite(AlphaComposite.SrcOver.derive(opacity));
            }
            drawElementBody(g, e);
            if (opacity < 1.0f) g.setComposite(baseComposite);
            if (savedTx != null) g.setTransform(savedTx);
        }
    }

    /** 单 element 几何绘制 dispatch（不含 opacity / rotation / dither 装饰）。 */
    private void drawElementBody(Graphics2D g, Element e) {
        ElementRenderer r = rendererFor(e);
        if (r != null) r.draw(g, e, ctx);
    }

    private ElementRenderer rendererFor(Element e) {
        return switch (e) {
            case TextElement ignored -> textRenderer;
            case RectElement ignored -> rectRenderer;
            case IconElement ignored -> iconRenderer;
            case PathElement ignored -> pathRenderer;
            case CircleElement ignored -> circleRenderer;
            case ShapeElement ignored -> shapeRenderer;
            case BrushStrokeElement ignored -> brushRenderer;
            case ImageElement ignored -> imageRenderer;
        };
    }

    /**
     * dither element：在独立 ARGB buffer 上画 + 跑 Bayer 抖动 → blend 回主 graphics。
     *
     * <p><b>M15.4 P0-Render-2</b>：buffer 按 element bbox（含 rotation 包围盒）而非整 canvas
     * 分配。配合 P0-Render-1 的 32 maps 上限，原来 4096×4096×ARGB = 64 MiB transient × N dither
     * element 的 OOM 风险消除；常规小元素只占 几 KiB ~ 几百 KiB。</p>
     *
     * <p><b>dither 相位保持</b>：buffer 局部坐标 (0,0) 对应原画 (clipX, clipY)，调用
     * {@link BayerDither#apply(BufferedImage, PaletteLut, int, int)} 传 phase=(clipX, clipY)
     * 让 Bayer 矩阵以原画坐标取阈值——snapshot baseline 不漂移、跨 element 边界不错位。</p>
     */
    private void drawDitheredElement(Graphics2D g, Element e, float opacity,
                                      int widthPx, int heightPx) {
        // bbox 外接矩形（含 rotation padding）。简化：任意角度的外接 = bbox 对角线 √(w²+h²)
        int bbX, bbY, bbW, bbH;
        if (e.rotation() != 0) {
            int diagonal = (int) Math.ceil(Math.hypot(e.w(), e.h()));
            int cx = e.x() + e.w() / 2;
            int cy = e.y() + e.h() / 2;
            bbX = cx - diagonal / 2;
            bbY = cy - diagonal / 2;
            bbW = diagonal;
            bbH = diagonal;
        } else {
            bbX = e.x();
            bbY = e.y();
            bbW = e.w();
            bbH = e.h();
        }
        // 与 canvas 相交
        int clipX = Math.max(0, bbX);
        int clipY = Math.max(0, bbY);
        int clipW = Math.min(widthPx, bbX + bbW) - clipX;
        int clipH = Math.min(heightPx, bbY + bbH) - clipY;
        if (clipW <= 0 || clipH <= 0) return;

        BufferedImage buf = new BufferedImage(clipW, clipH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D eg = buf.createGraphics();
        try {
            applyHints(eg);
            // buf 局部 (0,0) 对应原画 (clipX, clipY)，drawElementBody 仍用原画坐标
            eg.translate(-clipX, -clipY);
            if (e.rotation() != 0) {
                double cx = e.x() + e.w() / 2.0;
                double cy = e.y() + e.h() / 2.0;
                eg.rotate(Math.toRadians(e.rotation()), cx, cy);
            }
            drawElementBody(eg, e);
        } finally {
            eg.dispose();
        }
        // phase = (clipX, clipY) 让 dither 图案以原画坐标为基准，与全画布 buffer 时等价
        BayerDither.apply(buf, paletteLut, clipX, clipY);
        Composite prev = g.getComposite();
        if (opacity < 1.0f) g.setComposite(AlphaComposite.SrcOver.derive(opacity));
        g.drawImage(buf, clipX, clipY, null);
        if (opacity < 1.0f) g.setComposite(prev);
    }

    /** slow path：把 layer 内 element 画到独立 ARGB buffer（透明背景）。 */
    private BufferedImage renderLayerToBuffer(Layer layer, int widthPx, int heightPx) {
        BufferedImage buf = new BufferedImage(widthPx, heightPx, BufferedImage.TYPE_INT_ARGB);
        Graphics2D lg = buf.createGraphics();
        try {
            applyHints(lg);
            drawElementsTo(lg, layer.elements(), widthPx, heightPx);
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
    // 包级 static：供 TextRenderer.drawPixelatedGlyph 等 renderer 复用，保持 mask buffer 与
    // 主 buffer 渲染 hint 一致（关键：FRACTIONALMETRICS_OFF 保证像素字体 NN 路径稳定）

    static void applyHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }
}
