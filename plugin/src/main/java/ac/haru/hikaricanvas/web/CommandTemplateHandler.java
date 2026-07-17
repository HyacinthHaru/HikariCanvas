package ac.haru.hikaricanvas.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import ac.haru.hikaricanvas.HikariCanvasConfig.CommandTemplate;
import ac.haru.hikaricanvas.HikariCanvasConfig.ParamSpec;
import ac.haru.hikaricanvas.session.SessionManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code GET /api/script/command-templates} 端点 handler。
 *
 * <p>给积木编辑器的 {@code runCommand} 积木列出服主在 {@code config.yml} 配的命令模板，
 * 让玩家从下拉里选一个模板 + 填参数，而不是手打命令全文。</p>
 *
 * <h2>安全核心：绝不泄 command 原文</h2>
 *
 * <p>{@link CommandTemplate#command()} 可能含 op 命令 / 敏感参数（服主刻意收口的 runCommand
 * 白名单）；前端只需要知道"有哪些模板 + 每个模板要填什么参数"，<b>不需要</b>也<b>不应</b>看到
 * 命令字符串本身。本 handler 只输出 {@code id + params 列表（name/type/maxLength）}，command
 * 字段永不进 response。详见 {@code docs/scripting.md §5.2}。</p>
 *
 * <h2>路由约定</h2>
 *
 * <pre>{@code GET /api/script/command-templates?sessionId=<id>}</pre>
 *
 * <ul>
 *   <li>{@code sessionId}（必需）：通过 WS auth 拿到，参考 {@code docs/protocol.md §3}；
 *       校验失败 401。鉴权方式同
 *       {@link ac.haru.hikaricanvas.image.UploadHandler#handleDownload(Context)} 与
 *       {@link VariableMetadataHandler#handle(Context)}（{@code sessionManager.byId} 非空判定）。
 *       为兼容写作 {@code ?session=<sid>} 的调用方，也接受 {@code session} 作为别名
 *       （先读 {@code sessionId}，缺则读 {@code session}）。</li>
 * </ul>
 *
 * <h2>响应</h2>
 *
 * <pre>{@code
 * {
 *   "templates": [
 *     {"id":"announce","params":[{"name":"msg","type":"text","maxLength":64}]}
 *   ]
 * }
 * }</pre>
 *
 * <p>无模板 → {@code {"templates":[]}}。</p>
 *
 * <h2>顺序</h2>
 *
 * <p>{@link ac.haru.hikaricanvas.HikariCanvasConfig.ScriptsConfig#commandTemplates()} 与
 * {@link CommandTemplate#params()} 均经 {@code Map.copyOf} 包装——<b>不保证</b>声明序。
 * 故本 handler 对 template id 与 param name 一律按<b>字母序</b>稳定排序输出，避免每次响应
 * 顺序抖动（也利于前端做稳定渲染 / diff）。</p>
 *
 * <h2>热更</h2>
 *
 * <p>模板表经 {@link Supplier} 惰性读 volatile config 引用（生产传
 * {@code () -> plugin.config().scriptsConfig.commandTemplates()}）；{@code /canvas reload} 后
 * 下一次请求即返新模板，无需重新接线——与 {@code ActionExecutor} 读模板的范式一致。</p>
 *
 * <h2>注册</h2>
 *
 * <p>本类不主动注册路由：由 {@link WebServer} 装配时一行接入，保持 god-class 拆分纪律。</p>
 */
public final class CommandTemplateHandler {

    private static final Logger log = Logger.getLogger(CommandTemplateHandler.class.getName());

    /** 鉴权检查：sessionId 是否对应活 session。生产传 {@code sessionManager::byId 非空判定}；测试注入 fake。 */
    private final Predicate<String> sessionAuthCheck;
    /**
     * 命令模板表（templateId → 模板）的惰性供给。每次请求现读，故 {@code /canvas reload}
     * 后立即生效（生产传读 volatile config 的 lambda）。可能返 null —— 内部按空表处理。
     */
    private final Supplier<Map<String, CommandTemplate>> templatesSupplier;
    private final ObjectMapper jackson;

    /**
     * 生产构造：通过 {@link SessionManager#byId(String)} 非空判定鉴权。
     *
     * @param templatesSupplier 惰性读 volatile config 的命令模板表（热更友好）
     */
    public CommandTemplateHandler(SessionManager sessionManager,
                                  Supplier<Map<String, CommandTemplate>> templatesSupplier,
                                  ObjectMapper jackson) {
        this(sid -> Objects.requireNonNull(sessionManager, "sessionManager").byId(sid) != null,
                templatesSupplier, jackson);
    }

    /**
     * 测试构造：注入自定义鉴权 predicate + 模板供给。
     */
    CommandTemplateHandler(Predicate<String> sessionAuthCheck,
                           Supplier<Map<String, CommandTemplate>> templatesSupplier,
                           ObjectMapper jackson) {
        this.sessionAuthCheck = Objects.requireNonNull(sessionAuthCheck, "sessionAuthCheck");
        this.templatesSupplier = Objects.requireNonNull(templatesSupplier, "templatesSupplier");
        this.jackson = Objects.requireNonNull(jackson, "jackson");
    }

    /**
     * 入口：处理 {@code GET /api/script/command-templates}。
     *
     * <p>失败：</p>
     * <ul>
     *   <li>401 {@code {"error":"UNAUTHORIZED"}}（sessionId 缺失 / 不存在 / 已过期）—— 照
     *       {@link VariableMetadataHandler} 的 401 形态</li>
     *   <li>500 {@code {"error":"INTERNAL"}}（序列化失败等）</li>
     * </ul>
     */
    public void handle(Context ctx) {
        // 先读 sessionId（codebase 约定名）；缺则读 session（别名）
        String sessionId = ctx.queryParam("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = ctx.queryParam("session");
        }
        if (sessionId == null || sessionId.isBlank() || !sessionAuthCheck.test(sessionId)) {
            ctx.status(401).contentType("application/json")
                    .result("{\"error\":\"UNAUTHORIZED\"}");
            return;
        }
        try {
            String json = buildJson();
            ctx.contentType("application/json")
                    // 模板表跟随 config，reload 后会变；短缓存即可（与 /api/font/list 60s 同级）
                    .header("Cache-Control", "max-age=60")
                    .result(json);
        } catch (Exception e) {
            log.log(Level.WARNING,
                    "command-templates serialization failed: " + e.getMessage(), e);
            ctx.status(500).contentType("application/json")
                    .result("{\"error\":\"INTERNAL\"}");
        }
    }

    /**
     * 构造响应 JSON。<b>只</b>输出 {@code id + params（name/type/maxLength）}；
     * {@link CommandTemplate#command()} 永不进结果。template id 与 param name 按字母序稳定排序。
     *
     * <p>包级可见，供单测直接断言（不经 HTTP）。</p>
     */
    String buildJson() throws Exception {
        Map<String, CommandTemplate> templates = templatesSupplier.get();
        List<Map<String, Object>> out = new ArrayList<>();
        if (templates != null && !templates.isEmpty()) {
            // 字母序稳定输出（Map.copyOf 不保证声明序）
            for (Map.Entry<String, CommandTemplate> e : new TreeMap<>(templates).entrySet()) {
                String id = e.getKey();
                CommandTemplate tpl = e.getValue();
                if (id == null || tpl == null) continue;
                Map<String, Object> tplOut = new LinkedHashMap<>();
                tplOut.put("id", id);
                tplOut.put("params", buildParams(tpl));
                out.add(tplOut);
            }
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("templates", out);
        return jackson.writeValueAsString(root);
    }

    /** 单模板的 params 列表（name/type/maxLength），按 param name 字母序。 */
    private static List<Map<String, Object>> buildParams(CommandTemplate tpl) {
        List<Map<String, Object>> params = new ArrayList<>();
        Map<String, ParamSpec> specs = tpl.params();
        if (specs == null || specs.isEmpty()) return params;
        for (Map.Entry<String, ParamSpec> pe : new TreeMap<>(specs).entrySet()) {
            String name = pe.getKey();
            ParamSpec spec = pe.getValue();
            if (name == null || spec == null) continue;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", name);
            // type 已在 config load 期归一为 "text" / "online-player"
            p.put("type", spec.type() == null ? ParamSpec.TYPE_TEXT : spec.type());
            p.put("maxLength", spec.maxLength());
            params.add(p);
        }
        return params;
    }
}
