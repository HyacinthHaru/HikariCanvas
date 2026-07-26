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
import ac.haru.hikaricanvas.template.TemplateRegistry;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 模板缩略图服务。给每个 {@code .canvas} pack 模板渲染一张缩略图供前端 Gallery 卡片显示。
 *
 * <p>策略：</p>
 * <ul>
 *   <li>缩略图 = 现解 {@link TemplateEntry#packBytes()}（用声明的 default 参数收敛 + {@code ${var:X}}
 *       收敛 fallback）成预览 {@link ProjectState}，再走 {@link CanvasCompositor} 完整渲染管线得到 RGBA
 *       buffer，最后 PNG 编码（{@link #renderPackPreview}）</li>
 *   <li>结果缓存在内存里 {@link #cache}，key = templateId；reload 时清空</li>
 *   <li>失败的模板（解包 / 物化失败等）→ 不缓存，下次请求重试。前端 fallback 显占位图</li>
 * </ul>
 */
public final class TemplatePreviewService {

    private final Logger log;
    private final TemplateRegistry registry;
    private final CanvasCompositor compositor;

    /**
     * key = templateId；value = PNG bytes。
     *
     * <p>渲染失败时 {@link #renderPackPreview} 返 {@code null}，而
     * {@link ConcurrentHashMap#computeIfAbsent} 对 null 返回值不写表——即失败结果
     * <b>不被缓存，下次请求会重新解 pack 重试</b>（与类 javadoc 策略一致）。
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
     * 单个模板失效。重新发布同 slug 时 templateId 不变（缓存 key 就是它），不主动清的话
     * Gallery 会一直显示上一版的缩略图，直到 {@code /canvas reload} 或重启。
     */
    public void invalidate(String templateId) {
        if (templateId != null) cache.remove(templateId);
    }

    /**
     * 取（或生成）某模板的 PNG bytes。线程安全：{@link ConcurrentHashMap#computeIfAbsent} 单 flight。
     * 模板不存在、调用方无权看、或渲染失败一律返回 {@code null}（前端 fallback 占位图）。
     *
     * <p><b>按调用方身份过滤</b>：缩略图是模板画布内容的完整渲染图，等于把别人私有模板的
     * 画面直接给出去。这里走与 {@code template.apply} 同一条隔离判定
     * （{@link TemplateRegistry#byIdForApply}）：builtin / server 模板人人可看，
     * user-templates 只有 owner（或持 bypass 的调用方）能看。<b>无法解析出调用方身份时
     * 传 {@code null}</b>，效果是只放行 builtin / server。</p>
     *
     * <p>越权与不存在都返回 {@code null}（同一个 404），不区分——否则这个端点就成了
     * "枚举别人有哪些模板"的探针。</p>
     *
     * @param callerUuid 请求方玩家 UUID；{@code null} 等同非 owner
     * @param hasBypass  调用方是否持 {@code canvas.template.use-others}
     */
    public byte[] pngFor(String templateId, java.util.UUID callerUuid, boolean hasBypass) {
        TemplateEntry entry;
        try {
            entry = registry.byIdForApply(templateId, callerUuid, hasBypass);
        } catch (ac.haru.hikaricanvas.template.ForbiddenTemplateException e) {
            return null;
        }
        if (entry == null || !entry.isPack()) return null;
        // computeIfAbsent 对 null 返回值不写表 → 渲染失败不缓存、下次请求重试。
        return cache.computeIfAbsent(templateId, id -> renderPackPreview(entry));
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

}
