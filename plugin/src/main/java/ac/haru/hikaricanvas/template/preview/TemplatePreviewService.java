package ac.haru.hikaricanvas.template.preview;

import ac.haru.hikaricanvas.canvasfile.CanvasArchive;
import ac.haru.hikaricanvas.canvasfile.CanvasImportException;
import ac.haru.hikaricanvas.canvasfile.CanvasManifest;
import ac.haru.hikaricanvas.canvasfile.PackParamResolver;
import ac.haru.hikaricanvas.canvasfile.ProjectImporter;
import ac.haru.hikaricanvas.canvasfile.ProjectMaterializer;
import ac.haru.hikaricanvas.render.CanvasCompositor;
import ac.haru.hikaricanvas.state.Element;
import ac.haru.hikaricanvas.state.Layer;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.state.TextElement;
import ac.haru.hikaricanvas.template.TemplateEntry;
import ac.haru.hikaricanvas.template.TemplateInstantiator;
import ac.haru.hikaricanvas.template.TemplateParam;
import ac.haru.hikaricanvas.template.TemplateRegistry;
import ac.haru.hikaricanvas.template.TemplateSpec;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 *
 * <p><b>两种载体：</b> YAML 模板走 {@link #renderPreview}（{@code TemplateInstantiator} 实例化）；
 * {@code .canvas} pack（{@link TemplateEntry#isPack()}）的合成 spec 无 layout/rawState，走
 * {@link #renderPackPreview}——现解 {@link TemplateEntry#packBytes()} 成预览 {@link ProjectState}
 * （default 参数收敛 + {@code ${var:X}} 收敛 fallback），再走同一条 {@link CanvasCompositor}。两条路径
 * 共用同一 cache 与「失败 null 不缓存、下次重试」语义。</p>
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
        // pack（.canvas）与 YAML 两条渲染路径共用同一 cache（key=templateId）+「失败 null 不缓存、
        // 下次重试」语义（computeIfAbsent 对 null 返回值不写表）。pack 的合成 spec 无 layout/rawState，
        // 走 renderPreview 必失败——必须分流到 renderPackPreview 现解 packBytes。
        return entry.isPack()
                ? cache.computeIfAbsent(templateId, id -> renderPackPreview(entry))
                : cache.computeIfAbsent(templateId, id -> renderPreview(entry.spec()));
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
            // 缩略图不接变量数据源，把 ${var:X|fallback=Y} 收敛成 Y（= 无数据时的样子），
            // 否则动态模板预览会渲出一串占位符字面。运行期 wall 渲染仍走真正的变量解析。
            List<Element> previewElements = resolvePlaceholdersForPreview(ok.elements());
            ProjectState state = stateOf(dims[0], dims[1], ok.backgroundColor(), previewElements);
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

    // ================= .canvas pack 预览 =================

    /**
     * pack 解包上限。服主 / 玩家已落盘的本地文件（非公网上传），给宽松上限——仅防病态 zip 炸弹在预览
     * 渲染期 OOM。与 {@link TemplateRegistry} 注册期的 {@code PACK_LOAD_MAX_*} 同值、各自独立。
     */
    private static final long PACK_PREVIEW_MAX_ZIP_BYTES = 64L * 1024 * 1024;    // 64 MB
    private static final long PACK_PREVIEW_MAX_ENTRY_BYTES = 64L * 1024 * 1024;  // 64 MB
    private static final long PACK_PREVIEW_MAX_TOTAL_BYTES = 256L * 1024 * 1024; // 256 MB

    /**
     * pack 缩略图：现解 {@link TemplateEntry#packBytes()} 成预览 {@link ProjectState}，再走与 YAML 路径
     * 同一条 {@link CanvasCompositor} 栅格化 + PNG 编码。解包 / 物化失败（{@link CanvasImportException}）
     * 或编码失败 → 记 warn + 返 {@code null}（不缓存、下次重试），与 {@link #renderPreview} 容错纪律一致。
     */
    private byte[] renderPackPreview(TemplateEntry entry) {
        try {
            ProjectState state = packStateForPreview(entry);
            BufferedImage rgba = compositor.rasterize(state);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
            ImageIO.write(rgba, "PNG", baos);
            log.fine("[preview] rendered pack " + entry.spec().id() + " "
                    + rgba.getWidth() + "x" + rgba.getHeight() + " → " + baos.size() + " B");
            return baos.toByteArray();
        } catch (CanvasImportException e) {
            log.warning("[preview] pack decode failed for " + entry.spec().id()
                    + ": " + e.code() + " — " + e.getMessage());
            return null;
        } catch (IOException e) {
            log.log(Level.WARNING, "[preview] PNG encode failed for pack " + entry.spec().id(), e);
            return null;
        } catch (Exception e) {
            log.log(Level.WARNING, "[preview] pack render failed for " + entry.spec().id(), e);
            return null;
        }
    }

    /**
     * 把 pack 字节现解成一份预览用 {@link ProjectState}（不含渲染，故不依赖 {@code compositor} / registry，
     * 可裸测）。步骤与 {@code ProjectImporter.applyPack} 的 build 前段对齐：
     * <ol>
     *   <li>{@link CanvasArchive#unpack} 安全解包（宽松上限，仅防 zip 炸弹）；</li>
     *   <li>{@link CanvasManifest#parse} 取墙尺寸；</li>
     *   <li>有 {@code params.json} → {@link PackParamResolver#resolve} 用 default 收敛（预览无用户填值），
     *       无则空 map；</li>
     *   <li>{@link PackParamResolver#substitute} 在 {@code project.json} 文本层替换 {@code ${param}}；</li>
     *   <li>{@link ProjectMaterializer#materialize} 物化 + 元素校验（喂 manifest 墙尺寸当会话尺寸，
     *       pack 自洽必过尺寸匹配）；</li>
     *   <li>{@link #convergePreviewVars} 逐层把 {@code ${var:X|fallback=Y}} 收敛成 Y（缩略图不接变量数据源）。</li>
     * </ol>
     */
    // package-private for test
    ProjectState packStateForPreview(TemplateEntry entry) throws CanvasImportException {
        CanvasArchive.Limits limits = new CanvasArchive.Limits(
                PACK_PREVIEW_MAX_ZIP_BYTES, PACK_PREVIEW_MAX_ENTRY_BYTES, PACK_PREVIEW_MAX_TOTAL_BYTES);
        Map<String, byte[]> zipEntries = CanvasArchive.unpack(entry.packBytes(), limits);
        CanvasManifest manifest = CanvasManifest.parse(
                zipEntries.get("manifest.json"), ProjectImporter.CANVAS_SPEC_MAX);
        byte[] paramsJson = zipEntries.get("params.json");
        // 预览无用户填值：有 params.json 就用声明的 default 收敛，无则空 map（substitute 对无占位符文本 no-op）。
        Map<String, Object> values = paramsJson != null
                ? PackParamResolver.resolve(PackParamResolver.parse(paramsJson), Map.of())
                : Map.of();
        String projectJson = new String(zipEntries.get("project.json"), StandardCharsets.UTF_8);
        String substituted = PackParamResolver.substitute(projectJson, values);
        ProjectState state = ProjectMaterializer.materialize(
                substituted.getBytes(StandardCharsets.UTF_8),
                manifest.wallWidth(), manifest.wallHeight());
        return convergePreviewVars(state);
    }

    /**
     * 逐层把每个 {@link TextElement} 的 {@code ${var:X|fallback=Y}} 收敛成预览态（复用 YAML 路径的
     * {@link #resolvePlaceholdersForPreview}），再重建 {@link ProjectState} 仅替换 layers。重建照
     * {@code ProjectImporter.stripOrphanTracksAndCollect} 的 9 参构造，保留 version / canvas /
     * activeLayerId / timelines / activeTimelineId / tweenFps（history 预览不带、传 null）。
     */
    private static ProjectState convergePreviewVars(ProjectState state) {
        List<Layer> converged = new java.util.ArrayList<>(state.layers().size());
        for (Layer l : state.layers()) {
            converged.add(new Layer(l.id(), l.name(), l.visible(), l.locked(),
                    l.opacity(), l.blendMode(), l.colorTag(),
                    resolvePlaceholdersForPreview(l.elements())));
        }
        return new ProjectState(state.version(), state.canvas(), /*v1Elements*/ null, converged,
                state.activeLayerId(), /*history*/ null,
                state.timelines(), state.activeTimelineId(), state.tweenFps());
    }

    /** {@code ${var:NAME|fallback=VALUE}}（含冒号，与模板参数 {@code ${param}} 不同源）。 */
    private static final java.util.regex.Pattern PREVIEW_VAR =
            java.util.regex.Pattern.compile("\\$\\{var:([^|}]+)(?:\\|fallback=([^}]*))?\\}");

    /** 预览专用：文本里的运行时变量占位符收敛为 fallback 值（无 fallback 用变量名末段）。 */
    private static List<Element> resolvePlaceholdersForPreview(List<Element> elements) {
        List<Element> out = new java.util.ArrayList<>(elements.size());
        for (Element el : elements) {
            if (el instanceof TextElement t && t.text() != null && t.text().contains("${var:")) {
                out.add(withText(t, previewResolve(t.text())));
            } else {
                out.add(el);
            }
        }
        return out;
    }

    /** package-private 供测试直接验证纯替换逻辑。 */
    static String previewResolve(String text) {
        java.util.regex.Matcher m = PREVIEW_VAR.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String fallback = m.group(2);
            String repl = fallback != null ? fallback
                    : m.group(1).substring(m.group(1).lastIndexOf('.') + 1);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static TextElement withText(TextElement t, String text) {
        return new TextElement(t.id(), t.x(), t.y(), t.w(), t.h(), t.rotation(),
                t.locked(), t.visible(), text, t.fontId(), t.fontSize(), t.color(),
                t.align(), t.letterSpacing(), t.lineHeight(), t.vertical(), t.effects(),
                t.opacity(), t.blendMode(), t.renderMode(), t.bold(), t.italic());
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
