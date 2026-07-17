package ac.haru.hikaricanvas.canvasfile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ac.haru.hikaricanvas.HikariCanvasConfig.CommandTemplate;
import ac.haru.hikaricanvas.script.Action;
import ac.haru.hikaricanvas.script.ScriptRule;
import ac.haru.hikaricanvas.script.ScriptRuleValidator;
import ac.haru.hikaricanvas.script.ScriptStore;
import ac.haru.hikaricanvas.script.engine.ConditionEvaluator;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 导入 {@code scripts.json} —— 把工程包里的脚本规则<b>重绑到目标墙</b>、
 * <b>全量重校验</b>（不信任文件内 {@code rule_json}）、最后<b>落 {@code wall_scripts}</b>。
 *
 * <p>每条规则的处理（契约 {@code docs/import-export.md §2.3} / {@code security.md §13.5}）：</p>
 * <ol>
 *   <li><b>wallId 重绑</b>：{@link ScriptStore#create} 天然忽略 incoming.id / incoming.wallId
 *       并生成全新 {@code sr-<8hex>} —— 这一步即完成「id 占位 + wallId 强制绑定到目标墙」
 *       （等价 {@code ScriptOpDispatcher.parseIncomingRule} 的服务端权威覆写，但那是 web 包
 *       package-private，本类经 store.create 达成同效）；</li>
 *   <li><b>结构校验</b>：{@link ScriptRuleValidator#validate} 非空 → 跳过 + {@code script-invalid}；</li>
 *   <li><b>条件语法预检</b>：递归对所有 {@code if/waitUntil/repeatUntil} 条件调
 *       {@link ConditionEvaluator#checkSyntax}（parse-only）→ 非空跳过 + {@code script-invalid}；</li>
 *   <li><b>命令模板检查</b>：扫所有 {@link Action.RunCommand} 的 {@code templateId}，本服模板表
 *       缺 → {@code script-command-blocked}（detail = templateId）；<b>不跳过</b>，规则照常落库
 *       （运行期 {@code CommandTemplateEngine} 会把该积木判为 Blocked）；</li>
 *   <li><b>落库</b>：{@link ScriptStore#create}；配额超限（{@link ScriptStore.QuotaExceededException}）
 *       → {@code script-quota} + <b>停止</b>处理后续规则。</li>
 * </ol>
 *
 * <p>{@code ScriptOpDispatcher} 的校验是 {@code web} 包 package-private，{@code canvasfile} 包
 * 无法复用；故本类用公开 API（多态反序列化 + {@code ScriptRuleValidator.validate} +
 * {@code ConditionEvaluator.checkSyntax} + {@code store.create}）复刻同一套语义。
 * wallId 重绑由 store.create 承担（与 dispatcher 同效）。</p>
 */
public final class ScriptImporter {

    /** ScriptRule 注解自带双向多态 (de)serializer（{@code @JsonDeserialize(using=...)}），裸 mapper 即可。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ScriptStore store;
    /** 本服命令模板表供给（惰性读 volatile config → /canvas reload 热更友好；与 WebServer 同款 supplier）。 */
    private final Supplier<Map<String, CommandTemplate>> commandTemplatesSupplier;

    public ScriptImporter(ScriptStore store,
                          Supplier<Map<String, CommandTemplate>> commandTemplatesSupplier) {
        this.store = store;
        this.commandTemplatesSupplier = commandTemplatesSupplier;
    }

    /** 供编排 / 测试取底层 store（验证落库结果）。 */
    public ScriptStore store() {
        return store;
    }

    /**
     * 解析并导入 {@code scripts.json} 的所有规则到目标墙。
     *
     * @param scriptsJson  {@code scripts.json} 原始字节（应为 {@code ScriptRule[]} 的 JSON 数组）
     * @param targetWallId 导入目标墙 id（所有规则强制重绑到此墙）
     * @return 非致命提示列表（{@code script-invalid} / {@code script-command-blocked} /
     *         {@code script-quota}）；永不为 null。整个 scripts.json 无法解析时返回单条
     *         {@code script-invalid}（不抛——脚本导入失败不应中断整次工程导入）。
     */
    public List<ImportWarning> importScripts(byte[] scriptsJson, String targetWallId) {
        List<ImportWarning> warnings = new ArrayList<>();

        JsonNode root;
        try {
            root = MAPPER.readTree(scriptsJson);
        } catch (Exception e) {
            warnings.add(new ImportWarning("script-invalid", "script data could not be parsed: " + rootMessage(e)));
            return warnings;
        }
        if (root == null || !root.isArray()) {
            warnings.add(new ImportWarning("script-invalid", "script data is not a rule array"));
            return warnings;
        }

        Map<String, CommandTemplate> templates = commandTemplatesSupplier == null
                ? Map.of()
                : commandTemplatesSupplier.get();
        if (templates == null) templates = Map.of();

        for (JsonNode ruleNode : root) {
            // a) 多态反序列化为 ScriptRule（trigger/actions 注解自带判别）；单条失败 → 跳过 + warning。
            ScriptRule rule;
            try {
                rule = MAPPER.convertValue(ruleNode, ScriptRule.class);
            } catch (RuntimeException ex) {
                warnings.add(new ImportWarning("script-invalid", "rule parse failed: " + rootMessage(ex)));
                continue;
            }
            if (rule == null) {
                warnings.add(new ImportWarning("script-invalid", "rule is empty"));
                continue;
            }

            // b) 结构校验（不信任文件内容）。validate 返 ValidationError（key + 参数）。
            //    本导入路径无编辑器 session / locale（是文件导入，非 per-player WS 请求），
            //    ImportWarning.detail 语义即"稳定的具体对象码，前端据此选大白话文案"——故 detail
            //    存 ValidationError 的稳定 key（+ 参数附注）而非渲染后语句；前端 script-invalid
            //    文案已本地化外层句子（见 web/src/i18n/messages.ts）。
            Optional<ac.haru.hikaricanvas.script.ValidationError> structErr =
                    ScriptRuleValidator.validate(rule);
            if (structErr.isPresent()) {
                warnings.add(new ImportWarning("script-invalid",
                        describeValidationError(structErr.get())));
                continue;
            }

            // c) 条件语法预检（if / waitUntil / repeatUntil，递归）。
            Optional<String> condErr = checkConditionSyntax(rule.actions());
            if (condErr.isPresent()) {
                warnings.add(new ImportWarning("script-invalid", condErr.get()));
                continue;
            }

            // d) 命令模板缺失 → script-command-blocked（不跳过，规则照常落库）。
            for (String missing : collectMissingTemplateIds(rule.actions(), templates)) {
                warnings.add(new ImportWarning("script-command-blocked", missing));
            }

            // e) 落库（wallId 重绑 + 新 ruleId 由 store.create 承担）。配额超限 → 停止后续。
            try {
                store.create(targetWallId, rule);
            } catch (ScriptStore.QuotaExceededException qe) {
                warnings.add(new ImportWarning("script-quota", qe.getMessage()));
                break;
            }
        }
        return warnings;
    }

    /**
     * 递归走动作树，对每个含 condition 的积木（{@code if} / {@code waitUntil} / {@code repeatUntil}）
     * 调 {@link ConditionEvaluator#checkSyntax}。返回首个失败信息；全通过返 empty。
     * （复刻 {@code ScriptOpDispatcher.checkConditionSyntax} 的递归覆盖。）
     */
    static Optional<String> checkConditionSyntax(List<Action> actions) {
        if (actions == null) return Optional.empty();
        for (Action a : actions) {
            switch (a) {
                case Action.If iff -> {
                    Optional<String> err = ConditionEvaluator.checkSyntax(iff.condition());
                    if (err.isPresent()) return Optional.of("invalid if condition: " + err.get());
                    Optional<String> sub = checkConditionSyntax(iff.then());
                    if (sub.isPresent()) return sub;
                    sub = checkConditionSyntax(iff.elseActions());
                    if (sub.isPresent()) return sub;
                }
                case Action.WaitUntil wu -> {
                    Optional<String> err = ConditionEvaluator.checkSyntax(wu.condition());
                    if (err.isPresent()) return Optional.of("invalid wait condition: " + err.get());
                }
                case Action.RepeatUntil ru -> {
                    Optional<String> err = ConditionEvaluator.checkSyntax(ru.condition());
                    if (err.isPresent()) return Optional.of("invalid repeat condition: " + err.get());
                    Optional<String> sub = checkConditionSyntax(ru.body());
                    if (sub.isPresent()) return sub;
                }
                case Action.Repeat rep -> {
                    Optional<String> sub = checkConditionSyntax(rep.body());
                    if (sub.isPresent()) return sub;
                }
                case Action.RandomBranch rb -> {
                    Optional<String> sub = checkConditionSyntax(rb.then());
                    if (sub.isPresent()) return sub;
                    sub = checkConditionSyntax(rb.elseActions());
                    if (sub.isPresent()) return sub;
                }
                case Action.TweenBlock tb -> {
                    Optional<String> sub = checkConditionSyntax(tb.body());
                    if (sub.isPresent()) return sub;
                }
                default -> { /* 无 condition / 无 body 的动作：无需检查 */ }
            }
        }
        return Optional.empty();
    }

    /**
     * 递归收集所有 {@link Action.RunCommand} 中本服模板表里<b>不存在</b>的 templateId（去重保序）。
     * 空 / 空白 templateId 由 {@link ScriptRuleValidator} 拦在前，这里只看「有 id 但本服没配」。
     */
    static Set<String> collectMissingTemplateIds(List<Action> actions,
                                                 Map<String, CommandTemplate> templates) {
        Set<String> missing = new LinkedHashSet<>();
        collectMissing(actions, templates, missing);
        return missing;
    }

    private static void collectMissing(List<Action> actions,
                                       Map<String, CommandTemplate> templates,
                                       Set<String> missing) {
        if (actions == null) return;
        for (Action a : actions) {
            switch (a) {
                case Action.RunCommand rc -> {
                    String id = rc.templateId();
                    if (id != null && !id.isBlank() && !templates.containsKey(id)) {
                        missing.add(id);
                    }
                }
                case Action.If iff -> {
                    collectMissing(iff.then(), templates, missing);
                    collectMissing(iff.elseActions(), templates, missing);
                }
                case Action.Repeat rep -> collectMissing(rep.body(), templates, missing);
                case Action.RepeatUntil ru -> collectMissing(ru.body(), templates, missing);
                case Action.RandomBranch rb -> {
                    collectMissing(rb.then(), templates, missing);
                    collectMissing(rb.elseActions(), templates, missing);
                }
                case Action.TweenBlock tb -> collectMissing(tb.body(), templates, missing);
                default -> { /* 非 runCommand 且无 body */ }
            }
        }
    }

    /** 取异常根因 message 首行（外发脱敏，照 dispatcher rootMessage 先例）。 */
    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        if (msg == null) return cur.getClass().getSimpleName();
        int nl = msg.indexOf('\n');
        return nl >= 0 ? msg.substring(0, nl) : msg;
    }

    /**
     * 把 {@link ac.haru.hikaricanvas.script.ValidationError} 压成稳定的 detail 字符串
     * （{@code key} 或 {@code key [k=v, ...]}），供 {@code script-invalid} 提示携带。
     * 无 locale 渲染（导入路径无 session）——前端外层句子已本地化，detail 是稳定具体对象码。
     */
    private static String describeValidationError(ac.haru.hikaricanvas.script.ValidationError ve) {
        if (ve.params().isEmpty()) return ve.key();
        StringBuilder sb = new StringBuilder(ve.key()).append(" [");
        boolean first = true;
        for (Map.Entry<String, String> e : ve.params().entrySet()) {
            if (!first) sb.append(", ");
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.append(']').toString();
    }
}
