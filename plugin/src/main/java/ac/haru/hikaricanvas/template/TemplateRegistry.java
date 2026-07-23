package ac.haru.hikaricanvas.template;

import ac.haru.hikaricanvas.canvasfile.CanvasArchive;
import ac.haru.hikaricanvas.canvasfile.CanvasImportException;
import ac.haru.hikaricanvas.canvasfile.CanvasManifest;
import ac.haru.hikaricanvas.canvasfile.PackParamResolver;
import ac.haru.hikaricanvas.canvasfile.ProjectImporter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * 全局模板注册表。契约见 {@code docs/template-spec.md §1}。
 *
 * <p><b>加载顺序：</b> jar 内 {@code /templates/*.yml} → {@code dataFolder/templates/*.yml}。
 * 同 {@code id} 后加载覆盖前者，允许服主覆盖内置模板。</p>
 *
 * <p><b>两种模板载体：</b> 三个来源（builtin / server / user）都同时收 {@code *.yml}（传统模板，走
 * {@link TemplateLoader} + {@code TemplateInstantiator}）与 {@code *.canvas} pack
 * （{@code manifest.kind="pack"}，走 {@code ProjectImporter.applyPack}）。pack 的 id = 文件名去
 * {@code .canvas} 后缀；注册时只解包出 manifest + params.json 合成一份 UI 用 {@link TemplateSpec}，
 * pack 原始字节整包存进 {@link TemplateEntry#packBytes()} 供 apply 时现解。{@code kind="project"}
 * 的普通工程 {@code .canvas} 不是模板，扫到即跳过（记 fine，不计失败）。</p>
 *
 * <p><b>原子热替换：</b> {@link #entries} 为 {@code volatile} 引用，{@link #reload}
 * 把全量加载结果在末尾一次性 swap，期间读到的注册表始终是某次成功加载的完整快照，
 * 无半态可见。</p>
 *
 * <p><b>线程模型：</b></p>
 * <ul>
 *   <li>{@code reload}：仅在主线程（命令处理）或异步任务中串行调用，由调用方保证。</li>
 *   <li>{@code byId / templates}：任意线程，读 {@code volatile} 引用一次后用副本。</li>
 * </ul>
 */
public final class TemplateRegistry {

    private static final String CLASSPATH_TEMPLATES_DIR = "templates";
    /** jar / classpath 内的模板清单文件——dev 模式（classes 与 resources 分离）下用它，
     *  避免遍历目录失败。文件每行一个 yml / .canvas 名（不含路径），允许 # 注释。 */
    private static final String INDEX_RESOURCE = "templates/_index.txt";

    // 加载可信 .canvas pack 的解包上限（服主 / 玩家已落盘的本地文件，非公网上传，故给宽松上限——
    // 仅防病态 zip 炸弹在 reload 期 OOM；正常 pack 远小于此）。与端点导入的 ImportConfig 上限相互独立。
    private static final long PACK_LOAD_MAX_ZIP_BYTES = 64L * 1024 * 1024;    // 64 MB
    private static final long PACK_LOAD_MAX_ENTRY_BYTES = 64L * 1024 * 1024;  // 64 MB
    private static final long PACK_LOAD_MAX_TOTAL_BYTES = 256L * 1024 * 1024; // 256 MB

    private final Logger log;
    private final TemplateLoader loader;
    /** 用于定位 jar 的"锚点类"——通常传 {@code HikariCanvas.class}。 */
    private final Class<?> anchorClass;
    private final Path serverTemplatesDir;
    /** 玩家发布的模板根目录 {@code dataFolder/user-templates/<uuid>/*.yml}。 */
    private final Path userTemplatesDir;

    /** {@code id → entry}。每次 reload 整体替换。读时取一次引用，避免拷贝。 */
    private volatile Map<String, TemplateEntry> entries = Collections.emptyMap();

    public TemplateRegistry(Logger log, Class<?> anchorClass, Path serverTemplatesDir) {
        this(log, anchorClass, serverTemplatesDir, null);
    }

    public TemplateRegistry(Logger log, Class<?> anchorClass, Path serverTemplatesDir,
                            Path userTemplatesDir) {
        this.log = log;
        this.loader = new TemplateLoader();
        this.anchorClass = anchorClass;
        this.serverTemplatesDir = serverTemplatesDir;
        this.userTemplatesDir = userTemplatesDir;
    }

    /** 加载汇总。每次 reload 返回，用于命令输出 + log。 */
    public record ReloadStats(int builtinLoaded, int serverLoaded, int userLoaded, int overrides,
                              int failed, List<String> failures) {
    }

    /** 当前可用模板的不可变视图。读 volatile 一次，安全暴露给调用方。 */
    public Map<String, TemplateEntry> templates() {
        return entries;
    }

    public TemplateEntry byId(String id) {
        return entries.get(id);
    }

    /**
     * apply 用查询，强制跨用户隔离。
     *
     * <ul>
     *   <li>id 不存在 → 返回 {@code null}（保持与 {@link #byId(String)} 一致的"未找到"语义）</li>
     *   <li>条目 {@code ownerUuid} 为 empty（builtin / server）→ 任何调用方可用</li>
     *   <li>条目 {@code ownerUuid} = {@code callerUuid} → 自己的模板可用</li>
     *   <li>条目 {@code ownerUuid} 非空且 ≠ {@code callerUuid} 且 {@code !hasBypass} →
     *       抛 {@link ForbiddenTemplateException}（caller UUID null 等同非 owner）</li>
     * </ul>
     *
     * <p>{@code hasBypass} 由调用方查 {@code canvas.template.use-others} 权限传入。</p>
     */
    public TemplateEntry byIdForApply(String id, UUID callerUuid, boolean hasBypass) {
        TemplateEntry entry = entries.get(id);
        if (entry == null) return null;
        Optional<UUID> owner = entry.ownerUuid();
        if (owner.isEmpty()) return entry;
        if (hasBypass) return entry;
        if (callerUuid != null && callerUuid.equals(owner.get())) return entry;
        throw new ForbiddenTemplateException(id);
    }

    /**
     * ready / list 端点用的可见性过滤，与 {@link #byIdForApply} 共用同一隔离判定。
     *
     * <p>ready 帧只下发调用方可见的 {@link TemplateSpec}——user-template 的完整
     * {@code raw_state} 画布内容不能泄漏给非 owner，否则 {@code byIdForApply} 的 apply
     * 端隔离形同虚设。</p>
     *
     * <p>规则（与 {@link #byIdForApply} 对齐）：</p>
     * <ul>
     *   <li>builtin / server 模板（{@code ownerUuid} empty）→ 始终可见</li>
     *   <li>{@code hasBypass}（{@code canvas.template.use-others}）→ 全部可见</li>
     *   <li>USER 模板且 {@code ownerUuid} == {@code callerUuid} → 可见</li>
     *   <li>其余 USER 模板 → 整条剔除（不下发 spec / rawState）</li>
     * </ul>
     *
     * @param callerUuid 请求方玩家 UUID；{@code null} 等同非 owner
     * @param hasBypass  调用方是否持 {@code canvas.template.use-others}
     * @return 调用方可见的 {@link TemplateEntry} 列表（读 volatile 快照一次，安全暴露）
     */
    public List<TemplateEntry> listVisibleTo(UUID callerUuid, boolean hasBypass) {
        List<TemplateEntry> visible = new java.util.ArrayList<>();
        for (TemplateEntry entry : entries.values()) {
            Optional<UUID> owner = entry.ownerUuid();
            if (owner.isEmpty() || hasBypass
                    || (callerUuid != null && callerUuid.equals(owner.get()))) {
                visible.add(entry);
            }
        }
        return visible;
    }

    public int size() {
        return entries.size();
    }

    /**
     * 全量重扫并原子替换 registry。调用方应吞掉异常——单文件失败已在内部
     * 记 warn 不会上抛；这里抛说明扫描自身（jar 不可读 / 目录权限）出错。
     */
    public synchronized ReloadStats reload() {
        Map<String, TemplateEntry> next = new LinkedHashMap<>();
        java.util.List<String> failures = new java.util.ArrayList<>();
        int[] builtinLoaded = {0};
        int[] serverLoaded = {0};
        int[] userLoaded = {0};
        int[] overrides = {0};

        // 1) 内置：jar 内 /templates/*.yml
        loadBuiltin(next, builtinLoaded, failures);

        // 2) 服务器：dataFolder/templates/*.yml（同 id 覆盖 builtin）
        loadServer(next, serverLoaded, overrides, failures);

        // 3) 玩家发布：dataFolder/user-templates/<uuid>/*.yml（同 id 跳过；不覆盖 builtin/server）
        loadUser(next, userLoaded, failures);

        // 4) atomic swap
        this.entries = Collections.unmodifiableMap(next);

        ReloadStats stats = new ReloadStats(builtinLoaded[0], serverLoaded[0], userLoaded[0],
                overrides[0], failures.size(), failures);
        log.info("Templates reloaded: builtin=" + stats.builtinLoaded()
                + " server=" + stats.serverLoaded()
                + " user=" + stats.userLoaded()
                + " overrides=" + stats.overrides()
                + " failed=" + stats.failed());
        return stats;
    }

    // ---------------- builtin (jar / classes dir) ----------------

    private void loadBuiltin(Map<String, TemplateEntry> out, int[] counter,
                             java.util.List<String> failures) {
        // 主路径：读 classpath 内的 _index.txt 清单，按名字依次 getResourceAsStream。
        // 这条路径同时覆盖：
        //   - 打包后 jar（all .yml 与 _index.txt 都在 jar 内）
        //   - dev / test（gradle 把 _index.txt + .yml 一起摊到 build/resources/main/）
        // 避免之前"扫 codesource 目录"在 classes 与 resources 分离时漏文件。
        List<String> manifest = readBuiltinManifest();
        if (!manifest.isEmpty()) {
            for (String name : manifest) {
                String label = "builtin:" + name;
                String resourcePath = CLASSPATH_TEMPLATES_DIR + "/" + name;
                try (InputStream in = anchorClass.getClassLoader().getResourceAsStream(resourcePath)) {
                    if (in == null) {
                        failures.add(label + ": resource not on classpath");
                        log.warning(label + ": missing from classpath");
                        continue;
                    }
                    if (isPackFileName(name)) {
                        if (acceptPackBytes(in.readAllBytes(), TemplateSource.BUILTIN, label,
                                packIdStem(name), Optional.empty(), out, failures)) {
                            counter[0]++;
                        }
                    } else if (acceptOne(in, TemplateSource.BUILTIN, label, out, failures)) {
                        counter[0]++;
                    }
                } catch (IOException e) {
                    failures.add(label + ": " + e.getMessage());
                    log.log(Level.WARNING, "Templates: read failed " + label, e);
                }
            }
            return;
        }

        // Fallback：没有 manifest（旧 jar / 外部插件包），按 codesource 直接扫
        URL codeSourceUrl = anchorClass.getProtectionDomain().getCodeSource().getLocation();
        if (codeSourceUrl == null) {
            log.warning("Templates: cannot locate plugin codesource; builtin templates skipped");
            return;
        }
        File codeSource;
        try {
            codeSource = new File(codeSourceUrl.toURI());
        } catch (URISyntaxException e) {
            log.log(Level.WARNING, "Templates: bad codesource URL " + codeSourceUrl, e);
            return;
        }
        if (codeSource.isFile()) {
            loadBuiltinFromJar(codeSource, out, counter, failures);
        } else if (codeSource.isDirectory()) {
            loadBuiltinFromClassesDir(codeSource, out, counter, failures);
        }
    }

    private List<String> readBuiltinManifest() {
        try (InputStream in = anchorClass.getClassLoader().getResourceAsStream(INDEX_RESOURCE)) {
            if (in == null) return List.of();
            byte[] bytes = in.readAllBytes();
            String content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            List<String> out = new java.util.ArrayList<>();
            for (String line : content.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                if (isTemplateFileName(trimmed)) out.add(trimmed);
                else log.warning("templates/_index.txt: ignoring non-template entry '" + trimmed + "'");
            }
            return out;
        } catch (IOException e) {
            log.log(Level.WARNING, "Templates: failed to read " + INDEX_RESOURCE, e);
            return List.of();
        }
    }

    private void loadBuiltinFromJar(File jarFile, Map<String, TemplateEntry> out,
                                    int[] counter, java.util.List<String> failures) {
        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> es = jar.entries();
            while (es.hasMoreElements()) {
                JarEntry je = es.nextElement();
                String name = je.getName();
                if (!isTemplateEntry(name)) continue;
                String label = "jar:" + name;
                String simpleName = name.substring(name.lastIndexOf('/') + 1);
                try (InputStream in = jar.getInputStream(je)) {
                    if (isPackFileName(simpleName)) {
                        if (acceptPackBytes(in.readAllBytes(), TemplateSource.BUILTIN, label,
                                packIdStem(simpleName), Optional.empty(), out, failures)) {
                            counter[0]++;
                        }
                    } else if (acceptOne(in, TemplateSource.BUILTIN, label, out, failures)) {
                        counter[0]++;
                    }
                }
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "Templates: jar scan failed " + jarFile, e);
        }
    }

    private void loadBuiltinFromClassesDir(File classesDir, Map<String, TemplateEntry> out,
                                            int[] counter, java.util.List<String> failures) {
        // 测试 / 开发期：plugin/build/classes/.../templates/*.yml
        File templatesDir = new File(classesDir, CLASSPATH_TEMPLATES_DIR);
        if (!templatesDir.isDirectory()) return;
        File[] files = templatesDir.listFiles((d, name) -> isTemplateFileName(name));
        if (files == null) return;
        for (File f : files) {
            String label = "classes:" + CLASSPATH_TEMPLATES_DIR + "/" + f.getName();
            try (InputStream in = Files.newInputStream(f.toPath())) {
                if (isPackFileName(f.getName())) {
                    if (acceptPackBytes(in.readAllBytes(), TemplateSource.BUILTIN, label,
                            packIdStem(f.getName()), Optional.empty(), out, failures)) {
                        counter[0]++;
                    }
                } else if (acceptOne(in, TemplateSource.BUILTIN, label, out, failures)) {
                    counter[0]++;
                }
            } catch (IOException e) {
                failures.add(label + ": " + e.getMessage());
                log.log(Level.WARNING, "Templates: read failed " + label, e);
            }
        }
    }

    // ---------------- server (plugins/HikariCanvas/templates/) ----------------

    private void loadServer(Map<String, TemplateEntry> out, int[] counter, int[] overrides,
                            java.util.List<String> failures) {
        if (serverTemplatesDir == null) return;
        try {
            Files.createDirectories(serverTemplatesDir);
        } catch (IOException e) {
            log.log(Level.WARNING, "Templates: cannot create " + serverTemplatesDir, e);
            return;
        }
        try (Stream<Path> stream = Files.list(serverTemplatesDir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> isTemplateFileName(p.getFileName().toString()))
                    .sorted()
                    .toList();
            for (Path p : files) {
                String fileName = p.getFileName().toString();
                String label = "server:" + fileName;
                try (InputStream in = Files.newInputStream(p)) {
                    // YAML 与 pack 各自解析出（id, entry），再汇合到 registerServerEntry
                    // 走同一套 override / 重复判定（server 覆盖 builtin）。
                    String id;
                    TemplateEntry entry;
                    if (isPackFileName(fileName)) {
                        byte[] bytes = in.readAllBytes();
                        TemplateSpec spec = tryBuildPackSpec(bytes, packIdStem(fileName), label, failures);
                        if (spec == null) continue;   // 非 pack / 解析失败（tryBuildPackSpec 已记 warn）
                        id = spec.id();               // manifest 自声明或退回 stem
                        entry = TemplateEntry.pack(spec, TemplateSource.SERVER, label,
                                Optional.empty(), bytes);
                    } else {
                        TemplateLoader.Result result = loader.load(in);
                        if (result instanceof TemplateLoader.Result.Failed f) {
                            failures.add(label + ": " + f.reason() + " — " + f.detail());
                            log.warning("Template '" + label + "' rejected: "
                                    + f.reason() + " — " + f.detail());
                            continue;
                        }
                        TemplateLoader.Result.Ok ok = (TemplateLoader.Result.Ok) result;
                        id = ok.spec().id();
                        entry = new TemplateEntry(ok.spec(), TemplateSource.SERVER, label);
                    }
                    registerServerEntry(out, id, label, entry, counter, overrides, failures);
                } catch (IOException e) {
                    failures.add(label + ": " + e.getMessage());
                    log.log(Level.WARNING, "Templates: read failed " + label, e);
                }
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "Templates: list failed " + serverTemplatesDir, e);
        }
    }

    // ---------------- user (plugins/HikariCanvas/user-templates/<uuid>/) ----------------

    /**
     * 递归扫 {@code user-templates/<uuid>/*.yml}。同 id 已存在 → 跳过（不覆盖
     * builtin / server）。容错：单 uuid 子目录失败不影响其他。
     */
    private void loadUser(Map<String, TemplateEntry> out, int[] counter,
                          java.util.List<String> failures) {
        if (userTemplatesDir == null) return;
        try {
            Files.createDirectories(userTemplatesDir);
        } catch (IOException e) {
            log.log(Level.WARNING, "Templates: cannot create " + userTemplatesDir, e);
            return;
        }
        try (Stream<Path> uuidDirs = Files.list(userTemplatesDir)) {
            List<Path> dirs = uuidDirs.filter(Files::isDirectory).sorted().toList();
            for (Path uuidDir : dirs) {
                // 校验目录名为合法 UUID；非法目录跳过 + warn（防伪造目录名注入
                // 全局可见模板，或绕过 owner 隔离）。
                String dirName = uuidDir.getFileName().toString();
                UUID ownerUuid;
                try {
                    ownerUuid = UUID.fromString(dirName);
                } catch (IllegalArgumentException iae) {
                    log.warning("Templates: skipping user-templates subdir with non-UUID name '"
                            + dirName + "'");
                    continue;
                }
                loadUserUuidDir(uuidDir, ownerUuid, out, counter, failures);
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "Templates: list user-templates failed", e);
        }
    }

    private void loadUserUuidDir(Path uuidDir, UUID ownerUuid, Map<String, TemplateEntry> out,
                                  int[] counter, java.util.List<String> failures) {
        try (Stream<Path> stream = Files.list(uuidDir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> isTemplateFileName(p.getFileName().toString()))
                    .sorted()
                    .toList();
            for (Path p : files) {
                String fileName = p.getFileName().toString();
                String label = "user:" + uuidDir.getFileName() + "/" + fileName;
                try (InputStream in = Files.newInputStream(p)) {
                    // .canvas pack：走 pack 加载路径（简单去重 + 带 ownerUuid，同 YAML user 规则）。
                    if (isPackFileName(fileName)) {
                        if (acceptPackBytes(in.readAllBytes(), TemplateSource.USER, label,
                                packIdStem(fileName), Optional.of(ownerUuid), out, failures)) {
                            counter[0]++;
                        }
                        continue;
                    }
                    TemplateLoader.Result result = loader.load(in);
                    if (result instanceof TemplateLoader.Result.Ok ok) {
                        String id = ok.spec().id();
                        if (out.containsKey(id)) {
                            failures.add(label + ": duplicate id '" + id
                                    + "' (already loaded from " + out.get(id).sourceLabel() + ")");
                            log.warning(label + ": duplicate id '" + id + "', skipped");
                            continue;
                        }
                        out.put(id, new TemplateEntry(ok.spec(), TemplateSource.USER, label,
                                Optional.of(ownerUuid)));
                        counter[0]++;
                    } else if (result instanceof TemplateLoader.Result.Failed f) {
                        failures.add(label + ": " + f.reason() + " — " + f.detail());
                        log.warning("Template '" + label + "' rejected: " + f.reason() + " — " + f.detail());
                    }
                } catch (IOException e) {
                    failures.add(label + ": " + e.getMessage());
                    log.log(Level.WARNING, "Templates: read failed " + label, e);
                }
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "Templates: list user uuid dir failed " + uuidDir, e);
        }
    }

    // ---------------- common ----------------

    private boolean acceptOne(InputStream in, TemplateSource source, String label,
                              Map<String, TemplateEntry> out,
                              java.util.List<String> failures) {
        TemplateLoader.Result result = loader.load(in);
        if (result instanceof TemplateLoader.Result.Ok ok) {
            String id = ok.spec().id();
            if (out.containsKey(id)) {
                failures.add(label + ": duplicate id '" + id + "' (already loaded from "
                        + out.get(id).sourceLabel() + ")");
                log.warning(label + ": duplicate id '" + id + "', skipped");
                return false;
            }
            out.put(id, new TemplateEntry(ok.spec(), source, label));
            return true;
        }
        if (result instanceof TemplateLoader.Result.Failed f) {
            failures.add(label + ": " + f.reason() + " — " + f.detail());
            log.warning("Template '" + label + "' rejected: " + f.reason() + " — " + f.detail());
        }
        return false;
    }

    // ---------------- .canvas pack ----------------

    /**
     * pack 加载的简单去重路径（builtin / user 共用，同 {@link #acceptOne} 的 containsKey 规则）：
     * 把 pack 字节合成 {@link TemplateSpec} 后放入 {@code out}。非 pack（kind=project）/ 解析失败 /
     * 重复 id → 返 {@code false}（不计数）。
     *
     * @param idStem    id = 文件名去 {@code .canvas} 后缀
     * @param ownerUuid user 源带 owner；builtin 传 {@link Optional#empty()}
     */
    private boolean acceptPackBytes(byte[] packBytes, TemplateSource source, String label,
                                    String idStem, Optional<UUID> ownerUuid,
                                    Map<String, TemplateEntry> out,
                                    java.util.List<String> failures) {
        TemplateSpec spec = tryBuildPackSpec(packBytes, idStem, label, failures);
        if (spec == null) return false;   // 非 pack / 解析失败（tryBuildPackSpec 已记 warn/fine）
        String id = spec.id();            // manifest 自声明或退回 stem（见 buildPackSpec）
        if (out.containsKey(id)) {
            failures.add(label + ": duplicate id '" + id + "' (already loaded from "
                    + out.get(id).sourceLabel() + ")");
            log.warning(label + ": duplicate id '" + id + "', skipped");
            return false;
        }
        out.put(id, TemplateEntry.pack(spec, source, label, ownerUuid, packBytes));
        return true;
    }

    /**
     * server 源统一注册（override / 重复判定）：{@code prev} 为 SERVER → 重复报错跳过；{@code prev}
     * 为 BUILTIN → 覆盖 + 计 override；否则纯新增。YAML 与 pack 两条路径共用，保证覆盖语义一致。
     */
    private void registerServerEntry(Map<String, TemplateEntry> out, String id, String label,
                                     TemplateEntry entry, int[] counter, int[] overrides,
                                     java.util.List<String> failures) {
        TemplateEntry prev = out.get(id);
        if (prev != null && prev.source() == TemplateSource.SERVER) {
            failures.add(label + ": duplicate server id '" + id
                    + "' (already loaded from " + prev.sourceLabel() + ")");
            log.warning(label + ": duplicate id '" + id + "', skipped");
            return;
        }
        boolean isOverride = prev != null && prev.source() == TemplateSource.BUILTIN;
        out.put(id, entry);
        counter[0]++;
        if (isOverride) {
            overrides[0]++;
            log.fine("Template '" + id + "' overridden by " + label);
        }
    }

    /**
     * 解包 pack 字节 + 解析 manifest + 校验 {@code kind="pack"} + 合成 {@link TemplateSpec}。
     *
     * <p>返回 {@code null} 表示「跳过」：{@code kind="project"} 的普通工程不是模板（记 fine，不计失败）；
     * 解包 / manifest / params 解析失败（{@link CanvasImportException}）记 warn + failures。两种情形都
     * <b>不上抛</b>——照每文件容错纪律，一个坏包不毁整次 {@link #reload()}。</p>
     */
    private TemplateSpec tryBuildPackSpec(byte[] packBytes, String idStem, String label,
                                          java.util.List<String> failures) {
        try {
            CanvasArchive.Limits limits = new CanvasArchive.Limits(
                    PACK_LOAD_MAX_ZIP_BYTES, PACK_LOAD_MAX_ENTRY_BYTES, PACK_LOAD_MAX_TOTAL_BYTES);
            Map<String, byte[]> zipEntries = CanvasArchive.unpack(packBytes, limits);
            CanvasManifest manifest = CanvasManifest.parse(
                    zipEntries.get("manifest.json"), ProjectImporter.CANVAS_SPEC_MAX);
            if (!"pack".equals(manifest.kind())) {
                // 普通工程（kind=project）不是模板——跳过（zero-param-project 容忍是后续阶段的事，不在此）。
                log.fine(label + ": kind=" + manifest.kind() + " (not a pack), skipped");
                return null;
            }
            return buildPackSpec(idStem, manifest, zipEntries.get("params.json"));
        } catch (CanvasImportException e) {
            failures.add(label + ": " + e.code() + " — " + e.getMessage());
            log.warning("Template pack '" + label + "' rejected: " + e.code() + " — " + e.getMessage());
            return null;
        }
    }

    /**
     * 用 manifest + {@code params.json} 合成 pack 的 {@link TemplateSpec}——仅供 Gallery / list / preview
     * 统一按 {@code spec()} 读。只填 {@code spec / id / name / params}；
     * {@code description / version / author / tags / preview / canvas / layout / rawState} 均为 null
     * （pack 的画布内容在 {@code project.json}，apply 时由 {@code ProjectImporter.applyPack} 现解，不进 spec）。
     *
     * @param idStem     文件名去 {@code .canvas} 后缀；仅当 manifest 未自声明 {@code id} 时退回用它
     * @param paramsJson pack 内 {@code params.json} 字节；可空（无参数 pack）
     */
    private static TemplateSpec buildPackSpec(String idStem, CanvasManifest manifest, byte[] paramsJson)
            throws CanvasImportException {
        Map<String, TemplateParam> params = new LinkedHashMap<>();
        if (paramsJson != null) {
            for (PackParamResolver.ParamDef def : PackParamResolver.parse(paramsJson)) {
                params.put(def.id(), def.param());
            }
        }
        // id 优先取 manifest 自声明（存为模板产出的 pack 带 user-<uuid8>-<slug>，与 DB template_id 对齐），
        // 缺省退回文件名 stem（作者手写内置 pack 可只靠文件名）。
        String id = (manifest.id() != null && !manifest.id().isBlank()) ? manifest.id() : idStem;
        String name = manifest.name() != null ? manifest.name() : id;
        return new TemplateSpec(manifest.spec(), id, name, /*description*/ null,
                /*version*/ null, /*author*/ null, /*tags*/ null, /*preview*/ null,
                /*canvas*/ null, params, /*layout*/ null, /*rawState*/ null);
    }

    /** pack 的 id = 文件名去最后一个扩展名（{@code subway.canvas → subway}）。 */
    private static String packIdStem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    // ---------------- 文件名判定 ----------------

    private static boolean isTemplateEntry(String name) {
        return name.startsWith(CLASSPATH_TEMPLATES_DIR + "/")
                && isTemplateFileName(name.substring(name.lastIndexOf('/') + 1));
    }

    /**
     * 模板文件名——决定各来源 scan 过滤器是否纳入该文件。YAML（{@code .yml/.yaml}）走
     * {@link TemplateLoader}；{@code .canvas} pack 走 pack 加载路径（{@link #acceptPackBytes} /
     * {@link #tryBuildPackSpec}），二者在各 loader 里按 {@link #isPackFileName} 分流。
     */
    private static boolean isTemplateFileName(String name) {
        return isYamlFileName(name) || isPackFileName(name);
    }

    private static boolean isYamlFileName(String name) {
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    /** {@code .canvas} pack 文件名——走 pack 加载路径，不喂 {@link TemplateLoader}。 */
    private static boolean isPackFileName(String name) {
        return name.endsWith(".canvas");
    }
}
