package moe.hikari.canvas.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.websocket.WsMessageContext;
import moe.hikari.canvas.script.ScriptPermissions;
import moe.hikari.canvas.script.ScriptRule;
import moe.hikari.canvas.script.ScriptRuleValidator;
import moe.hikari.canvas.script.ScriptStore;
import moe.hikari.canvas.session.Session;
import moe.hikari.canvas.session.SessionManager;
import moe.hikari.canvas.session.SessionRateLimiter;
import moe.hikari.canvas.state.PatchOp;
import moe.hikari.canvas.state.StatePatch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static moe.hikari.canvas.web.WebHelpers.asPayloadMap;
import static moe.hikari.canvas.web.WebHelpers.stringOrNull;

/**
 * 0.7.0 P1：{@code script.*} WS op 分发器（5 op）。契约 {@code docs/scripting.md §2}。
 *
 * <h2>5 个 op</h2>
 *
 * <ul>
 *   <li>{@code script.create {rule}} — 新建规则（id / wallId 服务端权威）</li>
 *   <li>{@code script.update {ruleId, rule}} — 全量替换（先 find 确认属本 wall，防跨墙改）</li>
 *   <li>{@code script.delete {ruleId}} — 删除；不存在也推 remove patch（幂等，照 alias clear 先例）</li>
 *   <li>{@code script.enable {ruleId, enabled}} — 翻转开关</li>
 *   <li>{@code script.test {ruleId}} — P1 固定 {@code SCRIPT_ENGINE_UNAVAILABLE}；
 *       P2 经 {@link ScriptTestSeam} 接 ScriptRunner</li>
 * </ul>
 *
 * <h2>权限</h2>
 *
 * <p>全部 op 先查基础节点 {@link ScriptPermissions#NODE_EDIT}（default=true；offline 玩家
 * 兜底放行，照 {@link VariableAliasDispatcher} 的 own 节点写法）。create / update 解析出
 * rule 后再对 {@link ScriptPermissions#requiredFacets} 逐面查——<b>面节点无兜底</b>：
 * 服主收回 {@code canvas.script.sound} / {@code canvas.script.command} /
 * {@code canvas.script.trigger.global} 时必须真拒（危险面纪律）。</p>
 *
 * <p><b>不读 wall lock</b>（lock-state 纪律：后端编辑 op 不读 lock）。</p>
 *
 * <h2>state.patch 路径</h2>
 *
 * <p>{@code /scripts/<encoded ruleId>}（RFC 6901 段编码）。create / update / enable 推 add
 * （前端 mirror set 幂等，replace 语义统一用 add，照 alias 注释先例）；delete 推 remove。
 * 同 {@link VariableAliasDispatcher}：不走 EditSession（脚本不影响 ProjectState 像素 /
 * version / undo），patch 携 {@code currentVersion(s)}——Ultrareview 2026-05-25 #17：
 * 不要写 0，前端 applyPatch 会把空 projectOps 的 patch.version 当 state.version 覆盖，
 * 写 0 会把 ProjectState.version 倒退影响后续 op 冲突判定。</p>
 */
final class ScriptOpDispatcher {

    /** 类内单例 mapper：ScriptRule 注解自带双向多态 serializer，无需外部配置。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** create 解析期占位 id（store.create 会忽略 incoming.id 重新生成）。 */
    private static final String PENDING_ID = "sr-pending";

    /**
     * P2 seam：脚本试运行入口。P1 不实现引擎，seam 为 null 时 {@code script.test}
     * 固定回 {@code SCRIPT_ENGINE_UNAVAILABLE}；P2 ScriptRunner 落地后注入，
     * 返回值整体作为 ack payload。
     */
    interface ScriptTestSeam {
        Map<String, Object> run(String wallId, String ruleId);
    }

    /** 解析结果：error 非 null 即 INVALID_PAYLOAD message（此时 rule 为 null）。 */
    record ParsedRule(ScriptRule rule, String error) {}

    private final SessionManager sessionManager;
    private final SessionRateLimiter rateLimiter;
    private final ScriptStore store;
    private final moe.hikari.canvas.storage.WallRepo wallRepo;
    private final OpPushCallback push;
    private final moe.hikari.canvas.storage.AuditLog auditLog;
    /** 主线程权限解析用宿主插件；可为 null（测试装配走直接调用）。 */
    private final org.bukkit.plugin.Plugin plugin;
    private volatile ScriptTestSeam testSeam;

    ScriptOpDispatcher(SessionManager sessionManager,
                       SessionRateLimiter rateLimiter,
                       ScriptStore store,
                       moe.hikari.canvas.storage.WallRepo wallRepo,
                       OpPushCallback push,
                       moe.hikari.canvas.storage.AuditLog auditLog,
                       org.bukkit.plugin.Plugin plugin) {
        this.sessionManager = sessionManager;
        this.rateLimiter = rateLimiter;
        this.store = store;
        this.wallRepo = wallRepo;
        this.push = push;
        this.auditLog = auditLog;
        this.plugin = plugin;
    }

    /** P2 ScriptRunner 落地后注入；volatile 多线程可见。 */
    void setTestSeam(ScriptTestSeam seam) {
        this.testSeam = seam;
    }

    void dispatch(WsMessageContext ctx, Envelope in, String sessionId) {
        if (!rateLimiter.allow(sessionId)) {
            ctx.send(Envelope.error(in.id(), "RATE_LIMITED",
                    "input rate exceeded; slow down"));
            return;
        }
        Session s = sessionManager.byId(sessionId);
        if (s == null) {
            ctx.send(Envelope.error(in.id(), "SESSION_CLOSED", "no active session"));
            return;
        }
        if (s.wallId() == null) {
            ctx.send(Envelope.error(in.id(), "WALL_NOT_FOUND",
                    "session has no bound wall"));
            return;
        }
        if (store == null) {
            ctx.send(Envelope.error(in.id(), "INTERNAL_ERROR",
                    "script store not configured"));
            return;
        }

        Map<String, Object> payload;
        try {
            payload = asPayloadMap(in.payload());
        } catch (IllegalArgumentException iae) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", iae.getMessage()));
            return;
        }

        // 基础权限（恒查；NODE_EDIT default=true → offline 兜底放行）+ wall 存在性
        if (!checkBasePermission(ctx, in, sessionId, s)) return;

        String op = in.op();
        String wallId = s.wallId();
        try {
            switch (op) {
                case "script.create" -> handleCreate(ctx, in, sessionId, s, wallId, payload);
                case "script.update" -> handleUpdate(ctx, in, sessionId, s, wallId, payload);
                case "script.delete" -> handleDelete(ctx, in, sessionId, s, wallId, payload);
                case "script.enable" -> handleEnable(ctx, in, sessionId, s, wallId, payload);
                case "script.test" -> handleTest(ctx, in, wallId, payload);
                default -> ctx.send(Envelope.error(in.id(), "INVALID_OP",
                        "unknown script op: " + op));
            }
        } catch (Exception e) {
            // M16 P6.1 错误消息脱敏：内部异常不外泄细节
            ctx.send(Envelope.error(in.id(), "INTERNAL_ERROR", "script op failed"));
        }
    }

    // ──────────────────────────────────────────────────────────
    //  per-op handlers
    // ──────────────────────────────────────────────────────────

    private void handleCreate(WsMessageContext ctx, Envelope in, String sessionId,
                              Session s, String wallId, Map<String, Object> payload) {
        ParsedRule parsed = parseIncomingRule(payload, wallId);
        if (parsed.error() != null) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", parsed.error()));
            return;
        }
        Optional<String> invalid = ScriptRuleValidator.validate(parsed.rule());
        if (invalid.isPresent()) {
            ctx.send(Envelope.error(in.id(), "SCRIPT_INVALID", invalid.get()));
            return;
        }
        Set<String> facets = ScriptPermissions.requiredFacets(parsed.rule());
        if (!checkFacets(ctx, in, sessionId, s, "script.create", facets)) return;

        ScriptRule created;
        try {
            created = store.create(wallId, parsed.rule());
        } catch (ScriptStore.QuotaExceededException qe) {
            ctx.send(Envelope.error(in.id(), "SCRIPT_QUOTA_EXCEEDED", qe.getMessage()));
            return;
        }

        Map<String, Object> ruleMap = ruleToMap(created);
        pushAdd(sessionId, s, created.id(), ruleMap);
        recordAudit("SCRIPT_CREATE", sessionId, s, wallId, created, facets);
        ctx.send(Envelope.of("ack", in.id(), Map.of("rule", ruleMap)));
    }

    private void handleUpdate(WsMessageContext ctx, Envelope in, String sessionId,
                              Session s, String wallId, Map<String, Object> payload) {
        String ruleId = stringOrNull(payload.get("ruleId"));
        if (ruleId == null || ruleId.isEmpty()) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", "ruleId required"));
            return;
        }
        // 防跨墙改：ruleId 必须属于本 session 的 wall（store.update 仅按反查索引定位，
        // 不带 wall 校验——这里先 find 把"别人墙的 ruleId"挡掉）
        if (store.find(wallId, ruleId).isEmpty()) {
            ctx.send(Envelope.error(in.id(), "SCRIPT_NOT_FOUND",
                    "script rule not found: " + ruleId));
            return;
        }
        ParsedRule parsed = parseIncomingRule(payload, wallId);
        if (parsed.error() != null) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", parsed.error()));
            return;
        }
        Optional<String> invalid = ScriptRuleValidator.validate(parsed.rule());
        if (invalid.isPresent()) {
            ctx.send(Envelope.error(in.id(), "SCRIPT_INVALID", invalid.get()));
            return;
        }
        Set<String> facets = ScriptPermissions.requiredFacets(parsed.rule());
        if (!checkFacets(ctx, in, sessionId, s, "script.update", facets)) return;

        ScriptRule updated;
        try {
            updated = store.update(ruleId, parsed.rule());
        } catch (ScriptStore.NotFoundException nfe) {
            ctx.send(Envelope.error(in.id(), "SCRIPT_NOT_FOUND", nfe.getMessage()));
            return;
        }

        Map<String, Object> ruleMap = ruleToMap(updated);
        pushAdd(sessionId, s, updated.id(), ruleMap);
        recordAudit("SCRIPT_UPDATE", sessionId, s, wallId, updated, facets);
        ctx.send(Envelope.of("ack", in.id(), Map.of("rule", ruleMap)));
    }

    private void handleDelete(WsMessageContext ctx, Envelope in, String sessionId,
                              Session s, String wallId, Map<String, Object> payload) {
        String ruleId = stringOrNull(payload.get("ruleId"));
        if (ruleId == null || ruleId.isEmpty()) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", "ruleId required"));
            return;
        }
        ScriptRule existing = store.find(wallId, ruleId).orElse(null);
        boolean removed = false;
        if (existing != null) {
            try {
                store.delete(wallId, ruleId);
                removed = true;
            } catch (ScriptStore.NotFoundException ignored) {
                // find 与 delete 间被并发删——视作不存在，继续幂等推 remove
            }
        }

        // 规则不存在也推 remove，让前端 mirror 收敛（幂等，照 alias clear 先例）
        String path = "/scripts/" + WebHelpers.encodeJsonPointerSegment(ruleId);
        push.pushPatch(sessionId,
                new StatePatch(currentVersion(s), List.of(PatchOp.remove(path))));

        recordAudit("SCRIPT_DELETE", sessionId, s, wallId, existing, Set.of(), ruleId);
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("ruleId", ruleId);
        ack.put("removed", removed);
        ctx.send(Envelope.of("ack", in.id(), ack));
    }

    private void handleEnable(WsMessageContext ctx, Envelope in, String sessionId,
                              Session s, String wallId, Map<String, Object> payload) {
        String ruleId = stringOrNull(payload.get("ruleId"));
        if (ruleId == null || ruleId.isEmpty()) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", "ruleId required"));
            return;
        }
        Object enabledRaw = payload.get("enabled");
        if (!(enabledRaw instanceof Boolean enabled)) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", "enabled (boolean) required"));
            return;
        }

        ScriptRule flipped;
        try {
            flipped = store.setEnabled(wallId, ruleId, enabled);
        } catch (ScriptStore.NotFoundException nfe) {
            ctx.send(Envelope.error(in.id(), "SCRIPT_NOT_FOUND", nfe.getMessage()));
            return;
        }

        Map<String, Object> ruleMap = ruleToMap(flipped);
        pushAdd(sessionId, s, flipped.id(), ruleMap);
        recordAudit("SCRIPT_ENABLE", sessionId, s, wallId, flipped, Set.of());
        ctx.send(Envelope.of("ack", in.id(), Map.of("rule", ruleMap)));
    }

    private void handleTest(WsMessageContext ctx, Envelope in,
                            String wallId, Map<String, Object> payload) {
        String ruleId = stringOrNull(payload.get("ruleId"));
        if (ruleId == null || ruleId.isEmpty()) {
            ctx.send(Envelope.error(in.id(), "INVALID_PAYLOAD", "ruleId required"));
            return;
        }
        ScriptTestSeam seam = this.testSeam;
        if (seam == null) {
            // P1：引擎未落地，固定拒——P2 接 ScriptRunner 后注入 seam
            ctx.send(Envelope.error(in.id(), "SCRIPT_ENGINE_UNAVAILABLE",
                    "script engine lands in 0.7.0-P2"));
            return;
        }
        ctx.send(Envelope.of("ack", in.id(), seam.run(wallId, ruleId)));
    }

    // ──────────────────────────────────────────────────────────
    //  payload → ScriptRule 解析（包级静态，单测直接打）
    // ──────────────────────────────────────────────────────────

    /**
     * 把 {@code payload.rule} 解析为 {@link ScriptRule}：
     * <ul>
     *   <li>id / wallId 服务端权威——客户端给了也覆写（id 占位，store.create 再生成；
     *       wallId = session 的 wall）</li>
     *   <li>enabled 缺省 true；blockLayout 缺省 {@code "{}"}</li>
     *   <li>trigger / actions 经各自多态 deserializer；非法 type / 非数组等 →
     *       {@code ParsedRule.error}（INVALID_PAYLOAD message）</li>
     * </ul>
     */
    static ParsedRule parseIncomingRule(Map<String, Object> payload, String wallId) {
        Object raw = payload.get("rule");
        if (!(raw instanceof Map<?, ?> ruleRaw)) {
            return new ParsedRule(null, "rule (object) required");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ruleRaw.entrySet()) {
            if (e.getKey() instanceof String k) {
                m.put(k, e.getValue());
            }
        }
        // 服务端权威字段覆写 + 缺省补齐
        m.put("id", PENDING_ID);
        m.put("wallId", wallId);
        if (!(m.get("enabled") instanceof Boolean)) {
            m.put("enabled", Boolean.TRUE);
        }
        if (m.get("blockLayout") == null) {
            m.put("blockLayout", "{}");
        }
        try {
            return new ParsedRule(MAPPER.convertValue(m, ScriptRule.class), null);
        } catch (IllegalArgumentException iae) {
            return new ParsedRule(null, "rule malformed: " + rootMessage(iae));
        }
    }

    /** rule → wire 形态 Map（注解自带 serializer，trigger/actions 扁平 + type 判别）。 */
    private static Map<String, Object> ruleToMap(ScriptRule rule) {
        return MAPPER.convertValue(rule, new TypeReference<Map<String, Object>>() {});
    }

    // ──────────────────────────────────────────────────────────
    //  权限
    // ──────────────────────────────────────────────────────────

    /**
     * 基础节点 {@code canvas.script.edit}（default=true）+ wall 存在性。
     * offline 玩家（MainThreadPerms 返 false）对 NODE_EDIT 兜底放行——
     * 照 {@link VariableAliasDispatcher} 的 own 节点写法。
     */
    private boolean checkBasePermission(WsMessageContext ctx, Envelope in,
                                        String sessionId, Session s) {
        UUID callerUuid = s.playerUuid();
        moe.hikari.canvas.storage.WallRepo.Wall wall =
                wallRepo == null ? null : wallRepo.loadById(s.wallId()).orElse(null);
        if (wall == null) {
            ctx.send(Envelope.error(in.id(), "WALL_NOT_FOUND", "wall not found"));
            return false;
        }
        boolean granted = MainThreadPerms.hasPermission(plugin, callerUuid,
                ScriptPermissions.NODE_EDIT);
        if (!granted) {
            // NODE_EDIT default=true：offline / 解析失败兜底放行（与 own 节点惯例一致）
            granted = true;
        }
        return granted;
    }

    /**
     * 面权限逐一查，<b>无兜底</b>（被服主收回必须真拒）。任一缺 →
     * {@code PERMISSION_DENIED}（message 含缺的节点）+ audit。
     */
    private boolean checkFacets(WsMessageContext ctx, Envelope in, String sessionId,
                                Session s, String op, Set<String> facets) {
        if (facets.isEmpty()) return true;
        UUID callerUuid = s.playerUuid();
        // 一次主线程 hop 批量解析（≤3 节点）；offline / 超时 → 全 false（fail-closed）
        String[] nodes = facets.toArray(new String[0]);
        MainThreadPerms.Resolved resolved = MainThreadPerms.resolve(plugin, callerUuid, nodes);
        for (int i = 0; i < nodes.length; i++) {
            if (resolved.granted(i)) continue;
            String missing = nodes[i];
            if (auditLog != null) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("operation", op);
                details.put("required", missing);
                details.put("wall_id", s.wallId());
                auditLog.record("PERMISSION_DENIED",
                        callerUuid == null ? null : callerUuid.toString(),
                        s.playerName(), sessionId, null, details);
            }
            ctx.send(Envelope.error(in.id(), "PERMISSION_DENIED",
                    "missing permission: " + missing));
            return false;
        }
        return true;
    }

    // ──────────────────────────────────────────────────────────
    //  helpers
    // ──────────────────────────────────────────────────────────

    /** create/update/enable 共用：推 {@code add /scripts/<encoded ruleId>}（replace 语义幂等）。 */
    private void pushAdd(String sessionId, Session s, String ruleId, Map<String, Object> ruleMap) {
        String path = "/scripts/" + WebHelpers.encodeJsonPointerSegment(ruleId);
        push.pushPatch(sessionId,
                new StatePatch(currentVersion(s), List.of(PatchOp.add(path, ruleMap))));
    }

    /**
     * Ultrareview 2026-05-25 #17：从 session 拿当前 ProjectState.version。
     * 脚本通道不改像素 → 不 bump，但要保持前端 mirror 看到的版本号一致；写 0 会把
     * ProjectState.version 倒退。session 无 ProjectState（极早期）回 0。
     */
    private static long currentVersion(Session s) {
        if (s == null) return 0L;
        moe.hikari.canvas.state.ProjectState ps = s.projectState();
        return ps == null ? 0L : ps.version();
    }

    private void recordAudit(String event, String sessionId, Session s, String wallId,
                             ScriptRule rule, Set<String> facets) {
        recordAudit(event, sessionId, s, wallId, rule, facets,
                rule == null ? null : rule.id());
    }

    /** details 恒含 wall_id / rule_id / rule_name；facets 非空时逗号串接附上。 */
    private void recordAudit(String event, String sessionId, Session s, String wallId,
                             ScriptRule rule, Set<String> facets, String ruleId) {
        if (auditLog == null) return;
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("wall_id", wallId);
        if (ruleId != null) d.put("rule_id", ruleId);
        if (rule != null) d.put("rule_name", rule.name());
        if (facets != null && !facets.isEmpty()) {
            d.put("facets", String.join(",", facets.stream().sorted().toList()));
        }
        auditLog.record(event,
                s.playerUuid() == null ? null : s.playerUuid().toString(),
                s.playerName(), sessionId, null, d);
    }

    /** 取异常链最深 message（Jackson convertValue 包两层 IllegalArgumentException）。 */
    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        if (msg == null) msg = "unparseable rule";
        // 截断防超长 Jackson 路径信息刷屏
        return msg.length() > 300 ? msg.substring(0, 300) : msg;
    }
}
