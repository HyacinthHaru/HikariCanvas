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
 * <p>全部 op 先查基础节点 {@link ScriptPermissions#NODE_EDIT}（default=true；仅 offline
 * 玩家兜底放行，在线被显式收回必须真拒——批次3 #1，与 alias 模板的 own 兜底等价语义）。
 * create / update 解析出
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
     *
     * <p><b>facet 语义（契约 scripting.md §4.1 已定）</b>：试跑即真实执行（D5），
     * 因此 {@code seam.run} 前同样过 {@link #checkFacets}（sound / command 面缺失真拒），
     * 并记 {@code SCRIPT_TEST} audit——P2 接 ScriptRunner 时此层已就位，引擎侧无需重查。</p>
     */
    interface ScriptTestSeam {
        Map<String, Object> run(String wallId, String ruleId);
    }

    /**
     * 解析结果：error 非 null 即 INVALID_PAYLOAD message（此时 rule 为 null）；
     * cause 是解析期原始异常（仅 error 路径非 null，供 server 日志记完整堆栈——
     * 批次3 #5：外发 message 只留首行，细节进日志）。
     */
    record ParsedRule(ScriptRule rule, String error, Throwable cause) {}

    private final SessionManager sessionManager;
    private final SessionRateLimiter rateLimiter;
    private final ScriptStore store;
    private final moe.hikari.canvas.storage.WallRepo wallRepo;
    private final OpPushCallback push;
    private final moe.hikari.canvas.storage.AuditLog auditLog;
    /** 主线程权限解析用宿主插件；可为 null（测试装配走直接调用）。 */
    private final org.bukkit.plugin.Plugin plugin;
    /** server 日志（批次3 #2：catch-all 不再静默吞异常）；可为 null（兜底跳过记录）。 */
    private final java.util.logging.Logger log;
    private volatile ScriptTestSeam testSeam;

    ScriptOpDispatcher(SessionManager sessionManager,
                       SessionRateLimiter rateLimiter,
                       ScriptStore store,
                       moe.hikari.canvas.storage.WallRepo wallRepo,
                       OpPushCallback push,
                       moe.hikari.canvas.storage.AuditLog auditLog,
                       org.bukkit.plugin.Plugin plugin,
                       java.util.logging.Logger log) {
        this.sessionManager = sessionManager;
        this.rateLimiter = rateLimiter;
        this.store = store;
        this.wallRepo = wallRepo;
        this.push = push;
        this.auditLog = auditLog;
        this.plugin = plugin;
        this.log = log;
    }

    /** P2 ScriptRunner 落地后注入；volatile 多线程可见。 */
    void setTestSeam(ScriptTestSeam seam) {
        this.testSeam = seam;
    }

    void dispatch(WsMessageContext ctx, Envelope in, String sessionId) {
        ctx.send(dispatchToEnvelope(in, sessionId));
    }

    /**
     * dispatch 主体，返回待发送的 {@link Envelope}（package-private，照
     * {@code TimelinePlaybackDispatchTest} 的范式让行为级测试绕开 final 的
     * {@code WsMessageContext} 直接驱动）。
     */
    Envelope dispatchToEnvelope(Envelope in, String sessionId) {
        if (!rateLimiter.allow(sessionId)) {
            return Envelope.error(in.id(), "RATE_LIMITED",
                    "input rate exceeded; slow down");
        }
        Session s = sessionManager.byId(sessionId);
        if (s == null) {
            return Envelope.error(in.id(), "SESSION_CLOSED", "no active session");
        }
        if (s.wallId() == null) {
            return Envelope.error(in.id(), "WALL_NOT_FOUND",
                    "session has no bound wall");
        }
        if (store == null) {
            return Envelope.error(in.id(), "INTERNAL_ERROR",
                    "script store not configured");
        }

        Map<String, Object> payload;
        try {
            payload = asPayloadMap(in.payload());
        } catch (IllegalArgumentException iae) {
            return Envelope.error(in.id(), "INVALID_PAYLOAD", iae.getMessage());
        }

        // 基础权限（恒查；NODE_EDIT default=true → 仅 offline 兜底放行）+ wall 存在性
        Envelope baseDenied = checkBasePermission(in, sessionId, s);
        if (baseDenied != null) return baseDenied;

        String op = in.op();
        String wallId = s.wallId();
        try {
            return switch (op) {
                case "script.create" -> handleCreate(in, sessionId, s, wallId, payload);
                case "script.update" -> handleUpdate(in, sessionId, s, wallId, payload);
                case "script.delete" -> handleDelete(in, sessionId, s, wallId, payload);
                case "script.enable" -> handleEnable(in, sessionId, s, wallId, payload);
                case "script.test" -> handleTest(in, sessionId, s, wallId, payload);
                default -> Envelope.error(in.id(), "INVALID_OP",
                        "unknown script op: " + op);
            };
        } catch (Exception e) {
            // M16 P6.1 错误消息脱敏：client 只收固定文案；批次3 #2：完整异常进 server 日志
            if (log != null) {
                log.log(java.util.logging.Level.WARNING, "script op failed: " + op, e);
            }
            return Envelope.error(in.id(), "INTERNAL_ERROR", "script op failed");
        }
    }

    // ──────────────────────────────────────────────────────────
    //  per-op handlers
    // ──────────────────────────────────────────────────────────

    Envelope handleCreate(Envelope in, String sessionId,
                          Session s, String wallId, Map<String, Object> payload) {
        ParsedRule parsed = parseIncomingRule(payload, wallId);
        if (parsed.error() != null) {
            logParseFailure("script.create", parsed);
            return Envelope.error(in.id(), "INVALID_PAYLOAD", parsed.error());
        }
        Optional<String> invalid = ScriptRuleValidator.validate(parsed.rule());
        if (invalid.isPresent()) {
            return Envelope.error(in.id(), "SCRIPT_INVALID", invalid.get());
        }
        Set<String> facets = ScriptPermissions.requiredFacets(parsed.rule());
        Envelope facetDenied = checkFacets(in, sessionId, s, "script.create", facets);
        if (facetDenied != null) return facetDenied;

        ScriptRule created;
        try {
            created = store.create(wallId, parsed.rule());
        } catch (ScriptStore.QuotaExceededException qe) {
            return Envelope.error(in.id(), "SCRIPT_QUOTA_EXCEEDED", qe.getMessage());
        }

        Map<String, Object> ruleMap = ruleToMap(created);
        pushAdd(sessionId, s, created.id(), ruleMap);
        recordAudit("SCRIPT_CREATE", sessionId, s, wallId, created, facets);
        return Envelope.of("ack", in.id(), Map.of("rule", ruleMap));
    }

    Envelope handleUpdate(Envelope in, String sessionId,
                          Session s, String wallId, Map<String, Object> payload) {
        String ruleId = stringOrNull(payload.get("ruleId"));
        if (ruleId == null || ruleId.isEmpty()) {
            return Envelope.error(in.id(), "INVALID_PAYLOAD", "ruleId required");
        }
        // 防跨墙改：ruleId 必须属于本 session 的 wall（store.update 仅按反查索引定位，
        // 不带 wall 校验——这里先 find 把"别人墙的 ruleId"挡掉）
        if (store.find(wallId, ruleId).isEmpty()) {
            return Envelope.error(in.id(), "SCRIPT_NOT_FOUND",
                    "script rule not found: " + ruleId);
        }
        ParsedRule parsed = parseIncomingRule(payload, wallId);
        if (parsed.error() != null) {
            logParseFailure("script.update", parsed);
            return Envelope.error(in.id(), "INVALID_PAYLOAD", parsed.error());
        }
        Optional<String> invalid = ScriptRuleValidator.validate(parsed.rule());
        if (invalid.isPresent()) {
            return Envelope.error(in.id(), "SCRIPT_INVALID", invalid.get());
        }
        Set<String> facets = ScriptPermissions.requiredFacets(parsed.rule());
        Envelope facetDenied = checkFacets(in, sessionId, s, "script.update", facets);
        if (facetDenied != null) return facetDenied;

        ScriptRule updated;
        try {
            updated = store.update(ruleId, parsed.rule());
        } catch (ScriptStore.NotFoundException nfe) {
            return Envelope.error(in.id(), "SCRIPT_NOT_FOUND", nfe.getMessage());
        }

        Map<String, Object> ruleMap = ruleToMap(updated);
        pushAdd(sessionId, s, updated.id(), ruleMap);
        recordAudit("SCRIPT_UPDATE", sessionId, s, wallId, updated, facets);
        return Envelope.of("ack", in.id(), Map.of("rule", ruleMap));
    }

    Envelope handleDelete(Envelope in, String sessionId,
                          Session s, String wallId, Map<String, Object> payload) {
        String ruleId = stringOrNull(payload.get("ruleId"));
        if (ruleId == null || ruleId.isEmpty()) {
            return Envelope.error(in.id(), "INVALID_PAYLOAD", "ruleId required");
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
        return Envelope.of("ack", in.id(), ack);
    }

    Envelope handleEnable(Envelope in, String sessionId,
                          Session s, String wallId, Map<String, Object> payload) {
        String ruleId = stringOrNull(payload.get("ruleId"));
        if (ruleId == null || ruleId.isEmpty()) {
            return Envelope.error(in.id(), "INVALID_PAYLOAD", "ruleId required");
        }
        Object enabledRaw = payload.get("enabled");
        if (!(enabledRaw instanceof Boolean enabled)) {
            return Envelope.error(in.id(), "INVALID_PAYLOAD", "enabled (boolean) required");
        }

        ScriptRule flipped;
        try {
            flipped = store.setEnabled(wallId, ruleId, enabled);
        } catch (ScriptStore.NotFoundException nfe) {
            return Envelope.error(in.id(), "SCRIPT_NOT_FOUND", nfe.getMessage());
        }

        Map<String, Object> ruleMap = ruleToMap(flipped);
        pushAdd(sessionId, s, flipped.id(), ruleMap);
        recordAudit("SCRIPT_ENABLE", sessionId, s, wallId, flipped, Set.of());
        return Envelope.of("ack", in.id(), Map.of("rule", ruleMap));
    }

    Envelope handleTest(Envelope in, String sessionId, Session s,
                        String wallId, Map<String, Object> payload) {
        String ruleId = stringOrNull(payload.get("ruleId"));
        if (ruleId == null || ruleId.isEmpty()) {
            return Envelope.error(in.id(), "INVALID_PAYLOAD", "ruleId required");
        }
        ScriptTestSeam seam = this.testSeam;
        if (seam == null) {
            // P1：引擎未落地，固定拒——P2 接 ScriptRunner 后注入 seam
            return Envelope.error(in.id(), "SCRIPT_ENGINE_UNAVAILABLE",
                    "script engine lands in 0.7.0-P2");
        }
        // 批次3 #6：seam 调用前守卫 ruleId 存在性（含跨墙——find 按本 wall 查），
        // 不把"不存在的 ruleId"透传给引擎
        Optional<ScriptRule> rule = store.find(wallId, ruleId);
        if (rule.isEmpty()) {
            return Envelope.error(in.id(), "SCRIPT_NOT_FOUND",
                    "script rule not found: " + ruleId);
        }
        // 契约 scripting.md §4.1：试跑即真实执行（D5），同样过 sound/command 面权限
        Set<String> facets = ScriptPermissions.requiredFacets(rule.get());
        Envelope facetDenied = checkFacets(in, sessionId, s, "script.test", facets);
        if (facetDenied != null) return facetDenied;

        Map<String, Object> trace = seam.run(wallId, ruleId);
        // 契约 scripting.md §5.3：试跑真实执行须留痕（audit 标 TEST 与常规触发区分）
        recordAudit("SCRIPT_TEST", sessionId, s, wallId, rule.get(), facets);
        return Envelope.of("ack", in.id(), trace);
    }

    // ──────────────────────────────────────────────────────────
    //  payload → ScriptRule 解析（包级静态，单测直接打）
    // ──────────────────────────────────────────────────────────

    /**
     * 把 {@code payload.rule} 解析为 {@link ScriptRule}：
     * <ul>
     *   <li>id / wallId 服务端权威——客户端给了也覆写（id 占位，store.create 再生成；
     *       wallId = session 的 wall）</li>
     *   <li>enabled 缺省 true；存在但非 Boolean（如字符串 {@code "false"}）→ 拒
     *       （批次3 #7：不静默改写成 true，防类型混淆）；blockLayout 缺省 {@code "{}"}</li>
     *   <li>trigger / actions 经各自多态 deserializer；非法 type / 非数组等 →
     *       {@code ParsedRule.error}（INVALID_PAYLOAD message）</li>
     * </ul>
     */
    static ParsedRule parseIncomingRule(Map<String, Object> payload, String wallId) {
        Object raw = payload.get("rule");
        if (!(raw instanceof Map<?, ?> ruleRaw)) {
            return new ParsedRule(null, "rule (object) required", null);
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
        Object enabledRaw = m.get("enabled");
        if (enabledRaw == null) {
            m.put("enabled", Boolean.TRUE);
        } else if (!(enabledRaw instanceof Boolean)) {
            // 批次3 #7：enabled 存在但类型错（"false" / 0 / ...）→ 真拒，不默认成 true
            return new ParsedRule(null, "enabled must be a boolean", null);
        }
        if (m.get("blockLayout") == null) {
            m.put("blockLayout", "{}");
        }
        try {
            return new ParsedRule(MAPPER.convertValue(m, ScriptRule.class), null, null);
        } catch (IllegalArgumentException iae) {
            return new ParsedRule(null, "rule malformed: " + rootMessage(iae), iae);
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
     * 返回 null = 放行；非 null = 直接回给 client 的错误帧。
     *
     * <p>批次3 #1：与 alias / 模板的 own 兜底<b>等价语义</b>——default-true 节点只对
     * 无法解析的离线玩家（{@code online=false}，含主线程超时 / 解析异常）放行；
     * <b>在线但被服主显式收回节点的必须真拒</b>（PERMISSION_DENIED + audit，照
     * {@link #checkFacets} 的 audit 形态）。旧实现无条件 {@code granted=true}
     * 是 fail-open，服主收回 {@code canvas.script.edit} 形同虚设。</p>
     */
    private Envelope checkBasePermission(Envelope in, String sessionId, Session s) {
        UUID callerUuid = s.playerUuid();
        moe.hikari.canvas.storage.WallRepo.Wall wall =
                wallRepo == null ? null : wallRepo.loadById(s.wallId()).orElse(null);
        if (wall == null) {
            return Envelope.error(in.id(), "WALL_NOT_FOUND", "wall not found");
        }
        MainThreadPerms.Resolved resolved = MainThreadPerms.resolve(plugin, callerUuid,
                ScriptPermissions.NODE_EDIT);
        if (resolved.online() && !resolved.granted(0)) {
            // 在线且显式无节点 → 真拒（default=true 只兜 offline 不兜显式收回）
            if (auditLog != null) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("operation", in.op());
                details.put("required", ScriptPermissions.NODE_EDIT);
                details.put("wall_id", s.wallId());
                auditLog.record("PERMISSION_DENIED",
                        callerUuid == null ? null : callerUuid.toString(),
                        s.playerName(), sessionId, null, details);
            }
            return Envelope.error(in.id(), "PERMISSION_DENIED",
                    "missing permission: " + ScriptPermissions.NODE_EDIT);
        }
        // offline（online=false：离线 / 超时 / 解析失败）→ default-true 兜底放行
        return null;
    }

    /**
     * 面权限逐一查，<b>无兜底</b>（被服主收回必须真拒）。任一缺 →
     * {@code PERMISSION_DENIED}（message 含缺的节点）+ audit。
     * 返回 null = 全通过；非 null = 错误帧。
     */
    private Envelope checkFacets(Envelope in, String sessionId,
                                 Session s, String op, Set<String> facets) {
        if (facets.isEmpty()) return null;
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
            return Envelope.error(in.id(), "PERMISSION_DENIED",
                    "missing permission: " + missing);
        }
        return null;
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

    /** 批次3 #5：解析失败时完整异常进 server 日志（外发 message 已脱敏为首行）。 */
    private void logParseFailure(String op, ParsedRule parsed) {
        if (log != null && parsed.cause() != null) {
            log.log(java.util.logging.Level.FINE,
                    "script rule parse failed (" + op + ")", parsed.cause());
        }
    }

    /**
     * 取异常链最深 message（Jackson convertValue 包两层 IllegalArgumentException）。
     * 批次3 #5 脱敏：只留首行（自定义 deserializer 的可读文案在首行，后续行是
     * Jackson 引用链 / 内部路径，不外发）；完整异常由 {@link #logParseFailure} 进日志。
     */
    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        if (msg == null) msg = "unparseable rule";
        // 截到第一个换行符：首行即可读文案，换行后是 Jackson 内部细节
        int nl = msg.indexOf('\n');
        if (nl >= 0) msg = msg.substring(0, nl);
        // 再截断防超长刷屏
        return msg.length() > 300 ? msg.substring(0, 300) : msg;
    }
}
