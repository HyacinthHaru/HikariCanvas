package ac.haru.hikaricanvas.template;

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
     *  避免遍历目录失败。文件每行一个 yml 名（不含路径），允许 # 注释。 */
    private static final String INDEX_RESOURCE = "templates/_index.txt";

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
                    if (acceptOne(in, TemplateSource.BUILTIN, label, out, failures)) {
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
                else log.warning("templates/_index.txt: ignoring non-yml entry '" + trimmed + "'");
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
                try (InputStream in = jar.getInputStream(je)) {
                    if (acceptOne(in, TemplateSource.BUILTIN, label, out, failures)) {
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
                if (acceptOne(in, TemplateSource.BUILTIN, label, out, failures)) {
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
                String label = "server:" + p.getFileName();
                try (InputStream in = Files.newInputStream(p)) {
                    TemplateLoader.Result result = loader.load(in);
                    if (result instanceof TemplateLoader.Result.Ok ok) {
                        String id = ok.spec().id();
                        TemplateEntry prev = out.get(id);
                        if (prev != null && prev.source() == TemplateSource.SERVER) {
                            failures.add(label + ": duplicate server id '" + id
                                    + "' (already loaded from " + prev.sourceLabel() + ")");
                            log.warning(label + ": duplicate id '" + id + "', skipped");
                            continue;
                        }
                        boolean isOverride = prev != null
                                && prev.source() == TemplateSource.BUILTIN;
                        out.put(id, new TemplateEntry(ok.spec(),
                                TemplateSource.SERVER, label));
                        counter[0]++;
                        if (isOverride) {
                            overrides[0]++;
                            log.info("Template '" + id + "' overridden by " + label);
                        }
                    } else if (result instanceof TemplateLoader.Result.Failed f) {
                        failures.add(label + ": " + f.reason() + " — " + f.detail());
                        log.warning("Template '" + label + "' rejected: "
                                + f.reason() + " — " + f.detail());
                    }
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
                String label = "user:" + uuidDir.getFileName() + "/" + p.getFileName();
                try (InputStream in = Files.newInputStream(p)) {
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

    private static boolean isTemplateEntry(String name) {
        return name.startsWith(CLASSPATH_TEMPLATES_DIR + "/")
                && isTemplateFileName(name.substring(name.lastIndexOf('/') + 1));
    }

    private static boolean isTemplateFileName(String name) {
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }
}
