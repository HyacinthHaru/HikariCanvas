package moe.hikari.canvas.web;

import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import moe.hikari.canvas.canvasfile.CanvasImportException;
import moe.hikari.canvas.canvasfile.ImportResult;
import moe.hikari.canvas.canvasfile.ProjectImporter;
import moe.hikari.canvas.session.Session;
import moe.hikari.canvas.session.SessionManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 0.8-A2 Task 13：{@code POST /api/project/import} 端点——multipart 收 {@code .canvas} →
 * 鉴权 → {@link ProjectImporter#importInto} → 成功 {@code {ok:true,warnings:[...]}} /
 * 失败 {@code {error,message}} + 状态码。
 *
 * <p>ctx 范式照 {@code image/UploadHandler#handleUpload}：{@code ctx.formParam("sessionId")} 取会话、
 * {@code ctx.uploadedFile("file").content().readAllBytes()} 取字节、{@code reject} =
 * {@code ctx.status(s).json(Map.of("error",code,"message",msg))}。</p>
 *
 * <p><b>权限</b>：导入是「整体替换工程」的破坏性写，绑 {@code canvas.edit} 面（与上传绑
 * {@code canvas.upload} 同理）。fail-closed：拿不到 live {@link Player}（玩家离线）即视为无权限拒。</p>
 *
 * <p><b>错误码 → HTTP status</b>（{@code docs/import-export.md §4}）：{@code IMPORT_ZIP_TOO_LARGE}→413、
 * {@code IMPORT_SPEC_UNSUPPORTED}/{@code IMPORT_SIZE_MISMATCH}→409、{@code IMPORT_BAD_ENTRY}/
 * {@code IMPORT_MALFORMED}→400。</p>
 *
 * <p>鉴权 / 权限抽成可注入 seam（照 {@code CommandTemplateHandler}）：生产经 {@link SessionManager}
 * + {@link Bukkit} 解析，测试注入 fake，端点 e2e 无须 MockBukkit。</p>
 */
public final class ProjectImportHandler {

    private static final Logger LOG = Logger.getLogger(ProjectImportHandler.class.getName());

    /** sessionId → Session（生产传 {@code sessionManager::byId}）；返回 null = 未鉴权。 */
    private final Function<String, Session> sessionLookup;
    /** 权限判定（生产经 live Player.hasPermission("canvas.edit") fail-closed）。 */
    private final Predicate<Session> permissionCheck;
    private final ProjectImporter importer;

    /**
     * 生产构造：sessionId 经 {@link SessionManager#byId(String)} 解析，权限经 live
     * {@link Player#hasPermission(String)} 判定（离线 fail-closed）。
     */
    public ProjectImportHandler(SessionManager sessionManager, ProjectImporter importer) {
        this(s -> Objects.requireNonNull(sessionManager, "sessionManager").byId(s),
                session -> {
                    Player player = Bukkit.getPlayer(session.playerUuid());
                    return player != null && player.hasPermission("canvas.edit");
                },
                importer);
    }

    /** 测试构造：注入鉴权 lookup + 权限 predicate + importer。 */
    ProjectImportHandler(Function<String, Session> sessionLookup,
                         Predicate<Session> permissionCheck,
                         ProjectImporter importer) {
        this.sessionLookup = Objects.requireNonNull(sessionLookup, "sessionLookup");
        this.permissionCheck = Objects.requireNonNull(permissionCheck, "permissionCheck");
        this.importer = Objects.requireNonNull(importer, "importer");
    }

    /** 入口：处理 {@code POST /api/project/import}。 */
    public void handleImport(Context ctx) {
        // 1) sessionId 鉴权
        String sessionId = ctx.formParam("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            reject(ctx, 401, "NO_SESSION", "missing sessionId");
            return;
        }
        Session session = sessionLookup.apply(sessionId);
        if (session == null) {
            reject(ctx, 401, "NO_SESSION", "unknown session");
            return;
        }
        if (session.wallId() == null || session.editSession() == null
                || session.projectState() == null) {
            reject(ctx, 409, "SESSION_NOT_READY", "session has no active wall to import into");
            return;
        }

        // 2) 权限（canvas.edit，fail-closed）
        if (!permissionCheck.test(session)) {
            reject(ctx, 403, "FORBIDDEN", "missing canvas.edit permission");
            return;
        }

        // 3) multipart file
        UploadedFile file = ctx.uploadedFile("file");
        if (file == null) {
            reject(ctx, 400, "NO_FILE", "missing 'file' multipart field");
            return;
        }
        byte[] bytes;
        try (InputStream in = file.content()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            // 不 echo IOException message（可能含 multipart tmp 路径）
            LOG.log(Level.WARNING, "project import read failed", e);
            reject(ctx, 400, "IMPORT_MALFORMED", "failed to read uploaded file");
            return;
        }

        // 4) 编排 + 错误码 → HTTP status 映射
        try {
            ImportResult result = importer.importInto(session, bytes, session.playerUuid());
            ctx.status(200).json(Map.of("ok", true, "warnings", result.warnings()));
        } catch (CanvasImportException e) {
            int status = statusFor(e.code());
            ctx.status(status).json(Map.of("error", e.code(),
                    "message", e.getMessage() == null ? "" : e.getMessage()));
        } catch (RuntimeException e) {
            // 兜底：编排期意外运行期异常不静默 500-without-body
            LOG.log(Level.WARNING, "project import failed unexpectedly", e);
            ctx.status(500).json(Map.of("error", "INTERNAL", "message", "import failed"));
        }
    }

    /** {@code IMPORT_*} 错误码 → HTTP status（见类注释）。 */
    private static int statusFor(String code) {
        return switch (code) {
            case "IMPORT_ZIP_TOO_LARGE" -> 413;
            case "IMPORT_SPEC_UNSUPPORTED", "IMPORT_SIZE_MISMATCH" -> 409;
            case "IMPORT_BAD_ENTRY", "IMPORT_MALFORMED" -> 400;
            default -> 400;
        };
    }

    private void reject(Context ctx, int status, String code, String message) {
        ctx.status(status).json(Map.of("error", code, "message", message == null ? "" : message));
    }
}
