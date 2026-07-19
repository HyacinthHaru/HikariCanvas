package ac.haru.hikaricanvas.template.preview;

import ac.haru.hikaricanvas.render.CanvasCompositor;
import ac.haru.hikaricanvas.state.Element;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.template.TemplateEntry;
import ac.haru.hikaricanvas.template.TemplateInstantiator;
import ac.haru.hikaricanvas.template.TemplateParam;
import ac.haru.hikaricanvas.template.TemplateRegistry;
import ac.haru.hikaricanvas.template.TemplateSpec;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 模板缩略图服务。给每个模板渲染一张缩略图供前端 Gallery 卡片显示。
 *
 * <p>策略：</p>
 * <ul>
 *   <li>缩略图 = 用模板的 default 参数 + 一个"代表性 wall 尺寸"实例化，
 *       再走 {@link CanvasCompositor} 走完整渲染管线得到 RGBA buffer，最后 PNG 编码</li>
 *   <li>代表性尺寸 = {@code clamp(min_maps, max_maps, [4, 1])}（在范围内尽量取宽长比 4:1 的标牌形状）</li>
 *   <li>结果缓存在内存里 {@link #cache}，key = templateId；reload 时清空</li>
 *   <li>失败的模板（实例化时 INVALID_PARAM 等）→ 不缓存，下次请求重试。前端 fallback 显占位图</li>
 * </ul>
 */
public final class TemplatePreviewService {

    private final Logger log;
    private final TemplateRegistry registry;
    private final CanvasCompositor compositor;
    private final TemplateInstantiator instantiator = new TemplateInstantiator();

    /**
     * key = templateId；value = PNG bytes。
     *
     * <p>渲染失败时 {@link #renderPreview} 返 {@code null}，而
     * {@link ConcurrentHashMap#computeIfAbsent} 对 null 返回值不写表——即失败结果
     * <b>不被缓存，下次请求会重新实例化重试</b>（与类 javadoc 策略一致）。
     * 故此处不存在"失败负缓存、本轮不重试"语义。</p>
     */
    private final ConcurrentHashMap<String, byte[]> cache = new ConcurrentHashMap<>();

    public TemplatePreviewService(Logger log, TemplateRegistry registry, CanvasCompositor compositor) {
        this.log = log;
        this.registry = registry;
        this.compositor = compositor;
    }

    /** Registry reload 后调一次。 */
    public void invalidate() { cache.clear(); }

    /**
     * 取（或生成）某模板的 PNG bytes。线程安全：{@link ConcurrentHashMap#computeIfAbsent} 单 flight。
     * 模板不存在或渲染失败返回 {@code null}（前端 fallback 占位图）。
     */
    public byte[] pngFor(String templateId) {
        TemplateEntry entry = registry.byId(templateId);
        if (entry == null) return null;
        return cache.computeIfAbsent(templateId, id -> renderPreview(entry.spec()));
    }

    private byte[] renderPreview(TemplateSpec spec) {
        try {
            int[] dims = chooseDimensions(spec);
            Map<String, Object> defaults = collectDefaults(spec);
            TemplateInstantiator.Result r = instantiator.instantiate(spec, defaults, dims[0], dims[1]);
            if (r instanceof TemplateInstantiator.Result.Failed f) {
                log.warning("[preview] " + spec.id() + " instantiate failed: "
                        + f.code() + " — " + String.join("; ", f.errors()));
                return null;
            }
            TemplateInstantiator.Result.Ok ok = (TemplateInstantiator.Result.Ok) r;
            ProjectState state = stateOf(dims[0], dims[1], ok.backgroundColor(), ok.elements());
            BufferedImage rgba = compositor.rasterize(state);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
            ImageIO.write(rgba, "PNG", baos);
            log.fine("[preview] rendered " + spec.id() + " "
                    + rgba.getWidth() + "x" + rgba.getHeight() + " → " + baos.size() + " B");
            return baos.toByteArray();
        } catch (IOException e) {
            log.log(Level.WARNING, "[preview] PNG encode failed for " + spec.id(), e);
            return null;
        } catch (Exception e) {
            log.log(Level.WARNING, "[preview] render failed for " + spec.id(), e);
            return null;
        }
    }

    /** 优先 4×1，缺则收敛到 [min, max] 内尽量接近的尺寸。 */
    private static int[] chooseDimensions(TemplateSpec spec) {
        int wantW = 4, wantH = 1;
        if (spec.canvas() == null) return new int[]{wantW, wantH};
        if ("fixed".equals(spec.canvas().size()) && spec.canvas().maps() != null
                && spec.canvas().maps().size() == 2) {
            return new int[]{spec.canvas().maps().get(0), spec.canvas().maps().get(1)};
        }
        List<Integer> min = spec.canvas().minMaps();
        List<Integer> max = spec.canvas().maxMaps();
        int minW = min != null ? min.get(0) : 1;
        int minH = min != null ? min.get(1) : 1;
        int maxW = max != null ? max.get(0) : 8;
        int maxH = max != null ? max.get(1) : 4;
        int w = Math.min(maxW, Math.max(minW, wantW));
        int h = Math.min(maxH, Math.max(minH, wantH));
        return new int[]{w, h};
    }

    private static Map<String, Object> collectDefaults(TemplateSpec spec) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (spec.params() == null) return out;
        for (var e : spec.params().entrySet()) {
            TemplateParam p = e.getValue();
            if (p == null) continue;
            if (p.defaultValue() != null) {
                out.put(e.getKey(), p.defaultValue());
                continue;
            }
            // 非必填可省；必填但无默认 → 给一个占位（避免 INVALID_PARAM）
            if (p.required()) {
                out.put(e.getKey(), placeholderFor(p));
            }
        }
        return out;
    }

    private static Object placeholderFor(TemplateParam p) {
        return switch (p.type() == null ? "string" : p.type()) {
            case "int" -> 0;
            case "float" -> 0.0;
            case "bool" -> Boolean.FALSE;
            case "color" -> "#888888";
            default -> "—";
        };
    }

    /**
     * 直接构造 {@link ProjectState}（public 构造器 + {@link ProjectState#addElement}），
     * 不走 Jackson。
     *
     * <p>历史上这里用 {@code mapper.convertValue(Map.of("elements", elements), ...)}
     * 序列化装配——{@code Map.of} 把 {@code List<Element>} 的值类型擦成 {@code Object}，
     * 导致 {@link Element} 的 {@code @JsonTypeInfo(property="type")} 不写出 {@code type}
     * 判别符，反序列化 {@code List<Element>} 多态时抛 {@code InvalidTypeIdException}，
     * 所有含 ≥1 元素的模板预览必崩（{@code StatePatchBuilder} javadoc 记有同坑）。
     * 直接构造零序列化、零 type 丢失。</p>
     */
    // package-private for test
    ProjectState stateOf(int widthMaps, int heightMaps, String background,
                                 List<Element> elements) {
        ProjectState state = new ProjectState(widthMaps, heightMaps,
                background == null ? "#FFFFFF" : background);
        for (Element e : elements) state.addElement(e);
        return state;
    }
}
