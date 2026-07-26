package ac.haru.hikaricanvas.template;

import ac.haru.hikaricanvas.render.CanvasCompositor;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.storage.TemplateRepo;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 创意工坊协调器：把 {@link TemplateExporter} 产物 + 缩略图渲染 + DB 元数据 + Registry
 * 热重载组合成一次完整的"发布 / 删除 / 精选"操作。
 *
 * <p><b>线程模型：</b> publish / delete 调用方应在 WS 主循环或命令处理线程上调用（不在 Bukkit
 * 主线程或 async 任务并发调用同一 wall）。文件 IO + DB 都同步。</p>
 */
public final class TemplatePublisher {

    public sealed interface Result {
        record Ok(String templateId) implements Result {}
        record Failed(String code, String message) implements Result {}
    }

    private final Logger log;
    private final Path dataFolder;
    private final TemplateExporter exporter;
    private final TemplateRegistry registry;
    private final TemplateRepo repo;
    private final CanvasCompositor compositor;
    private final int maxPerPlayer;
    /**
     * 缩略图服务。发布 / 删除后要清掉对应模板的内存缓存，否则 Gallery 拿到的还是旧图。
     * 它在装配顺序上晚于 publisher 构造，故走 setter；可为 null（测试装配不接）。
     */
    private volatile ac.haru.hikaricanvas.template.preview.TemplatePreviewService previewService;
    /**
     * 图片字节来源：内容 hash → PNG 字节（生产接 {@code ImageStorage::readPngBytes}）。
     * 可为 null——那样存出来的模板不带图片，套用时原图被 LRU 驱逐就变空白。
     */
    private volatile java.util.function.Function<String, byte[]> assetSource;
    /**
     * 脚本字节来源：wallId → {@code scripts.json} 字节（{@code ScriptRule[]} 数组）。
     * 可为 null / 返 null——那样存出来的模板不带脚本。
     */
    private volatile java.util.function.Function<String, byte[]> scriptSource;

    public TemplatePublisher(Logger log, Path dataFolder,
                             TemplateRegistry registry,
                             TemplateRepo repo, CanvasCompositor compositor,
                             int maxPerPlayer) {
        this.log = log;
        this.dataFolder = dataFolder;
        this.exporter = new TemplateExporter();
        this.registry = registry;
        this.repo = repo;
        this.compositor = compositor;
        this.maxPerPlayer = maxPerPlayer;
    }

    /** 装配缩略图服务（HikariCanvas 在 TemplatePreviewService 建好后调）。 */
    public void setPreviewService(
            ac.haru.hikaricanvas.template.preview.TemplatePreviewService previewService) {
        this.previewService = previewService;
    }

    /** 装配图片字节来源（生产传 {@code imageStorage::readPngBytes}）；不装 = 存模板不带图片。 */
    public void setAssetSource(java.util.function.Function<String, byte[]> assetSource) {
        this.assetSource = assetSource;
    }

    /** 装配脚本字节来源（wallId → scripts.json 字节）；不装 = 存模板不带脚本。 */
    public void setScriptSource(java.util.function.Function<String, byte[]> scriptSource) {
        this.scriptSource = scriptSource;
    }

    /** 不带 wallId 的重载：脚本无从取，只打包工程 + 图片。 */
    public Result publish(UUID ownerUuid, String ownerName,
                          String slug, String displayName, String description,
                          TemplateExporter.ParamConfig paramConfig,
                          ProjectState state, boolean bypassQuota) {
        return publish(ownerUuid, ownerName, slug, displayName, description,
                paramConfig, state, bypassQuota, null);
    }

    /**
     * 发布当前 wall 为新模板（或更新同 slug 的现有模板）。
     *
     * @param bypassQuota 持 {@code canvas.template.bypass-limit} 权限时跳过 max-per-player
     * @param wallId      来源墙 id，用来取该墙的积木脚本一并打包；null = 不带脚本
     */
    public Result publish(UUID ownerUuid, String ownerName,
                          String slug, String displayName, String description,
                          TemplateExporter.ParamConfig paramConfig,
                          ProjectState state, boolean bypassQuota, String wallId) {
        // 1) 配额检查（基于已有数量，含同 slug 即将 upsert 的情况——给宽限）
        if (!bypassQuota && maxPerPlayer > 0) {
            int existing = repo.countByOwner(ownerUuid);
            String existingId = "user-" + ownerUuid.toString().replace("-", "").substring(0, 8) + "-" + slug;
            boolean isUpdate = repo.findById(existingId).isPresent();
            if (!isUpdate && existing >= maxPerPlayer) {
                return new Result.Failed("QUOTA_EXCEEDED",
                        "max " + maxPerPlayer + " templates per player; delete some first");
            }
        }

        // 2) 收集要一起打包的图片与脚本（缺任一都只是"少带"，不阻断发布）
        Map<String, byte[]> assets = collectAssets(state);
        byte[] scriptsJson = collectScripts(wallId);

        // 3) Exporter：ProjectState → pack 字节（含 assets/ 与 scripts.json）
        TemplateExporter.Result exportResult = exporter.export(
                ownerUuid, ownerName, slug, displayName, description, paramConfig, state,
                assets, scriptsJson);
        if (exportResult instanceof TemplateExporter.Result.Failed f) {
            return new Result.Failed(f.code(), f.message());
        }
        TemplateExporter.ExportResult ok = ((TemplateExporter.Result.Ok) exportResult).result();

        // 4) 写 .canvas pack 文件
        Path packAbs = dataFolder.resolve(ok.packRelativePath());
        try {
            Files.createDirectories(packAbs.getParent());
            Files.write(packAbs, ok.packBytes(), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            return new Result.Failed("WRITE_FAILED", "pack: " + e.getMessage());
        }

        // 5) 缩略图：直接 rasterize 当前 ProjectState（参数化前的快照）
        Path previewAbs = packAbs.resolveSibling(slug + ".preview.png");
        try {
            BufferedImage rgb = compositor.rasterize(state);
            try (OutputStream out = Files.newOutputStream(previewAbs,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ImageIO.write(rgb, "png", out);
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "[publisher] preview png write failed for " + ok.templateId(), e);
            // 缩略图失败不阻断发布；前端可走 404 → 占位
        }

        // 6) DB upsert
        long now = System.currentTimeMillis();
        boolean isExisting = repo.findById(ok.templateId()).isPresent();
        long created = isExisting ? repo.findById(ok.templateId()).orElseThrow().createdAt() : now;
        TemplateRepo.Row row = new TemplateRepo.Row(
                ok.templateId(), ownerUuid, ownerName,
                displayName, description, ok.packRelativePath(),
                false, false, 0L, created, now);
        if (!repo.upsert(row)) {
            return new Result.Failed("DB_FAILED", "templates upsert failed");
        }

        // 7) Registry 热重载（让前端 listTemplates 立即看到新条目）
        registry.reload();
        // 重发布同 slug 时 templateId 不变 → 缩略图缓存命中的还是旧图，这里点名清掉
        if (previewService != null) previewService.invalidate(ok.templateId());

        return new Result.Ok(ok.templateId());
    }

    /**
     * Hard delete：YAML + preview PNG + DB 行 + registry 重载。callerUuid + isAdmin 控制鉴权。
     */
    public Result delete(String templateId, UUID callerUuid, boolean isAdmin) {
        var rowOpt = repo.findById(templateId);
        if (rowOpt.isEmpty()) {
            return new Result.Failed("NOT_FOUND", "template '" + templateId + "' not found");
        }
        TemplateRepo.Row row = rowOpt.get();
        if (row.builtin()) {
            return new Result.Failed("FORBIDDEN", "cannot delete builtin template");
        }
        if (!isAdmin && (row.ownerUuid() == null || !row.ownerUuid().equals(callerUuid))) {
            return new Result.Failed("FORBIDDEN", "only owner or admin can delete");
        }

        // 删模板文件 + PNG
        try {
            Path fileAbs = dataFolder.resolve(row.filePath());
            Files.deleteIfExists(fileAbs);
            Path previewAbs = fileAbs.resolveSibling(slugFromFilePath(row.filePath()) + ".preview.png");
            Files.deleteIfExists(previewAbs);
            // 若 uuid 目录空了也清掉（best-effort，失败忽略）
            Path uuidDir = fileAbs.getParent();
            if (uuidDir != null && Files.isDirectory(uuidDir)) {
                try (var stream = Files.list(uuidDir)) {
                    if (stream.findAny().isEmpty()) Files.deleteIfExists(uuidDir);
                }
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "[publisher] file cleanup failed for " + templateId, e);
            // 继续删 DB；不阻断
        }

        repo.delete(templateId);
        registry.reload();
        if (previewService != null) previewService.invalidate(templateId);
        return new Result.Ok(templateId);
    }

    /**
     * 启动期把 registry 中的 builtin / server 模板入库（仅插入新行）。已存在 row 不动，保留
     * featured 状态由 admin 控制（v1 builtin 默认 featured=true）。
     */
    public void syncBuiltinToDb() {
        long now = System.currentTimeMillis();
        int inserted = 0;
        for (var entry : registry.templates().values()) {
            if (entry.source() == TemplateSource.USER) continue;
            String id = entry.spec().id();
            if (repo.findById(id).isPresent()) continue;
            String filePath = "builtin:" + id;  // 占位；builtin 不可删，路径仅记录
            TemplateRepo.Row row = new TemplateRepo.Row(
                    id, null, null,
                    entry.spec().name() == null ? id : entry.spec().name(),
                    entry.spec().description(), filePath,
                    true, true, 0L, now, now);
            if (repo.upsert(row)) inserted++;
        }
        if (inserted > 0) {
            log.info("[publisher] synced " + inserted + " builtin/server template(s) to DB");
        }
    }

    /** admin 精选 toggle。 */
    public Result setFeatured(String templateId, boolean featured) {
        var rowOpt = repo.findById(templateId);
        if (rowOpt.isEmpty()) {
            return new Result.Failed("NOT_FOUND", "template '" + templateId + "' not found");
        }
        if (rowOpt.get().builtin()) {
            return new Result.Failed("FORBIDDEN", "builtin templates are always featured");
        }
        if (!repo.setFeatured(templateId, featured)) {
            return new Result.Failed("DB_FAILED", "update featured failed");
        }
        return new Result.Ok(templateId);
    }

    /**
     * 扫工程里所有 {@link ac.haru.hikaricanvas.state.ImageElement} 引用的图片，逐个取 PNG 字节。
     *
     * <p>取不到的（文件已被 LRU 驱逐 / 读失败）跳过并记一条 warn——存模板本身照常成功，
     * 只是这张图没带上。{@code assetSource} 没装配时返回空 map。</p>
     */
    private Map<String, byte[]> collectAssets(ProjectState state) {
        java.util.function.Function<String, byte[]> src = this.assetSource;
        if (src == null || state == null || state.layers() == null) return Map.of();
        Map<String, byte[]> out = new java.util.LinkedHashMap<>();
        for (var layer : state.layers()) {
            for (var el : layer.elements()) {
                if (!(el instanceof ac.haru.hikaricanvas.state.ImageElement im)) continue;
                String hash = im.source();
                if (hash == null || out.containsKey(hash)) continue;
                byte[] png;
                try {
                    png = src.apply(hash);
                } catch (RuntimeException e) {
                    log.log(Level.WARNING, "[publisher] reading image " + hash + " failed", e);
                    continue;
                }
                if (png == null || png.length == 0) {
                    log.warning("[publisher] image " + hash
                            + " is no longer on disk; template will be saved without it");
                    continue;
                }
                out.put(hash, png);
            }
        }
        return out;
    }

    /** 取该墙的积木脚本序列化字节；来源没装配、没 wallId 或该墙无脚本时返 null。 */
    private byte[] collectScripts(String wallId) {
        java.util.function.Function<String, byte[]> src = this.scriptSource;
        if (src == null || wallId == null) return null;
        try {
            byte[] json = src.apply(wallId);
            return (json == null || json.length == 0) ? null : json;
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "[publisher] reading scripts for wall " + wallId + " failed", e);
            return null;
        }
    }

    private static String slugFromFilePath(String filePath) {
        int slash = filePath.lastIndexOf('/');
        String filename = slash < 0 ? filePath : filePath.substring(slash + 1);
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
