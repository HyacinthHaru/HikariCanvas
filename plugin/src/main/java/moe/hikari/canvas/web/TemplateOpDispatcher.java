package moe.hikari.canvas.web;

import io.javalin.websocket.WsMessageContext;
import moe.hikari.canvas.session.Session;
import moe.hikari.canvas.session.SessionManager;

import java.util.Map;

import static moe.hikari.canvas.web.WebHelpers.asPayloadMap;
import static moe.hikari.canvas.web.WebHelpers.stringOrNull;

/**
 * M14 创意工坊：{@code template.save / delete / feature / unfeature} 入口。
 * <p>鉴权用当前 session 的 player UUID 查 Bukkit live Player 拿 hasPermission。</p>
 */
final class TemplateOpDispatcher {

    private final SessionManager sessionManager;
    private final moe.hikari.canvas.template.TemplatePublisher templatePublisher;

    TemplateOpDispatcher(SessionManager sessionManager,
                         moe.hikari.canvas.template.TemplatePublisher templatePublisher) {
        this.sessionManager = sessionManager;
        this.templatePublisher = templatePublisher;
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
        org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(s.playerUuid());
        if (player == null) {
            ctx.send(Envelope.error(in.id(), "FORBIDDEN", "player offline"));
            return;
        }

        switch (in.op()) {
            case "template.save" -> handleTemplateSave(ctx, in, s, payload, player);
            case "template.delete" -> handleTemplateDelete(ctx, in, s, payload, player);
            case "template.feature" -> handleTemplateFeature(ctx, in, payload, player, true);
            case "template.unfeature" -> handleTemplateFeature(ctx, in, payload, player, false);
            default -> ctx.send(Envelope.error(in.id(), "INVALID_OP", "unreachable: " + in.op()));
        }
    }

    private void handleTemplateSave(WsMessageContext ctx, Envelope in, Session s,
                                    Map<String, Object> payload, org.bukkit.entity.Player player) {
        if (!player.hasPermission("canvas.template.save")) {
            ctx.send(Envelope.error(in.id(), "FORBIDDEN", "missing canvas.template.save"));
            return;
        }
        moe.hikari.canvas.state.ProjectState state = s.projectState();
        if (state == null) {
            ctx.send(Envelope.error(in.id(), "WALL_NOT_FOUND", "no project state in session"));
            return;
        }
        String slug = stringOrNull(payload.get("slug"));
        String displayName = stringOrNull(payload.get("displayName"));
        String description = stringOrNull(payload.get("description"));
        moe.hikari.canvas.template.TemplateExporter.ParamConfig paramConfig =
                parseParamConfig(payload.get("paramConfig"));
        boolean bypass = player.hasPermission("canvas.template.bypass-limit");

        moe.hikari.canvas.template.TemplatePublisher.Result result = templatePublisher.publish(
                s.playerUuid(), s.playerName(),
                slug, displayName, description, paramConfig, state, bypass);
        if (result instanceof moe.hikari.canvas.template.TemplatePublisher.Result.Ok ok) {
            ctx.send(Envelope.of("ack", in.id(), Map.of("templateId", ok.templateId())));
        } else if (result instanceof moe.hikari.canvas.template.TemplatePublisher.Result.Failed f) {
            ctx.send(Envelope.error(in.id(), f.code(), f.message()));
        }
    }

    private void handleTemplateDelete(WsMessageContext ctx, Envelope in, Session s,
                                      Map<String, Object> payload, org.bukkit.entity.Player player) {
        String templateId = stringOrNull(payload.get("templateId"));
        if (templateId == null) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", "templateId is required"));
            return;
        }
        boolean isAdmin = player.hasPermission("canvas.template.delete.any");
        boolean canDeleteOwn = player.hasPermission("canvas.template.delete.own");
        if (!isAdmin && !canDeleteOwn) {
            ctx.send(Envelope.error(in.id(), "FORBIDDEN", "missing delete permission"));
            return;
        }
        moe.hikari.canvas.template.TemplatePublisher.Result result =
                templatePublisher.delete(templateId, s.playerUuid(), isAdmin);
        if (result instanceof moe.hikari.canvas.template.TemplatePublisher.Result.Ok ok) {
            ctx.send(Envelope.of("ack", in.id(), Map.of("templateId", ok.templateId())));
        } else if (result instanceof moe.hikari.canvas.template.TemplatePublisher.Result.Failed f) {
            ctx.send(Envelope.error(in.id(), f.code(), f.message()));
        }
    }

    private void handleTemplateFeature(WsMessageContext ctx, Envelope in,
                                       Map<String, Object> payload,
                                       org.bukkit.entity.Player player, boolean featured) {
        if (!player.hasPermission("canvas.template.feature")) {
            ctx.send(Envelope.error(in.id(), "FORBIDDEN", "missing canvas.template.feature"));
            return;
        }
        String templateId = stringOrNull(payload.get("templateId"));
        if (templateId == null) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", "templateId is required"));
            return;
        }
        moe.hikari.canvas.template.TemplatePublisher.Result result =
                templatePublisher.setFeatured(templateId, featured);
        if (result instanceof moe.hikari.canvas.template.TemplatePublisher.Result.Ok ok) {
            ctx.send(Envelope.of("ack", in.id(), Map.of("templateId", ok.templateId(), "featured", featured)));
        } else if (result instanceof moe.hikari.canvas.template.TemplatePublisher.Result.Failed f) {
            ctx.send(Envelope.error(in.id(), f.code(), f.message()));
        }
    }

    @SuppressWarnings("unchecked")
    private static moe.hikari.canvas.template.TemplateExporter.ParamConfig parseParamConfig(Object raw) {
        if (raw == null) return moe.hikari.canvas.template.TemplateExporter.ParamConfig.empty();
        if (!(raw instanceof Map<?, ?> m)) return moe.hikari.canvas.template.TemplateExporter.ParamConfig.empty();
        Object textActionsObj = m.get("textActions");
        if (!(textActionsObj instanceof Map<?, ?> txMap)) {
            return moe.hikari.canvas.template.TemplateExporter.ParamConfig.empty();
        }
        Map<String, moe.hikari.canvas.template.TemplateExporter.AutoTextAction> textActions = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> e : txMap.entrySet()) {
            if (!(e.getKey() instanceof String autoId)) continue;
            if (!(e.getValue() instanceof Map<?, ?> v)) continue;
            String action = v.get("action") instanceof String sa ? sa : "keep";
            String name = v.get("name") instanceof String sn ? sn : null;
            String label = v.get("label") instanceof String sl ? sl : null;
            String desc = v.get("description") instanceof String sd ? sd : null;
            textActions.put(autoId, new moe.hikari.canvas.template.TemplateExporter.AutoTextAction(
                    action, name, label, desc));
        }
        return new moe.hikari.canvas.template.TemplateExporter.ParamConfig(textActions);
    }
}
