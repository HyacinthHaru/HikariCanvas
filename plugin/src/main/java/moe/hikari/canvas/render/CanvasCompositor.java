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
import moe.hikari.canvas.variable.VariableInterpolator;
import moe.hikari.canvas.variable.VariableStore;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 把 {@link ProjectState} 光栅化成 ARGB 大图，再按 128×128 量化切片成 palette 字节数组。
 * 契约对应 {@code docs/rendering.md §1 / §4 / §6 / §7}。
 *
 * <h2>管线</h2>
 * <pre>
 *   ProjectState
 *      → {@link #rasterize} 产出 {@code widthMaps*128 × heightMaps*128} 的 ARGB BufferedImage
 *      → {@link #toPaletteSlice} 按 mapIndex 切 128×128、逐像素 {@link PaletteLut#matchColor} 量化
 * </pre>
 *
 * <h2>架构</h2>
 * 本类负责：rasterize / toPaletteSlice 公共 API；按 layer/element 分发；fast vs slow path
 * 选择；element rotation / opacity / dither 装饰；layer 合成。各 element 几何由
 * {@link ElementRenderer} 子类（{@link TextRenderer} / {@link RectRenderer} / {@link IconRenderer} /
 * {@link PathRenderer} / {@link CircleRenderer} / {@link ShapeRenderer} / {@link BrushRenderer} /
 * {@link ImageRenderer}）承担，共享 {@link RenderContext} + {@link FillPaintBuilder}。
 *
 * <h2>线程模型</h2>
 * 无可变状态（除 {@link #imageLoader} volatile 引用）；{@link #rasterize} 每次分配新 BufferedImage，
 * 多线程并发调用安全。{@link PaletteLut} / {@link FontRegistry} 都在构造时传入、稳态只读。
 *
 * <p><b>池化注：</b>装配 {@link BufferPool} 后，<b>仅池 owner 线程（AnimationTicker
 * 单线程）</b>的 rasterize 借用复用 buffer（主 + slow-path layer），其余线程
 * {@code acquire} 退化为 new —— 上述「每次 new 保证并发安全」契约对反应式路径不变。
 * 主 buffer 逃逸出本方法，Ticker 路径由调用方（{@code CanvasProjector.renderFrame}）
 * 用完经 {@link #releaseToPool} 归还。见 docs/architecture.md §5.5。</p>
 */
public final class CanvasCompositor {

    public static final int MAP_SIZE = 128;

    /**
     * ImageElement 文件加载 SAM。生产环境注入 {@code ImageStorage::load}；测试可注入
     * 从 test resources 加载的 lambda。返回 {@code null} → drawImage 走占位路径。
     */
    @FunctionalInterface
    public interface ImageLoader {
        BufferedImage load(String source);
    }

    /** 静态 logger 用于 {@code maybeInterpolateText} 静态路径。 */
    private static final Logger LOG = Logger.getLogger(CanvasCompositor.class.getName());

    private final PaletteLut paletteLut;
    private final RenderContext ctx;
    /** 图片加载器；null = 所有 ImageElement 走占位。 */
    private volatile ImageLoader imageLoader;
    /**
     * 变量占位符替换器。null = 不做替换（snapshot 测试 / 老路径完全无侵入）。
     * 由 {@code HikariCanvas.onEnable} 在 {@link VariableStore} 构造后注入。
     * volatile 保证多线程可见。
     */
    private volatile VariableInterpolator interpolator;
    /**
     * 变量存储（用于渲染结束时 {@code markWallReferences}）。
     * 与 {@link #interpolator} 同时被 {@link #setVariableSupport} 注入；为 null 表示不联动倒排索引。
     */
    private volatile VariableStore variableStore;

    /**
     * BufferedImage 复用池（线程限定，见类注释「池化注」）。null = 不池化，
     * 所有路径每次 new —— snapshot 测试 / 旧装配零侵入。
     */
    private volatile BufferPool bufferPool;

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
     * 完整构造，含 {@link IconRegistry} 注入（矢量 icon 渲染需要）。生产路径
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

    /** 启动期由 {@code HikariCanvas.onEnable} 注入；测试可传 lambda。 */
    public void setImageLoader(ImageLoader loader) {
        this.imageLoader = loader;
    }

    /**
     * 启动期由 {@code HikariCanvas.onEnable} 在变量系统构造后注入。
     *
     * <p>注入后 {@link #rasterize(ProjectState, String)} 与
     * {@link #rasterize(ProjectState)} 都会在 TextElement 路径上做 {@code ${var:X}} 替换；
     * 未注入则保持旧行为（snapshot baseline 不漂移）。</p>
     *
     * @param interpolator   占位符替换器；null 即关闭替换
     * @param variableStore  与 interpolator 共用的 store（用于 {@code markWallReferences}）；null 即关闭倒排索引联动
     */
    public void setVariableSupport(VariableInterpolator interpolator, VariableStore variableStore) {
        this.interpolator = interpolator;
        this.variableStore = variableStore;
    }

    /**
     * 装配 {@link BufferPool}（由 {@code HikariCanvas.onEnable} 注入；测试可不注）。
     * 池是线程限定的——仅 owner 线程（AnimationTicker）真正复用，其余线程行为不变。
     */
    public void setBufferPool(BufferPool pool) {
        this.bufferPool = pool;
    }

    /** 借 buffer（无池 / 非 owner 线程 → new，行为与池化前一致）。 */
    private BufferedImage acquireBuffer(int w, int h) {
        BufferPool pool = this.bufferPool;
        return pool != null ? pool.acquire(w, h)
                : new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    }

    /**
     * 归还 rasterize 借出的 buffer（主 buffer 由调用方在切片完成后调；
     * layer buffer 在 rasterize 内部合成后调）。无池 / 非 owner 线程 / null → no-op。
     */
    public void releaseToPool(BufferedImage img) {
        BufferPool pool = this.bufferPool;
        if (pool != null) pool.release(img);
    }

    /**
     * 把 {@link ProjectState} 渲染到整张大画布。返回 {@code TYPE_INT_ARGB}（含 alpha，
     * 让透明背景的 alpha 通道贯穿到 {@link #toPaletteSlice}）。
     *
     * <p>大小 = {@code (widthMaps*128) × (heightMaps*128)}；2×2 = 85 KiB、8×4 = 2 MiB、10×10 = 8.5 MiB。</p>
     *
     * <h3>分层渲染</h3>
     * <ul>
     *   <li><b>fast path</b>：层 opacity = 1 + blendMode = NORMAL + 层内无 element opacity/blendMode
     *       → 直接 draw 到主 buffer，与早期无分层行为完全等价（snapshot baseline 不漂移）</li>
     *   <li><b>slow path</b>：分配 ARGB 中间 buffer 画层内 element + element.opacity 用
     *       {@link AlphaComposite#SrcOver}.derive；最后用
     *       {@link BlendModes#applyBlendModeOver} 把层 buffer 按 layer.opacity / blendMode
     *       合成到主 buffer</li>
     * </ul>
     *
     * <p>层间 z-order = {@code state.layers()} 索引（0 = 底，越大越上）。</p>
     */
    public BufferedImage rasterize(ProjectState state) {
        return rasterize(state, null);
    }

    /**
     * 带 wallId 的渲染入口。{@code wallId} 仅用于 {@code ${var:user/X}} 占位符
     * 注入 + 渲染结束 {@link VariableStore#markWallReferences} 倒排索引维护。
     *
     * <p>预览 / 模板 publish / 测试路径传 null wallId（{@code ${var:user/X}} 走 fallback 链
     * → "???"）；生产投影路径（{@code CanvasProjector.project} / {@code WallRestorer.restore}）
     * 必须传非 null。</p>
     *
     * <p>未通过 {@link #setVariableSupport} 注入 interpolator 时本方法行为与 {@link #rasterize(ProjectState)}
     * 完全一致 —— snapshot 测试 baseline 0 漂移依赖此性质。</p>
     */
    public BufferedImage rasterize(ProjectState state, String wallId) {
        ProjectState.Canvas canvas = state.canvas();
        int widthPx = canvas.widthMaps() * MAP_SIZE;
        int heightPx = canvas.heightMaps() * MAP_SIZE;
        // 主 buffer 用 TYPE_INT_ARGB 让背景 alpha 通道在 toPaletteSlice 时
        // 能被读到（TYPE_INT_RGB 会强行合成不透明，导致 SolidFill("#00000000") 透明背景
        // 被吃掉）。toPaletteSlice 内的 matchColor 4 参重载在 alpha < 128 时返 palette index 0
        // （TRANSPARENT_INDEX），MC 地图渲染时该像素透出后方方块。内存 +33%（64→85 KiB 每 2×2 maps）
        // 经池借用（仅 Ticker 线程命中池；其余线程等价 new，见类注释「池化注」）。
        // 渲染中途抛异常时归还借出的 buffer（否则池利用率随异常缓降）再重抛。
        BufferedImage img = acquireBuffer(widthPx, heightPx);
        try {
            return rasterizeInto(img, state, wallId, widthPx, heightPx);
        } catch (RuntimeException | Error ex) {
            releaseToPool(img);
            throw ex;
        }
    }

    /** {@link #rasterize(ProjectState, String)} 的渲染主体（拆出以便异常路径统一归还 buffer）。 */
    private BufferedImage rasterizeInto(BufferedImage img, ProjectState state, String wallId,
                                        int widthPx, int heightPx) {
        ProjectState.Canvas canvas = state.canvas();
        Graphics2D g = img.createGraphics();
        // snapshot 取 volatile，整段 rasterize 内不变
        VariableInterpolator interp = this.interpolator;
        VariableStore store = this.variableStore;
        // 倒排索引累积容器；null = 本次 rasterize 不联动 store
        Set<String> referencedFullNames = (interp != null && store != null && wallId != null)
                ? new HashSet<>() : null;
        try {
            applyHints(g);
            // 背景：background 是 Fill 联合类型（solid / linear / radial），
            // 由 FillPaintBuilder.fillToPaint 渲染——bbox 取全画布以便渐变铺满。
            g.setPaint(FillPaintBuilder.fillToPaint(canvas.background(), 0, 0, widthPx, heightPx));
            g.fillRect(0, 0, widthPx, heightPx);

            // 不可变快照遍历，免疫 WS 编辑线程与变量驱动重画线程对 live ArrayList 的
            // 并发结构修改（ConcurrentModificationException / 撕裂读）。state.layers() 已是
            // unmodifiableList live 视图；这里 List.copyOf 锁住层引用，每层 elements 也各取
            // 一次浅拷贝（Element 是 record 不可变，只需复制容器）。调用方
            // ProjectionThrottler.flushLocked 在 EditSession 监视器内调本方法，保证这一步浅拷贝
            // 自身不与结构修改并发——拷贝完成后即便锁释放，迭代也只看快照。
            List<Layer> layerSnapshot = List.copyOf(state.layers());

            // 分层渲染
            for (Layer layer : layerSnapshot) {
                if (!layer.visible()) continue;
                // NaN-aware opacity 兜底；layer.opacity 非 finite → fallback 1.0
                float layerOpacity = ElementValidator.finiteOr(layer.opacity(), 1.0f);
                if (layerOpacity <= 0f) continue;
                List<Element> elementSnapshot = List.copyOf(layer.elements());
                if (elementSnapshot.isEmpty()) continue;

                if (canFastPath(layer, layerOpacity, elementSnapshot)) {
                    drawElementsTo(g, elementSnapshot, widthPx, heightPx,
                            interp, wallId, referencedFullNames);
                } else {
                    BufferedImage layerBuf = renderLayerToBuffer(elementSnapshot,
                            widthPx, heightPx, interp, wallId, referencedFullNames);
                    try {
                        BlendModes.applyBlendModeOver(img, layerBuf, layerOpacity, layer.blendMode());
                    } finally {
                        // layer buffer 不逃逸 rasterize，合成完即归还（非池路径 no-op）
                        releaseToPool(layerBuf);
                    }
                }
            }
        } finally {
            g.dispose();
        }
        // 把本次实际引用的 fullName 写回倒排索引（同时清掉上次记录但本次不在的）。
        // markWallReferences 是 ConcurrentHashMap 操作，幂等且线程安全 —— 多 session 同 wall
        // 同时 rasterize 重复调不会错乱。
        if (referencedFullNames != null) {
            try {
                store.markWallReferences(wallId, referencedFullNames);
            } catch (Exception ignore) {
                // 渲染主路径不应被倒排索引维护拖垮；warning 由 store 内部 log（如果有）
            }
        }
        return img;
    }

    /**
     * fast path 判定：层与层内所有 element 都为默认混合参数时直接画主 buffer，避免分配中间 ARGB
     * 缓冲与 per-pixel 合成。这是保证已有 fixture snapshot baseline 不漂移的关键。
     *
     * <p>fast path 检查 element 级 opacity / renderMode 是<b>硬约束</b>：
     * renderMode=DITHER 的 element 必须走 slow path（per-pixel 抖动），否则 dither 不生效。
     * 当前 element.blendMode 即使非 NORMAL 也走 fast path 等价处理（无合成）。</p>
     */
    private static boolean canFastPath(Layer layer, float sanitizedLayerOpacity,
                                       List<Element> elements) {
        if (sanitizedLayerOpacity < 1.0f) return false;
        if (layer.blendMode() != BlendMode.NORMAL) return false;
        // 遍历调用方已抓好的不可变快照（与本帧实际绘制的元素集合一致），
        // 不再二次读 live layer.elements()。
        for (Element e : elements) {
            Float op = e.opacity();
            // NaN opacity 视为非默认（避开 fast path），让 slow path 兜底 clamp
            if (op != null && (!Float.isFinite(op) || op < 1.0f)) return false;
            RenderMode rm = e.renderMode();
            if (rm != null && rm != RenderMode.CLEAN) return false;
        }
        return true;
    }

    /**
     * 在指定 Graphics2D 上按 z-order 画一组 element，含 element.opacity 与 renderMode=DITHER 处理。
     *
     * <p><b>dither element 路径</b>：分配一张 canvas 尺寸的 ARGB 中间 buffer，
     * 在其上画 element body（仍走 drawRect/Path/etc）+ rotation；然后跑
     * {@link BayerDither#apply} 量化抖动；最后用 element.opacity 透明地 drawImage 到 {@code g}。
     * 这种做法保证 dither 仅作用于该 element 自身的像素，不污染相邻 element 或层背景。</p>
     */
    private void drawElementsTo(Graphics2D g, List<Element> elements,
                                int widthPx, int heightPx,
                                VariableInterpolator interp, String wallId,
                                Set<String> referencedAccumulator) {
        Composite baseComposite = g.getComposite();
        for (Element e : elements) {
            if (!e.visible()) continue;
            // TextElement 替换 ${var:X} 占位符 → 用替换后的副本走后续 dispatch。
            // 替换是位置不变的 record copy，rotation / opacity / dither 装饰路径完全不变。
            Element rendered = maybeInterpolateText(e, interp, wallId, referencedAccumulator);
            // opacity 经 ElementValidator.parseOpacityNullable 入口已挡 NaN，
            // 但模板 raw_state 反序列化绕过路径可能漏；finiteOr 兜底到 1.0
            float opacity = ElementValidator.finiteOr(rendered.effectiveOpacity(), 1.0f);
            // clamp 入 [0, 1]：协议入口允许 [0,1]，这里防御性 clamp 保证 AlphaComposite 不抛
            if (opacity < 0f) opacity = 0f;
            else if (opacity > 1f) opacity = 1f;

            if (rendered.effectiveRenderMode() == RenderMode.DITHER) {
                drawDitheredElement(g, rendered, opacity, widthPx, heightPx);
                continue;
            }

            AffineTransform savedTx = null;
            // rotation() 是 int 不会 NaN；只判 != 0 即可
            if (rendered.rotation() != 0) {
                savedTx = g.getTransform();
                double cx = rendered.x() + rendered.w() / 2.0;
                double cy = rendered.y() + rendered.h() / 2.0;
                g.rotate(Math.toRadians(rendered.rotation()), cx, cy);
            }
            if (opacity < 1.0f) {
                g.setComposite(AlphaComposite.SrcOver.derive(opacity));
            }
            drawElementBody(g, rendered);
            if (opacity < 1.0f) g.setComposite(baseComposite);
            if (savedTx != null) g.setTransform(savedTx);
        }
    }

    /**
     * TextElement 内含 {@code ${var:X}} 时返回替换后的 record 副本；其他元素 / 纯文本
     * 直接返原 {@code e}。{@code interp == null} 或 {@code text} 空也走 passthrough。
     *
     * <p>{@code referencedAccumulator} 不为 null 时会把本次引用的 fullName 累计进去
     * （供 rasterize 末尾 {@code markWallReferences}）。</p>
     */
    private static Element maybeInterpolateText(Element e, VariableInterpolator interp,
                                                String wallId, Set<String> referencedAccumulator) {
        if (interp == null) return e;
        if (!(e instanceof TextElement t)) return e;
        String src = t.text();
        if (src == null || src.isEmpty() || src.indexOf("${var:") < 0) return e;
        VariableInterpolator.Result r = interp.interpolate(src, wallId);
        if (referencedAccumulator != null) {
            referencedAccumulator.addAll(r.referencedFullNames());
        }
        String rendered = r.text();
        // interpolator 已做 MAX_INTERPOLATE_DEPTH 二次扫描；若仍含
        // ${var:} 字面 = 数据损坏或无法收敛，强制全替换为 "???" 防 wall 显字面 placeholder。
        if (rendered != null && rendered.indexOf("${var:") >= 0) {
            // 闭合大括号设为可选 `}?`，让未闭合的 `${var:foo`（行尾缺 `}`）也被兜底替换，
            // 否则残缺占位符会原样漏到 wall 显字面字符串。[^}]* 贪心吃到下一个 } 或行尾。
            rendered = rendered.replaceAll("\\$\\{var:[^}]*\\}?", VariableInterpolator.UNRESOLVED);
            LOG.warning("[CanvasCompositor] residual ${var:} after interpolate; replaced with "
                    + VariableInterpolator.UNRESOLVED);
        }
        // 替换文本相同 → 不分配新 record（极端情况：占位符全 resolve 出与原字符串等同的文本，罕见）
        if (src.equals(rendered)) return e;
        return new TextElement(
                t.id(), t.x(), t.y(), t.w(), t.h(), t.rotation(),
                t.locked(), t.visible(),
                rendered,
                t.fontId(), t.fontSize(), t.color(), t.align(),
                t.letterSpacing(), t.lineHeight(), t.vertical(),
                t.effects(),
                t.opacity(), t.blendMode(), t.renderMode(),
                t.bold(), t.italic());
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
     * <p><b>buffer 按 element bbox 分配</b>（含 rotation 包围盒）而非整 canvas。
     * 配合 32 maps 上限，避免 4096×4096×ARGB = 64 MiB transient × N dither
     * element 的 OOM 风险；常规小元素只占 几 KiB ~ 几百 KiB。</p>
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
            // italic TextElement 走 shear(-0.2, 0) 变换，字形会在左右缘各溢出最多
            // ceil(0.2 * h)（行高 20% 即最大水平偏移）。dither buffer 若只裁到元素 bbox 会切掉
            // 倾斜后探出的字形像素。给非旋转分支的 italic 文本左右各扩 shear padding；
            // translate(-clipX,-clipY) 与 BayerDither phase 仍按扩后坐标传入，dither 相位不变。
            if (e instanceof TextElement t && Boolean.TRUE.equals(t.italic())) {
                int shearPad = (int) Math.ceil(0.2 * Math.abs(e.h()));
                bbX -= shearPad;
                bbW += shearPad * 2;
            }
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

    /**
     * slow path：把 layer 内 element 画到独立 ARGB buffer（透明背景）。
     *
     * <p>{@code elements} 是调用方已抓的不可变快照（{@link #rasterize} 内
     * {@code List.copyOf(layer.elements())}），不再二次读 live {@code layer.elements()}，
     * 保证 fast/slow path 渲染同一份元素集合且免疫并发结构修改。layer 级标量
     * （blendMode / opacity）已在调用方 {@link #canFastPath} 处判过，这里不再需要。</p>
     */
    private BufferedImage renderLayerToBuffer(List<Element> elements,
                                              int widthPx, int heightPx,
                                              VariableInterpolator interp, String wallId,
                                              Set<String> referencedAccumulator) {
        // 经池借用（调用方 rasterize 在合成后 releaseToPool；非池路径等价 new）。
        // 绘制中途抛异常 → 归还后重抛。
        BufferedImage buf = acquireBuffer(widthPx, heightPx);
        Graphics2D lg = buf.createGraphics();
        try {
            applyHints(lg);
            drawElementsTo(lg, elements, widthPx, heightPx,
                    interp, wallId, referencedAccumulator);
        } catch (RuntimeException | Error ex) {
            releaseToPool(buf);
            throw ex;
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
                int a = (rgb >>> 24) & 0xff;
                int r = (rgb >> 16) & 0xff;
                int gg = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                // 4 参 matchColor — alpha < ALPHA_THRESHOLD(128) 时返
                // TRANSPARENT_INDEX(0)，MC 地图渲染时该像素透出 ItemFrame 后方方块。
                // 不透明像素（alpha >= 128）走 3 参 LUT 匹配路径，零差异。
                out[base + x] = paletteLut.matchColor(r, gg, b, a);
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
