package ac.haru.hikaricanvas.script.engine;

import ac.haru.hikaricanvas.HikariCanvasConfig.CommandTemplate;
import ac.haru.hikaricanvas.HikariCanvasConfig.ParamSpec;

import java.util.Collection;
import java.util.Map;

/**
 * runCommand 命令模板渲染器（docs/scripting.md §5.2）。
 *
 * <p><b>纯函数，零 Bukkit</b>——模板表 / 在线玩家名列表全部由调用方传入；
 * 任意线程可调，全场景可单测。{@link ActionExecutor#execute} 是唯一生产消费点。</p>
 *
 * <h2>转义 / 注入防御规则（逐条）</h2>
 * <ul>
 *   <li>模板查无（含 templateId 空 / 模板表空）→ {@link Result.Blocked}
 *       （blocked step 形态——"功能没配"而非"参数错"）</li>
 *   <li>模板声明的参数缺失 → {@link Result.Error}</li>
 *   <li>替换值先<b>剥换行（\n / \r）与 {@code §}</b>（颜色码 / 多行注入面）</li>
 *   <li>text 参数（默认）：剥后仍含 {@code @} → 整体拒（error；杜绝 {@code @a/@e}
 *       选择器注入）</li>
 *   <li>{@code type: online-player} 参数：放行 {@code @} 检查，但值必须<b>精确命中</b>
 *       传入的在线玩家名（大小写敏感——MC 玩家名大小写固定）</li>
 *   <li>剥后长度超 {@code max-length}（默 64）→ error</li>
 *   <li>只有模板 params 里<b>声明过</b>的参数会被替换；规则方多传的参数静默忽略
 *       （进不了命令文本，无注入面）；模板 command 里没有对应声明的 {@code {x}}
 *       占位符原样保留（服主配置笔误，进 audit 全文可见）</li>
 *   <li>渲染结果剥一个前导 {@code /}（Bukkit.dispatchCommand 不吃前导斜杠）</li>
 *   <li>参数值字面含 {@code {其他参数名}} 时，替换结果依参数迭代序而定（先替换的
 *       参数值里的占位符文本可能被后续轮替换）——两个值都已过同一套净化（剥换行 /
 *       {@code §} + {@code @} 检查 + 长度上限），不构成新的注入面，仅是文本拼接
 *       顺序差异（记账，不修）</li>
 * </ul>
 */
public final class CommandTemplateEngine {

    private CommandTemplateEngine() {
    }

    /** 渲染结果三态：ok（可执行命令全文）/ blocked（模板未配置）/ error（参数校验失败）。 */
    public sealed interface Result {
        record Ok(String command) implements Result {}

        record Blocked(String reason) implements Result {}

        record Error(String reason) implements Result {}
    }

    /**
     * 渲染模板。
     *
     * @param templateId        规则里引用的模板 id（{@code Action.RunCommand.templateId}）
     * @param params            规则作者填的参数表（可 null）
     * @param templates         config 的模板白名单（可 null = 未配置）
     * @param onlinePlayerNames 在线玩家名集合（online-player 参数校验用；可 null = 无人在线）
     */
    public static Result render(String templateId,
                                Map<String, String> params,
                                Map<String, CommandTemplate> templates,
                                Collection<String> onlinePlayerNames) {
        if (templateId == null || templateId.isBlank()
                || templates == null || !templates.containsKey(templateId)) {
            return new Result.Blocked("command template not configured: "
                    + (templateId == null || templateId.isBlank() ? "(empty)" : templateId));
        }
        CommandTemplate tpl = templates.get(templateId);
        if (tpl.command() == null || tpl.command().isBlank()) {
            // config 解析期已拦空 command；这里是防御（手工构造的模板表）
            return new Result.Error("template " + templateId + " has empty command");
        }
        Map<String, String> given = params == null ? Map.of() : params;

        String rendered = tpl.command();
        for (Map.Entry<String, ParamSpec> e : tpl.params().entrySet()) {
            String name = e.getKey();
            ParamSpec spec = e.getValue() == null ? ParamSpec.defaults() : e.getValue();
            String raw = given.get(name);
            if (raw == null) {
                return new Result.Error("missing parameter: " + name);
            }
            // 转义：剥换行 + § 颜色码
            String value = sanitize(raw);
            if (ParamSpec.TYPE_ONLINE_PLAYER.equals(spec.type())) {
                if (value.isEmpty()
                        || onlinePlayerNames == null || !onlinePlayerNames.contains(value)) {
                    return new Result.Error("parameter " + name + " must be an online player name");
                }
            } else if (value.indexOf('@') >= 0) {
                // 含 @ 整体拒（选择器注入面），除非显式 online-player
                return new Result.Error("parameter " + name + " contains @, rejected (selectors not allowed)");
            }
            int maxLen = spec.maxLength() <= 0 ? ParamSpec.DEFAULT_MAX_LENGTH : spec.maxLength();
            if (value.length() > maxLen) {
                return new Result.Error("parameter " + name + " too long (max " + maxLen + " chars)");
            }
            rendered = rendered.replace("{" + name + "}", value);
        }

        String out = rendered.trim();
        if (out.startsWith("/")) {
            out = out.substring(1).trim();
        }
        if (out.isEmpty()) {
            return new Result.Error("template " + templateId + " rendered to empty");
        }
        return new Result.Ok(out);
    }

    /** 转义：删去 \n / \r / §（不替换为空格——招牌命令场景值都是单行短串）。 */
    static String sanitize(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\n' || c == '\r' || c == '§') continue;
            sb.append(c);
        }
        return sb.toString();
    }
}
