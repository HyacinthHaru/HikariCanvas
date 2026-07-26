package ac.haru.hikaricanvas.web;

import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import ac.haru.hikaricanvas.canvasfile.CanvasImportException;
import ac.haru.hikaricanvas.canvasfile.ImportResult;
import ac.haru.hikaricanvas.canvasfile.ProjectImporter;
import ac.haru.hikaricanvas.session.Session;
import ac.haru.hikaricanvas.session.SessionManager;
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
 * {@code POST /api/project/import} 端点——multipart 收 {@code .canvas} →
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
 * <p><b>大小闸</b>：{@code readAllBytes()} 之前先查 {@code file.size()}，超
 * {@code config.import.canvas-max-mb} 直接 413。解包内部虽有同一道闸，但那时字节已经全进堆了。</p>
 *
 * <p><b>还留着的口子</b>：{@code ctx.formParam("sessionId")} 会触发 Javalin 解析整个 multipart，
 * 也就是说未认证请求的 body 在鉴权之前就已经落到临时盘上了。要堵住得在 Javalin 侧配
 * {@code MultipartConfig} 的 {@code maxTotalRequestSize}（不在本类范围内）。默认绑回环 +
 * 公网必须反代（nginx 默认 {@code client_max_body_size 1m} 会先挡）是当前的兜底立场。</p>
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
    /** 权限判定（生产走主线程解析 {@code canvas.edit}，fail-closed）。 */
    private final Predicate<Session> permissionCheck;
    private final ProjectImporter importer;
    /**
     * 会话级 IP 绑定用（{@code security.md §2.5}）；测试构造传 null = 跳过该检查。
     *
     * <p>WS 帧每一条都过 {@code bindOrCheckIp}，而 HTTP 面此前完全没接——token 泄漏后
     * 异地重放可以直接 POST 一整个工程覆盖别人的画布。</p>
     */
    private final SessionManager sessionManager;

    /**
     * 生产构造：sessionId 经 {@link SessionManager#byId(String)} 解析，
     * 权限经 {@link MainThreadPerms} <b>在主线程</b>解析（离线 / 超时 fail-closed）。
     *
     * <p>权限判定必须 hop 主线程：{@code Bukkit.getPlayer(UUID)}（读 join/quit 时 mutate 的
     * 在线表）与 {@code Player.hasPermission}（读 LuckPerms reload 时主线程重算的
     * PermissibleBase）都是主线程专用 API，而本 handler 跑在 Jetty 线程。原实现直接裸调，
     * 与 7 个 WS dispatcher 早已统一的纪律不一致——那 7 个改了，HTTP 端点漏了。</p>
     *
     * <p>{@code canvas.edit} 在 {@code paper-plugin.yml} 里 default=true，但本端点
     * <b>不走</b> default-true 兜底：导入会整体替换画布内容，离线身份不该有这个能力，
     * 保持原有的「离线即拒」语义。</p>
     */
    public ProjectImportHandler(SessionManager sessionManager, ProjectImporter importer) {
        this(s -> Objects.requireNonNull(sessionManager, "sessionManager").byId(s),
                session -> {
                    MainThreadPerms.Resolved r = MainThreadPerms.resolve(
                            resolveHostPlugin(), session.playerUuid(), "canvas.edit");
                    return r.online() && r.granted(0);
                },
                importer,
                sessionManager);
    }

    /** 测试构造：注入鉴权 lookup + 权限 predicate + importer（不做 IP 绑定检查）。 */
    ProjectImportHandler(Function<String, Session> sessionLookup,
                         Predicate<Session> permissionCheck,
                         ProjectImporter importer) {
        this(sessionLookup, permissionCheck, importer, null);
    }

    ProjectImportHandler(Function<String, Session> sessionLookup,
                         Predicate<Session> permissionCheck,
                         ProjectImporter importer,
                         SessionManager sessionManager) {
        this.sessionLookup = Objects.requireNonNull(sessionLookup, "sessionLookup");
        this.permissionCheck = Objects.requireNonNull(permissionCheck, "permissionCheck");
        this.importer = Objects.requireNonNull(importer, "importer");
        this.sessionManager = sessionManager;
    }

    /**
     * 宿主插件实例。用 {@code getProvidingPlugin} 而不是加构造参数——装配层
     * （{@code WebServer}）的签名不动，改动面收在本文件内。
     * 单测环境（无 Bukkit server）返回 null，{@link MainThreadPerms} 会退回直接调用。
     */
    private static org.bukkit.plugin.Plugin resolveHostPlugin() {
        try {
            return org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(ProjectImportHandler.class);
        } catch (Throwable t) {
            return null;
        }
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

        // 2) 会话级 IP 绑定（security.md §2.5）。WS 帧每条都查，HTTP 面此前完全没接——
        // token 泄漏后异地重放可以直接 POST 一整个工程覆盖别人的画布。
        // 与 WS 侧同语义：首次绑定，之后不一致即拒；NO_SESSION 交给上面那道检查兜（走不到这）。
        if (sessionManager != null) {
            String callerIp = ctx.ip();
            if (sessionManager.bindOrCheckIp(sessionId, callerIp)
                    == SessionManager.IpBindResult.MISMATCH) {
                LOG.warning("project import rejected: session IP mismatch for " + sessionId);
                reject(ctx, 401, "AUTH_FAILED", "session bound to a different address");
                return;
            }
        }

        // 3) 权限（canvas.edit，主线程解析，fail-closed）
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
        // 先看大小再读进堆。解包内部也有同一道闸，但那是 readAllBytes 之后的事——
        // 真被打进一个巨大文件，堆先炸，闸根本没机会说话。
        long maxBytes = importer.maxCanvasBytes();
        if (file.size() > maxBytes) {
            reject(ctx, 413, "IMPORT_ZIP_TOO_LARGE",
                    "file is " + file.size() + " bytes; max " + maxBytes);
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
