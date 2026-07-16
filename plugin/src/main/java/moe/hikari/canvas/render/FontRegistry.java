package moe.hikari.canvas.render;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 字体注册表，契约见 {@code docs/rendering.md §2.2 / §2.3}。
 *
 * <h2>加载顺序（后者覆盖前者）</h2>
 * <ol>
 *   <li><b>内置字体</b>：jar 内 {@code /fonts/SourceHanSansSC-Regular.otf} 与
 *       {@code /fonts/ark-pixel-12px-monospaced-zh_cn.ttf}（由 Gradle
 *       {@code downloadFonts} 任务构建期拉取入 jar）</li>
 *   <li><b>外部字体</b>：{@code plugins/HikariCanvas/fonts/*.ttf}（{@code .otf}），
 *       扫描时 fileName 去扩展名作为 {@code fontId}；同名覆盖内置</li>
 * </ol>
 *
 * <h2>字号语义（rendering.md §2.4）</h2>
 * 字号单位 = 像素；{@link Font#deriveFont(float)} 的参数即字号。
 * 像素字体（{@link Metadata#pixelated()}）若用户字号非 {@code nativeSize} 的整数倍，
 * 由 CanvasCompositor 选最近邻缩放模式（本类只管加载）。
 *
 * <h2>线程</h2>
 * 加载期有写入，稳态后全局只读；{@link #get} / {@link #getOrDefault} 纯读线程安全。
 */
public final class FontRegistry {

    /** 默认 fontId（TextElement.fontId 为 null 或未知时回退到这个）。 */
    public static final String DEFAULT_FONT_ID = "ark_pixel";

    /** 内置字体清单：fontId → (classpath 路径, 元数据)。顺序保留便于 log。 */
    private static final Map<String, BuiltIn> BUILT_IN = new LinkedHashMap<>();

    static {
        BUILT_IN.put("ark_pixel", new BuiltIn(
                "/fonts/ark-pixel-12px-monospaced-zh_cn.ttf",
                new Metadata("Ark Pixel 12px", true, 12)));
        BUILT_IN.put("source_han_sans", new BuiltIn(
                "/fonts/SourceHanSansSC-Regular.otf",
                new Metadata("思源黑体 SC Regular", false, 0)));
        // 6 个正文字体（全 SIL OFL 1.1）
        BUILT_IN.put("source_han_serif", new BuiltIn(
                "/fonts/SourceHanSerifSC-Regular.otf",
                new Metadata("思源宋体 SC Regular", false, 0)));
        BUILT_IN.put("jetbrains_mono", new BuiltIn(
                "/fonts/JetBrainsMono-Regular.ttf",
                new Metadata("JetBrains Mono Regular", false, 0)));
        BUILT_IN.put("fira_code", new BuiltIn(
                "/fonts/FiraCode-Regular.ttf",
                new Metadata("Fira Code Regular", false, 0)));
        BUILT_IN.put("inter", new BuiltIn(
                "/fonts/Inter-Regular.otf",
                new Metadata("Inter Regular", false, 0)));
        BUILT_IN.put("noto_serif", new BuiltIn(
                "/fonts/NotoSerif-Regular.ttf",
                new Metadata("Noto Serif Regular", false, 0)));
        // 13 个艺术 / 装饰字体（全 SIL OFL 1.1）
        // 中文艺术 6
        BUILT_IN.put("smiley_sans", new BuiltIn(
                "/fonts/SmileySans-Oblique.otf",
                new Metadata("得意黑 Smiley Sans Oblique", false, 0)));
        BUILT_IN.put("ma_shan_zheng", new BuiltIn(
                "/fonts/MaShanZheng-Regular.ttf",
                new Metadata("马善政毛笔楷书 Ma Shan Zheng Regular", false, 0)));
        BUILT_IN.put("zcool_xiaowei", new BuiltIn(
                "/fonts/ZCOOLXiaoWei-Regular.ttf",
                new Metadata("站酷小薇 ZCOOL XiaoWei Regular", false, 0)));
        BUILT_IN.put("zcool_kuaile", new BuiltIn(
                "/fonts/ZCOOLKuaiLe-Regular.ttf",
                new Metadata("站酷快乐体 ZCOOL KuaiLe Regular", false, 0)));
        BUILT_IN.put("zcool_qingkehuangyou", new BuiltIn(
                "/fonts/ZCOOLQingKeHuangYou-Regular.ttf",
                new Metadata("站酷庆科黄油体 ZCOOL QingKe HuangYou Regular", false, 0)));
        BUILT_IN.put("lxgw_wenkai", new BuiltIn(
                "/fonts/LXGWWenKai-Regular.ttf",
                new Metadata("霞鹜文楷 LXGW WenKai Regular", false, 0)));
        // 西文装饰 7（permanent_marker Apache License 跳过，shadows_into_light 替代马克笔位）
        BUILT_IN.put("comic_neue", new BuiltIn(
                "/fonts/ComicNeue-Regular.ttf",
                new Metadata("Comic Neue Regular", false, 0)));
        BUILT_IN.put("pacifico", new BuiltIn(
                "/fonts/Pacifico-Regular.ttf",
                new Metadata("Pacifico Regular", false, 0)));
        BUILT_IN.put("lobster", new BuiltIn(
                "/fonts/Lobster-Regular.ttf",
                new Metadata("Lobster Regular", false, 0)));
        BUILT_IN.put("bangers", new BuiltIn(
                "/fonts/Bangers-Regular.ttf",
                new Metadata("Bangers Regular", false, 0)));
        BUILT_IN.put("shadows_into_light", new BuiltIn(
                "/fonts/ShadowsIntoLight.ttf",
                new Metadata("Shadows Into Light Regular", false, 0)));
        // caveat / dancing_script 是 variable font（google/fonts 无 static），AWT + 浏览器 Canvas
        // 取 default instance（wght=400）双端一致。
        BUILT_IN.put("caveat", new BuiltIn(
                "/fonts/Caveat-Variable.ttf",
                new Metadata("Caveat Regular", false, 0)));
        BUILT_IN.put("dancing_script", new BuiltIn(
                "/fonts/DancingScript-Variable.ttf",
                new Metadata("Dancing Script Regular", false, 0)));
        // overpass：Red Hat 的 FHWA Series 风格 sans（variable wght）；default 400 取静态等价。
        // bebas_neue：经典 DIN / Bahnschrift Condensed 替代（condensed sans，仅英文）。
        BUILT_IN.put("overpass", new BuiltIn(
                "/fonts/Overpass-Variable.ttf",
                new Metadata("Overpass Regular (FHWA-like)", false, 0)));
        BUILT_IN.put("bebas_neue", new BuiltIn(
                "/fonts/BebasNeue-Regular.ttf",
                new Metadata("Bebas Neue Regular (DIN-like)", false, 0)));
    }

    private final Logger log;
    private final Map<String, Registered> fonts = new HashMap<>();

    /**
     * 后台 metrics worker 线程引用（{@link #spawnMetricsWorker}）。持有它让
     * {@code HikariCanvas#onDisable} 能 {@link #shutdownMetricsWorker()} interrupt + join，
     * 避免热重载时旧 worker 仍跑（每用户字体 ~1-2s × N）+ pin 住旧 classloader 延迟 GC。
     * volatile：spawn / shutdown 可能跨线程读写。
     */
    private volatile Thread metricsWorker;

    public FontRegistry(Logger log) {
        this.log = log;
    }

    /** 从 classpath 加载所有内置字体；缺失字体 log WARN，不抛异常（fallback 到存在的）。 */
    public void loadBuiltIn() {
        for (var e : BUILT_IN.entrySet()) {
            String id = e.getKey();
            BuiltIn bi = e.getValue();
            try (InputStream in = FontRegistry.class.getResourceAsStream(bi.resourcePath)) {
                if (in == null) {
                    log.warning("FontRegistry: built-in font missing from classpath: "
                            + id + " (" + bi.resourcePath + ")；确认 ./gradlew downloadFonts 已跑过");
                    continue;
                }
                int format = bi.resourcePath.endsWith(".otf") ? Font.TRUETYPE_FONT : Font.TRUETYPE_FONT;
                // AWT 对 TTF/OTF 统一用 TRUETYPE_FONT 常量；OpenType 是 TrueType 的超集
                Font font = Font.createFont(format, in);
                fonts.put(id, new Registered(id, font, bi.metadata, "classpath:" + bi.resourcePath));
                log.info("FontRegistry: loaded built-in '" + id + "' ("
                        + bi.metadata.displayName + ", pixelated=" + bi.metadata.pixelated + ")");
            } catch (IOException | FontFormatException ex) {
                log.log(Level.WARNING, "FontRegistry: failed to load built-in " + id, ex);
            }
        }
    }

    /**
     * 扫描给定目录加载外部字体；fileName 去扩展名作为 fontId。
     * 目录不存在或扫描失败不抛异常，仅 log。
     *
     * <p><b>metrics 异步化：</b> {@link Font#createFont} + {@code fonts.put} 仍同步
     * 完成（其他模块 onEnable 后立即假定字体注册表 ready）；但 {@link FontMetricsTable#registerRuntime}
     * 扫 65k codepoints 每字体 ~1-2s 阻塞，N 个用户字体启动期累计 20s+。改为 daemon worker thread
     * 后台跑：</p>
     * <ul>
     *   <li>worker 完成前调用 {@link FontMetricsTable#advance} 返 -1，调用方走 canonicalCharWidth
     *       fallback —— 渲染功能完全可用，只是前几秒宽度按 ASCII=0.5×fontSize / CJK=fontSize 近似</li>
     *   <li>worker 内部用 {@code ConcurrentHashMap.put} 原子覆盖 MISSING sentinel，
     *       不需要 caller 同步；与运行时 {@code advance} 查询天然无锁</li>
     *   <li>异常吞掉到 log（避免 worker 静默死掉但 main 仍跑）</li>
     * </ul>
     */
    public void loadExternal(Path fontsDir) {
        if (!Files.isDirectory(fontsDir)) {
            log.info("FontRegistry: external fonts dir not present: " + fontsDir);
            return;
        }
        // 收集需要后台算 metrics 的字体（id, Font 对象），避免在 Files.list 流里逃逸捕获 try-with-resources
        List<Map.Entry<String, Font>> pendingMetrics = new ArrayList<>();
        try (var stream = Files.list(fontsDir)) {
            stream.forEach(path -> {
                String fileName = path.getFileName().toString();
                String lower = fileName.toLowerCase();
                if (!lower.endsWith(".ttf") && !lower.endsWith(".otf")) return;
                String id = fileName.substring(0, fileName.lastIndexOf('.'));
                // 派生的 fontId 必须与 /api/font/file、/api/font/metrics 端点的
                // 正则 [a-zA-Z0-9_-]+ 同集。文件名含点（如 "My.Font.ttf" → id="My.Font"）/ 空格 /
                // 非 ASCII / 隐藏文件（".DS_Store" 已被扩展名过滤，但 ".x.ttf" → id="" 空串）会
                // 让 id 与端点正则不一致——listAll 暴露的 id 前端拉不到字体。拒载 + WARNING，不污染下游。
                if (id.isEmpty() || !id.matches("[a-zA-Z0-9_-]+")) {
                    log.warning("FontRegistry: skipping external font with invalid id '" + id
                            + "' (from " + fileName + "); font id must match [a-zA-Z0-9_-]+ "
                            + "(no dots / spaces / non-ASCII). Rename the file to load it.");
                    return;
                }
                try (InputStream in = Files.newInputStream(path)) {
                    Font font = Font.createFont(Font.TRUETYPE_FONT, in);
                    // 外部字体默认 pixelated=false；接入 config.yml 后可按文件名前缀或配置项区分
                    fonts.put(id, new Registered(id, font,
                            new Metadata(id, false, 0),
                            path.toAbsolutePath().toString()));
                    log.info("FontRegistry: loaded external '" + id + "' (" + path + ")");
                    // 用户字体没有构建期生成的 .metrics.json，运行时扫一次 advance 表
                    // 覆盖 MISSING sentinel；否则 charAdvance 返 -1 fallback canonicalCharWidth 与
                    // GlyphMetricsLut HTTP 端点的查询也会 404。
                    // 异步化（见 javadoc）；这里只收集，后面统一 spawn worker
                    pendingMetrics.add(Map.entry(id, font));
                } catch (IOException | FontFormatException ex) {
                    log.log(Level.WARNING, "FontRegistry: failed to load external " + path, ex);
                }
            });
        } catch (IOException ex) {
            log.log(Level.WARNING, "FontRegistry: scan failed for " + fontsDir, ex);
        }
        if (!pendingMetrics.isEmpty()) {
            spawnMetricsWorker(pendingMetrics);
        }
    }

    /**
     * 启动单个 daemon 线程后台逐字体跑 {@link FontMetricsTable#registerRuntime}。
     * daemon 防 plugin disable 后 JVM 仍挂住；逐字体而非并行—避免同时算 65k×N 把 GC 打爆。
     *
     * <p>worker 引用存入 {@link #metricsWorker} 字段，供 {@link #shutdownMetricsWorker()}
     * interrupt + join；循环内每字体前查 {@link Thread#isInterrupted()} 提前退出。</p>
     */
    private void spawnMetricsWorker(List<Map.Entry<String, Font>> pending) {
        Thread t = new Thread(() -> {
            long t0 = System.nanoTime();
            int done = 0;
            for (Map.Entry<String, Font> e : pending) {
                // disable 期 interrupt 后尽快收手，不再扫剩余字体的 65k codepoints
                if (Thread.currentThread().isInterrupted()) {
                    log.info("FontRegistry: metrics worker interrupted, stopping early ("
                            + done + "/" + pending.size() + " fonts done)");
                    return;
                }
                String id = e.getKey();
                Font font = e.getValue();
                try {
                    FontMetricsTable.registerRuntime(id, font);
                    done++;
                    log.info("FontRegistry: registered runtime metrics for external font '" + id + "'");
                } catch (Throwable th) {
                    // 不让一个字体 metrics 失败拖垮 worker
                    log.log(Level.WARNING,
                            "FontRegistry: registerRuntime failed for '" + id + "'", th);
                }
            }
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            log.info("FontRegistry: async metrics worker done (" + done + "/"
                    + pending.size() + " fonts, " + ms + " ms)");
        }, "HikariCanvas-FontMetrics-Worker");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        this.metricsWorker = t;
        t.start();
    }

    /**
     * 停止后台 metrics worker（{@code HikariCanvas#onDisable} 调用）。
     * interrupt 让 worker 在下一字体前退出，再 join 短超时回收，最后清字段断引用让旧
     * classloader 可被 GC。worker 已结束 / 从未 spawn 时为 no-op。
     */
    public void shutdownMetricsWorker() {
        Thread t = this.metricsWorker;
        if (t == null) return;
        t.interrupt();
        try {
            t.join(2_000L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        this.metricsWorker = null;
    }

    /** 返回指定 id 的字体；不存在返 null。 */
    public Registered get(String fontId) {
        return fontId == null ? null : fonts.get(fontId);
    }

    /**
     * 返回指定 id 的字体；不存在则返 {@link #DEFAULT_FONT_ID}。
     * 如果连默认也没（例如 downloadFonts 没跑成功），返 null——调用方应检查并走系统字体 fallback。
     */
    public Registered getOrDefault(String fontId) {
        Registered r = get(fontId);
        if (r != null) return r;
        return fonts.get(DEFAULT_FONT_ID);
    }

    public int size() {
        return fonts.size();
    }

    public Map<String, Registered> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(fonts));
    }

    // ---------- HTTP 端点支持 ----------

    /**
     * 读取字体文件二进制。内置字体从 jar classpath，用户字体从绝对路径。
     * 不存在 / 读取失败返 null。fontId 必须已注册（防 path traversal：不直接拼接外部输入）。
     */
    public byte[] loadFontBytes(String fontId) {
        if (fontId == null) return null;
        Registered r = fonts.get(fontId);
        if (r == null) return null;
        String src = r.source();
        try {
            if (src.startsWith("classpath:")) {
                String path = src.substring("classpath:".length());
                try (InputStream in = FontRegistry.class.getResourceAsStream(path)) {
                    if (in == null) return null;
                    return in.readAllBytes();
                }
            } else {
                // 用户字体走文件系统；源路径来自启动期 loadExternal 扫描，非用户实时输入
                return Files.readAllBytes(Paths.get(src));
            }
        } catch (IOException ex) {
            log.warning("FontRegistry.loadFontBytes failed for " + fontId + ": " + ex.getMessage());
            return null;
        }
    }

    /**
     * 列举所有已注册字体的元数据。返回顺序：内置在前 + 用户在后；同组按 id 字母序。
     * 给 {@code /api/font/list} 端点用。
     */
    public List<FontInfo> listAll() {
        List<FontInfo> result = new ArrayList<>(fonts.size());
        for (Map.Entry<String, Registered> entry : fonts.entrySet()) {
            Registered r = entry.getValue();
            String src = r.source();
            boolean builtin = src.startsWith("classpath:");
            String path = builtin ? src.substring("classpath:".length()) : src;
            int dot = path.lastIndexOf('.');
            String ext = dot >= 0 ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
            // 仅 ttf / otf 暴露；其他扩展名归一到 ttf（AWT TRUETYPE_FONT 兼容超集）
            if (!"otf".equals(ext)) ext = "ttf";
            result.add(new FontInfo(
                    entry.getKey(),
                    r.metadata().displayName(),
                    builtin ? "builtin" : "user",
                    ext,
                    r.metadata().pixelated(),
                    r.metadata().nativeSize()));
        }
        result.sort((a, b) -> {
            if (!a.source().equals(b.source())) {
                return "builtin".equals(a.source()) ? -1 : 1;
            }
            return a.id().compareTo(b.id());
        });
        return result;
    }

    // ---------- 数据类型 ----------

    public record Metadata(String displayName, boolean pixelated, int nativeSize) {}

    /**
     * 公开给 HTTP 端点 {@code /api/font/list} 的字体元信息。
     * <ul>
     *   <li>{@code source}: {@code "builtin"}（jar 内置）/ {@code "user"}（plugins/.../fonts/）</li>
     *   <li>{@code format}: {@code "ttf"} / {@code "otf"}，仅基于文件扩展名</li>
     * </ul>
     */
    public record FontInfo(String id, String displayName, String source, String format,
                           boolean pixelated, int nativeSize) {}

    /** 已注册的字体：fontId + AWT Font + 元数据 + 来源（调试用）。 */
    public record Registered(String fontId, Font font, Metadata metadata, String source) {

        /**
         * 按像素字号派生一个具体字号 Font 实例。像素字体用户字号非 native 整数倍时，
         * 在 CanvasCompositor 里走最近邻缩放；这里只负责 {@link Font#deriveFont(float)}。
         */
        public Font derive(float pixelSize) {
            return font.deriveFont(pixelSize);
        }
    }

    private record BuiltIn(String resourcePath, Metadata metadata) {}
}
