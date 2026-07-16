package moe.hikari.canvas.template;

import moe.hikari.canvas.render.CanvasCompositor;
import moe.hikari.canvas.state.ProjectState;
import moe.hikari.canvas.storage.TemplateRepo;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
    private final TemplateLoader yamlLoader;
    private final TemplateRegistry registry;
    private final TemplateRepo repo;
    private final CanvasCompositor compositor;
    private final int maxPerPlayer;

    public TemplatePublisher(Logger log, Path dataFolder,
                             TemplateLoader yamlLoader, TemplateRegistry registry,
                             TemplateRepo repo, CanvasCompositor compositor,
                             int maxPerPlayer) {
        this.log = log;
        this.dataFolder = dataFolder;
        this.exporter = new TemplateExporter(yamlLoader);
        this.yamlLoader = yamlLoader;
        this.registry = registry;
        this.repo = repo;
        this.compositor = compositor;
        this.maxPerPlayer = maxPerPlayer;
    }

    /**
     * 发布当前 wall 为新模板（或更新同 slug 的现有模板）。
     *
     * @param bypassQuota 持 {@code canvas.template.bypass-limit} 权限时跳过 max-per-player
     */
    public Result publish(UUID ownerUuid, String ownerName,
                          String slug, String displayName, String description,
                          TemplateExporter.ParamConfig paramConfig,
                          ProjectState state, boolean bypassQuota) {
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

        // 2) Exporter：ProjectState → spec + yaml
        TemplateExporter.Result exportResult = exporter.export(
                ownerUuid, ownerName, slug, displayName, description, paramConfig, state);
        if (exportResult instanceof TemplateExporter.Result.Failed f) {
            return new Result.Failed(f.code(), f.message());
        }
        TemplateExporter.ExportResult ok = ((TemplateExporter.Result.Ok) exportResult).result();

        // 3) 写 YAML 文件
        Path yamlAbs = dataFolder.resolve(ok.yamlRelativePath());
        try {
            Files.createDirectories(yamlAbs.getParent());
            Files.writeString(yamlAbs, ok.yamlString(), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            return new Result.Failed("WRITE_FAILED", "yaml: " + e.getMessage());
        }

        // 4) 缩略图：直接 rasterize 当前 ProjectState（已脱参数化前的快照）
        Path previewAbs = yamlAbs.resolveSibling(slug + ".preview.png");
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

        // 5) DB upsert
        long now = System.currentTimeMillis();
        boolean isExisting = repo.findById(ok.templateId()).isPresent();
        long created = isExisting ? repo.findById(ok.templateId()).orElseThrow().createdAt() : now;
        TemplateRepo.Row row = new TemplateRepo.Row(
                ok.templateId(), ownerUuid, ownerName,
                displayName, description, ok.yamlRelativePath(),
                false, false, 0L, created, now);
        if (!repo.upsert(row)) {
            return new Result.Failed("DB_FAILED", "templates upsert failed");
        }

        // 6) Registry 热重载（让前端 listTemplates 立即看到新条目）
        registry.reload();

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

        // 删 YAML + PNG
        try {
            Path yamlAbs = dataFolder.resolve(row.yamlPath());
            Files.deleteIfExists(yamlAbs);
            Path previewAbs = yamlAbs.resolveSibling(slugFromYamlPath(row.yamlPath()) + ".preview.png");
            Files.deleteIfExists(previewAbs);
            // 若 uuid 目录空了也清掉（v1 best-effort，失败忽略）
            Path uuidDir = yamlAbs.getParent();
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
            String yamlPath = "builtin:" + id;  // 占位；builtin 不可删，路径仅记录
            TemplateRepo.Row row = new TemplateRepo.Row(
                    id, null, null,
                    entry.spec().name() == null ? id : entry.spec().name(),
                    entry.spec().description(), yamlPath,
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

    private static String slugFromYamlPath(String yamlPath) {
        int slash = yamlPath.lastIndexOf('/');
        String filename = slash < 0 ? yamlPath : yamlPath.substring(slash + 1);
        return filename.endsWith(".yml") ? filename.substring(0, filename.length() - 4) : filename;
    }
}
