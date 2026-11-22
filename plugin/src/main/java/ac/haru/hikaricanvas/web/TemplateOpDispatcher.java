package ac.haru.hikaricanvas.web;

import ac.haru.hikaricanvas.session.Session;
import ac.haru.hikaricanvas.session.SessionManager;
import ac.haru.hikaricanvas.session.SessionRateLimiter;
import ac.haru.hikaricanvas.state.ProjectState;
import ac.haru.hikaricanvas.template.TemplateExporter;
import ac.haru.hikaricanvas.template.TemplatePublisher;
import io.javalin.websocket.WsMessageContext;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ac.haru.hikaricanvas.web.WebHelpers.asPayloadMap;
import static ac.haru.hikaricanvas.web.WebHelpers.stringOrNull;

/**
 * {@code template.save / delete / feature / unfeature} 入口。
 * <p>鉴权用当前 session 的 player UUID 查 Bukkit live Player 拿 hasPermission。</p>
 * <p>{@code Bukkit.getPlayer} + {@code hasPermission} 主线程专用——经
 * {@link MainThreadPerms#resolve} 一次主线程 hop 解析在线态 + 全部所需节点（复用 auth
 * 路径同款 {@code callSyncMethod}）。</p>
 */
final class TemplateOpDispatcher {

    // dispatch 一次性解析的节点顺序（与 Resolved.granted 下标对应）。
    private static final int NODE_SAVE = 0;
    private static final int NODE_BYPASS_LIMIT = 1;
    private static final int NODE_DELETE_ANY = 2;
    private static final int NODE_DELETE_OWN = 3;
    private static final int NODE_FEATURE = 4;
    private static final int NODE_USE_OTHERS = 5;
    private static final String[] PERM_NODES = {
            "canvas.template.save",
            "canvas.template.bypass-limit",
            "canvas.template.delete.any",
            "canvas.template.delete.own",
            "canvas.template.feature",
            "canvas.template.use-others",
    };

    private final SessionManager sessionManager;
    private final TemplatePublisher templatePublisher;
    /** 主线程权限解析用宿主插件；可为 null（测试装配走直接调用）。 */
    private final Plugin plugin;
    /**
     * 存 / 删成功后把该 session 最新可见模板列表推回去——前端只在 ready 帧拉一次列表，否则
     * gallery 要重连才见新模板。可空：测试装配传 null（不推送，不影响 ack 主链）。
     */
    private final TemplateListRefresher refresher;
    /** 输入限流；可空（测试装配传 null = 不限流）。{@code template.save} 含磁盘写与 DB 写。 */
    private final SessionRateLimiter rateLimiter;

    /** 由 {@code WebServer} 提供实现：算 {@code listVisibleTo} + {@code ctx.send} 一帧 {@code templates}。 */
    @FunctionalInterface
    interface TemplateListRefresher {
        void refresh(WsMessageContext ctx, Session session, boolean useOthersBypass);
    }

    TemplateOpDispatcher(SessionManager sessionManager,
                         TemplatePublisher templatePublisher) {
        this(sessionManager, templatePublisher, null, null);
    }

    TemplateOpDispatcher(SessionManager sessionManager,
                         TemplatePublisher templatePublisher,
                         Plugin plugin) {
        this(sessionManager, templatePublisher, plugin, null);
    }

    TemplateOpDispatcher(SessionManager sessionManager,
                         TemplatePublisher templatePublisher,
                         Plugin plugin,
                         TemplateListRefresher refresher) {
        this(sessionManager, templatePublisher, plugin, refresher, null);
    }

    TemplateOpDispatcher(SessionManager sessionManager,
                         TemplatePublisher templatePublisher,
                         Plugin plugin,
                         TemplateListRefresher refresher,
                         SessionRateLimiter rateLimiter) {
        this.sessionManager = sessionManager;
        this.templatePublisher = templatePublisher;
        this.plugin = plugin;
        this.refresher = refresher;
        this.rateLimiter = rateLimiter;
    }

    void dispatch(WsMessageContext ctx, Envelope in, String sessionId) {
        // 输入限流（template.save 含磁盘写 + DB 写）
        if (rateLimiter != null && !rateLimiter.allow(sessionId)) {
            ctx.send(Envelope.error(in.id(), "RATE_LIMITED", "input rate exceeded; slow down"));
            return;
        }
        Session s = sessionManager.byId(sessionId);
        if (s == null) {
            ctx.send(Envelope.error(in.id(), "SESSION_CLOSED", "no active session"));
            return;
        }
        Map<String, Object> payload;
        try {
            payload = asPayloadMap(in.payload());
        } catch (IllegalArgumentException iae) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", iae.getMessage()));
            return;
        }
        // 一次主线程 hop 解析在线态 + 全部节点；离线 / 超时 → online=false + 全节点 false。
        MainThreadPerms.Resolved perms = MainThreadPerms.resolve(plugin, s, PERM_NODES);
        if (!perms.online()) {
            ctx.send(Envelope.error(in.id(), "FORBIDDEN", "player offline"));
            return;
        }

        switch (in.op()) {
            case "template.save" -> handleTemplateSave(ctx, in, s, payload, perms);
            case "template.delete" -> handleTemplateDelete(ctx, in, s, payload, perms);
            case "template.feature" -> handleTemplateFeature(ctx, in, payload, perms, true);
            case "template.unfeature" -> handleTemplateFeature(ctx, in, payload, perms, false);
            default -> ctx.send(Envelope.error(in.id(), "INVALID_OP", "unreachable: " + in.op()));
        }
    }

    private void handleTemplateSave(WsMessageContext ctx, Envelope in, Session s,
                                    Map<String, Object> payload, MainThreadPerms.Resolved perms) {
        if (!perms.granted(NODE_SAVE)) {
            ctx.send(Envelope.error(in.id(), "FORBIDDEN", "missing canvas.template.save"));
            return;
        }
        ProjectState state = s.projectState();
        if (state == null) {
            ctx.send(Envelope.error(in.id(), "WALL_NOT_FOUND", "no project state in session"));
            return;
        }
        String slug = stringOrNull(payload.get("slug"));
        String displayName = stringOrNull(payload.get("displayName"));
        String description = stringOrNull(payload.get("description"));
        TemplateExporter.ParamConfig paramConfig =
                parseParamConfig(payload.get("paramConfig"));
        boolean bypass = perms.granted(NODE_BYPASS_LIMIT);

        // 传 wallId：pack 要把该墙的积木脚本一起打包，否则存成模板后脚本永久丢失
        TemplatePublisher.Result result = templatePublisher.publish(
                s.playerUuid(), s.playerName(),
                slug, displayName, description, paramConfig, state, bypass, s.wallId());
        if (result instanceof TemplatePublisher.Result.Ok ok) {
            ctx.send(Envelope.of("ack", in.id(), Map.of("templateId", ok.templateId())));
            pushTemplateRefresh(ctx, s, perms);
        } else if (result instanceof TemplatePublisher.Result.Failed f) {
            ctx.send(Envelope.error(in.id(), f.code(), f.message()));
        }
    }

    private void handleTemplateDelete(WsMessageContext ctx, Envelope in, Session s,
                                      Map<String, Object> payload, MainThreadPerms.Resolved perms) {
        String templateId = stringOrNull(payload.get("templateId"));
        if (templateId == null) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", "templateId is required"));
            return;
        }
        boolean isAdmin = perms.granted(NODE_DELETE_ANY);
        boolean canDeleteOwn = perms.granted(NODE_DELETE_OWN);
        if (!isAdmin && !canDeleteOwn) {
            ctx.send(Envelope.error(in.id(), "FORBIDDEN", "missing delete permission"));
            return;
        }
        TemplatePublisher.Result result =
                templatePublisher.delete(templateId, s.playerUuid(), isAdmin);
        if (result instanceof TemplatePublisher.Result.Ok ok) {
            ctx.send(Envelope.of("ack", in.id(), Map.of("templateId", ok.templateId())));
            pushTemplateRefresh(ctx, s, perms);
        } else if (result instanceof TemplatePublisher.Result.Failed f) {
            ctx.send(Envelope.error(in.id(), f.code(), f.message()));
        }
    }

    /** 存 / 删成功后推该 session 最新可见模板列表（refresher 由 WebServer 注入；null 则跳过）。 */
    private void pushTemplateRefresh(WsMessageContext ctx, Session s, MainThreadPerms.Resolved perms) {
        if (refresher != null) {
            refresher.refresh(ctx, s, perms.granted(NODE_USE_OTHERS));
        }
    }

    private void handleTemplateFeature(WsMessageContext ctx, Envelope in,
                                       Map<String, Object> payload,
                                       MainThreadPerms.Resolved perms, boolean featured) {
        if (!perms.granted(NODE_FEATURE)) {
            ctx.send(Envelope.error(in.id(), "FORBIDDEN", "missing canvas.template.feature"));
            return;
        }
        String templateId = stringOrNull(payload.get("templateId"));
        if (templateId == null) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", "templateId is required"));
            return;
        }
        TemplatePublisher.Result result =
                templatePublisher.setFeatured(templateId, featured);
        if (result instanceof TemplatePublisher.Result.Ok ok) {
            ctx.send(Envelope.of("ack", in.id(), Map.of("templateId", ok.templateId(), "featured", featured)));
        } else if (result instanceof TemplatePublisher.Result.Failed f) {
            ctx.send(Envelope.error(in.id(), f.code(), f.message()));
        }
    }

    /**
     * 解析 {@code paramConfig} payload：{@code textActions}（Map<autoId, action>）+ {@code fieldMarks}
     * （数组，每项 {@code {autoId, field}}）。任一段缺失 / 非法 → 容错跳过该段，不整体失败。
     */
    private static TemplateExporter.ParamConfig parseParamConfig(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return TemplateExporter.ParamConfig.empty();
        }
        Map<String, TemplateExporter.AutoTextAction> textActions = new LinkedHashMap<>();
        if (m.get("textActions") instanceof Map<?, ?> txMap) {
            for (Map.Entry<?, ?> e : txMap.entrySet()) {
                if (!(e.getKey() instanceof String autoId)) continue;
                if (!(e.getValue() instanceof Map<?, ?> v)) continue;
                String action = v.get("action") instanceof String sa ? sa : "keep";
                String name = v.get("name") instanceof String sn ? sn : null;
                String label = v.get("label") instanceof String sl ? sl : null;
                String desc = v.get("description") instanceof String sd ? sd : null;
                textActions.put(autoId, new TemplateExporter.AutoTextAction(
                        action, name, label, desc));
            }
        }
        List<TemplateExporter.FieldMark> fieldMarks = new ArrayList<>();
        if (m.get("fieldMarks") instanceof List<?> fmList) {
            for (Object o : fmList) {
                if (!(o instanceof Map<?, ?> fmMap)) continue;
                String autoId = fmMap.get("autoId") instanceof String a ? a : null;
                String field = fmMap.get("field") instanceof String f ? f : null;
                if (autoId != null && field != null) {
                    fieldMarks.add(new TemplateExporter.FieldMark(autoId, field));
                }
            }
        }
        return new TemplateExporter.ParamConfig(textActions, fieldMarks);
    }
}
