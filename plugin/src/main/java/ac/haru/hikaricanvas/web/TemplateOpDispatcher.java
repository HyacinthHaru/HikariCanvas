package ac.haru.hikaricanvas.web;

import io.javalin.websocket.WsMessageContext;
import ac.haru.hikaricanvas.session.Session;
import ac.haru.hikaricanvas.session.SessionManager;

import java.util.Map;

import static ac.haru.hikaricanvas.web.WebHelpers.asPayloadMap;
import static ac.haru.hikaricanvas.web.WebHelpers.stringOrNull;

/**
 * {@code template.save / delete / feature / unfeature} 入口。
 * <p>鉴权用当前 session 的 player UUID 查 Bukkit live Player 拿 hasPermission。</p>
 * <p>{@code Bukkit.getPlayer} + {@code hasPermission} 主线程专用——经
 * {@link MainThreadPerms#resolve} 一次主线程 hop 解析在线态 + 全部所需节点（复用 auth
 * 路径同款 {@code callSyncMethod}），不再在 Jetty 线程裸调。</p>
 */
final class TemplateOpDispatcher {

    // dispatch 一次性解析的节点顺序（与 Resolved.granted 下标对应）。
    private static final int NODE_SAVE = 0;
    private static final int NODE_BYPASS_LIMIT = 1;
    private static final int NODE_DELETE_ANY = 2;
    private static final int NODE_DELETE_OWN = 3;
    private static final int NODE_FEATURE = 4;
    private static final String[] PERM_NODES = {
            "canvas.template.save",
            "canvas.template.bypass-limit",
            "canvas.template.delete.any",
            "canvas.template.delete.own",
            "canvas.template.feature",
    };

    private final SessionManager sessionManager;
    private final ac.haru.hikaricanvas.template.TemplatePublisher templatePublisher;
    /** 主线程权限解析用宿主插件；可为 null（测试装配走直接调用）。 */
    private final org.bukkit.plugin.Plugin plugin;

    TemplateOpDispatcher(SessionManager sessionManager,
                         ac.haru.hikaricanvas.template.TemplatePublisher templatePublisher) {
        this(sessionManager, templatePublisher, null);
    }

    TemplateOpDispatcher(SessionManager sessionManager,
                         ac.haru.hikaricanvas.template.TemplatePublisher templatePublisher,
                         org.bukkit.plugin.Plugin plugin) {
        this.sessionManager = sessionManager;
        this.templatePublisher = templatePublisher;
        this.plugin = plugin;
    }

    void dispatch(WsMessageContext ctx, Envelope in, String sessionId) {
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
        MainThreadPerms.Resolved perms = MainThreadPerms.resolve(plugin, s.playerUuid(), PERM_NODES);
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
        ac.haru.hikaricanvas.state.ProjectState state = s.projectState();
        if (state == null) {
            ctx.send(Envelope.error(in.id(), "WALL_NOT_FOUND", "no project state in session"));
            return;
        }
        String slug = stringOrNull(payload.get("slug"));
        String displayName = stringOrNull(payload.get("displayName"));
        String description = stringOrNull(payload.get("description"));
        ac.haru.hikaricanvas.template.TemplateExporter.ParamConfig paramConfig =
                parseParamConfig(payload.get("paramConfig"));
        boolean bypass = perms.granted(NODE_BYPASS_LIMIT);

        ac.haru.hikaricanvas.template.TemplatePublisher.Result result = templatePublisher.publish(
                s.playerUuid(), s.playerName(),
                slug, displayName, description, paramConfig, state, bypass);
        if (result instanceof ac.haru.hikaricanvas.template.TemplatePublisher.Result.Ok ok) {
            ctx.send(Envelope.of("ack", in.id(), Map.of("templateId", ok.templateId())));
        } else if (result instanceof ac.haru.hikaricanvas.template.TemplatePublisher.Result.Failed f) {
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
        ac.haru.hikaricanvas.template.TemplatePublisher.Result result =
                templatePublisher.delete(templateId, s.playerUuid(), isAdmin);
        if (result instanceof ac.haru.hikaricanvas.template.TemplatePublisher.Result.Ok ok) {
            ctx.send(Envelope.of("ack", in.id(), Map.of("templateId", ok.templateId())));
        } else if (result instanceof ac.haru.hikaricanvas.template.TemplatePublisher.Result.Failed f) {
            ctx.send(Envelope.error(in.id(), f.code(), f.message()));
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
        ac.haru.hikaricanvas.template.TemplatePublisher.Result result =
                templatePublisher.setFeatured(templateId, featured);
        if (result instanceof ac.haru.hikaricanvas.template.TemplatePublisher.Result.Ok ok) {
            ctx.send(Envelope.of("ack", in.id(), Map.of("templateId", ok.templateId(), "featured", featured)));
        } else if (result instanceof ac.haru.hikaricanvas.template.TemplatePublisher.Result.Failed f) {
            ctx.send(Envelope.error(in.id(), f.code(), f.message()));
        }
    }

    @SuppressWarnings("unchecked")
    private static ac.haru.hikaricanvas.template.TemplateExporter.ParamConfig parseParamConfig(Object raw) {
        if (raw == null) return ac.haru.hikaricanvas.template.TemplateExporter.ParamConfig.empty();
        if (!(raw instanceof Map<?, ?> m)) return ac.haru.hikaricanvas.template.TemplateExporter.ParamConfig.empty();
        Object textActionsObj = m.get("textActions");
        if (!(textActionsObj instanceof Map<?, ?> txMap)) {
            return ac.haru.hikaricanvas.template.TemplateExporter.ParamConfig.empty();
        }
        Map<String, ac.haru.hikaricanvas.template.TemplateExporter.AutoTextAction> textActions = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> e : txMap.entrySet()) {
            if (!(e.getKey() instanceof String autoId)) continue;
            if (!(e.getValue() instanceof Map<?, ?> v)) continue;
            String action = v.get("action") instanceof String sa ? sa : "keep";
            String name = v.get("name") instanceof String sn ? sn : null;
            String label = v.get("label") instanceof String sl ? sl : null;
            String desc = v.get("description") instanceof String sd ? sd : null;
            textActions.put(autoId, new ac.haru.hikaricanvas.template.TemplateExporter.AutoTextAction(
                    action, name, label, desc));
        }
        return new ac.haru.hikaricanvas.template.TemplateExporter.ParamConfig(textActions);
    }
}
