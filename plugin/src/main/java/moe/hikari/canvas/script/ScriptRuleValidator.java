package moe.hikari.canvas.script;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link ScriptRule} 结构校验（纯静态、零 Bukkit / 零 DB）。
 * 契约 {@code docs/scripting.md §2.1}。
 *
 * <p>{@link #validate(ScriptRule)} 返回 {@code empty} = 合法；{@code present} =
 * 人读错误信息（后续 dispatcher 塞进 {@code SCRIPT_INVALID} 错误码）。
 * 一次只报第一个错误（fail-fast），与项目其他 validator 一致。</p>
 *
 * <p>注意：{@link Action.If#condition()} 本期只查非空 + 长度上限，
 * 表达式语法解析在 P2 接（届时复用 template.expr）。</p>
 */
public final class ScriptRuleValidator {

    /** 规则名最大长度。 */
    public static final int NAME_MAX = 64;
    /** 积木总数上限（每个 Action 计 1，If 自身计 1 再加两分支递归）。 */
    public static final int MAX_TOTAL_BLOCKS = 50;
    /** if 嵌套深度上限（第 5 层拒）。 */
    public static final int MAX_IF_DEPTH = 4;
    /** Wait.ms 范围（毫秒）。 */
    public static final long WAIT_MIN = 50;
    public static final long WAIT_MAX = 5000;
    /** Timer.intervalSeconds 范围（秒）。 */
    public static final int TIMER_MIN = 1;
    public static final int TIMER_MAX = 86400;
    /** PlayerNear.rangeBlocks 范围（方块）。 */
    public static final int NEAR_MIN = 1;
    public static final int NEAR_MAX = 32;
    /** blockLayout JSON 最大长度（null 当 "{}" 合法）。 */
    public static final int BLOCK_LAYOUT_MAX = 65536;
    /** SetElementProperty.property 白名单。 */
    public static final Set<String> ELEMENT_PROPERTIES =
            Set.of("x", "y", "w", "h", "rotation", "opacity", "text", "fill");
    /** PlayTimeline.op 白名单。 */
    public static final Set<String> TIMELINE_OPS = Set.of("play", "pause", "seek");
    /** PlaySound.scope 白名单。 */
    public static final Set<String> SOUND_SCOPES = Set.of("near", "all");
    /** SetVariable.value 最大长度（空串合法、null 拒）。 */
    public static final int SET_VALUE_MAX = 4096;
    /** PlaySound.soundId 最大长度。 */
    public static final int SOUND_ID_MAX = 128;
    /** RunCommand.params 每个值的最大长度。 */
    public static final int COMMAND_PARAM_MAX = 256;
    /** Log.message 最大长度。 */
    public static final int LOG_MESSAGE_MAX = 256;
    /** If.condition 最大长度。 */
    public static final int CONDITION_MAX = 512;
    /** PlaySound.volume 范围。 */
    public static final double VOLUME_MIN = 0.0;
    public static final double VOLUME_MAX = 2.0;
    /** PlaySound.pitch 范围。 */
    public static final double PITCH_MIN = 0.5;
    public static final double PITCH_MAX = 2.0;

    private ScriptRuleValidator() {
    }

    /**
     * 校验整条规则。empty = 合法；present = 人读错误信息。
     */
    public static Optional<String> validate(ScriptRule rule) {
        if (rule == null) {
            return Optional.of("规则不能为空");
        }
        if (rule.name() == null || rule.name().isBlank()) {
            return Optional.of("规则名称不能为空");
        }
        if (rule.name().length() > NAME_MAX) {
            return Optional.of("规则名称超长（最多 " + NAME_MAX + " 字符）");
        }
        if (rule.trigger() == null) {
            return Optional.of("缺少触发器");
        }
        if (rule.actions().isEmpty()) {
            return Optional.of("动作列表不能为空");
        }
        if (rule.blockLayout() != null && rule.blockLayout().length() > BLOCK_LAYOUT_MAX) {
            return Optional.of("积木布局数据超长（最多 " + BLOCK_LAYOUT_MAX + " 字符）");
        }
        Optional<String> triggerError = validateTrigger(rule.trigger());
        if (triggerError.isPresent()) {
            return triggerError;
        }
        int total = countBlocks(rule.actions());
        if (total > MAX_TOTAL_BLOCKS) {
            return Optional.of("积木总数 " + total + " 超过上限 " + MAX_TOTAL_BLOCKS);
        }
        return validateActions(rule.actions(), 0);
    }

    /** 触发器各子类的字段范围校验。 */
    private static Optional<String> validateTrigger(Trigger trigger) {
        return switch (trigger) {
            case Trigger.VariableChange t -> blank(t.fullName())
                    ? Optional.of("变量变化触发器的变量名不能为空")
                    : Optional.empty();
            case Trigger.Timer t -> (t.intervalSeconds() < TIMER_MIN || t.intervalSeconds() > TIMER_MAX)
                    ? Optional.of("定时器间隔需在 " + TIMER_MIN + ".." + TIMER_MAX + " 秒之间")
                    : Optional.empty();
            case Trigger.PlayerNear t -> (t.rangeBlocks() < NEAR_MIN || t.rangeBlocks() > NEAR_MAX)
                    ? Optional.of("玩家靠近半径需在 " + NEAR_MIN + ".." + NEAR_MAX + " 方块之间")
                    : Optional.empty();
            case Trigger.PlayerJoin ignored -> Optional.empty();
            case Trigger.PlayerKill ignored -> Optional.empty();
            case Trigger.WallReady ignored -> Optional.empty();
        };
    }

    /** 递归积木计数：每个 Action 计 1，If 自身计 1 再加两分支。 */
    private static int countBlocks(List<Action> actions) {
        int count = 0;
        for (Action action : actions) {
            count++;
            if (action instanceof Action.If iff) {
                count += countBlocks(iff.then()) + countBlocks(iff.elseActions());
            }
        }
        return count;
    }

    /** 逐个动作校验；ifDepth = 进入本层前已有的 if 嵌套层数。 */
    private static Optional<String> validateActions(List<Action> actions, int ifDepth) {
        for (Action action : actions) {
            Optional<String> error = validateAction(action, ifDepth);
            if (error.isPresent()) {
                return error;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> validateAction(Action action, int ifDepth) {
        return switch (action) {
            case Action.SetVariable a -> {
                if (blank(a.fullName())) {
                    yield Optional.of("设置变量的变量名不能为空");
                }
                if (a.value() == null) {
                    yield Optional.of("设置变量的值不能为 null（空串合法）");
                }
                if (a.value().length() > SET_VALUE_MAX) {
                    yield Optional.of("设置变量的值超长（最多 " + SET_VALUE_MAX + " 字符）");
                }
                yield Optional.empty();
            }
            case Action.IncrementVariable a -> blank(a.fullName())
                    ? Optional.of("累加变量的变量名不能为空")
                    : Optional.empty();
            case Action.SetElementProperty a -> {
                if (blank(a.elementId())) {
                    yield Optional.of("设置元素属性缺少元素 ID");
                }
                if (a.property() == null || !ELEMENT_PROPERTIES.contains(a.property())) {
                    yield Optional.of("元素属性不在允许范围：" + a.property());
                }
                if (blank(a.value())) {
                    yield Optional.of("设置元素属性的值不能为空");
                }
                yield Optional.empty();
            }
            case Action.PlayTimeline a -> {
                if (blank(a.timelineId())) {
                    yield Optional.of("时间轴控制缺少时间轴 ID");
                }
                if (a.op() == null || !TIMELINE_OPS.contains(a.op())) {
                    yield Optional.of("时间轴操作不在允许范围：" + a.op());
                }
                if ("seek".equals(a.op()) && a.seekMs() == null) {
                    yield Optional.of("seek 操作必须提供 seekMs");
                }
                if (a.seekMs() != null && a.seekMs() < 0) {
                    yield Optional.of("seekMs 不能为负数");
                }
                yield Optional.empty();
            }
            case Action.PlaySound a -> {
                if (blank(a.soundId()) || a.soundId().length() > SOUND_ID_MAX) {
                    yield Optional.of("声音 ID 不能为空且最多 " + SOUND_ID_MAX + " 字符");
                }
                if (a.volume() < VOLUME_MIN || a.volume() > VOLUME_MAX) {
                    yield Optional.of("音量需在 " + VOLUME_MIN + ".." + VOLUME_MAX + " 之间");
                }
                if (a.pitch() < PITCH_MIN || a.pitch() > PITCH_MAX) {
                    yield Optional.of("音调需在 " + PITCH_MIN + ".." + PITCH_MAX + " 之间");
                }
                if (a.scope() == null || !SOUND_SCOPES.contains(a.scope())) {
                    yield Optional.of("声音范围不在允许范围：" + a.scope());
                }
                yield Optional.empty();
            }
            case Action.Wait a -> (a.ms() < WAIT_MIN || a.ms() > WAIT_MAX)
                    ? Optional.of("等待时长需在 " + WAIT_MIN + ".." + WAIT_MAX + " 毫秒之间")
                    : Optional.empty();
            case Action.RunCommand a -> {
                if (blank(a.templateId())) {
                    yield Optional.of("执行命令缺少模板 ID");
                }
                for (Map.Entry<String, String> e : a.params().entrySet()) {
                    if (e.getValue() == null || e.getValue().length() > COMMAND_PARAM_MAX) {
                        yield Optional.of("命令参数 '" + e.getKey() + "' 超长（最多 "
                                + COMMAND_PARAM_MAX + " 字符）");
                    }
                }
                yield Optional.empty();
            }
            case Action.Log a -> {
                if (a.message() == null) {
                    yield Optional.of("日志内容不能为 null");
                }
                if (a.message().length() > LOG_MESSAGE_MAX) {
                    yield Optional.of("日志内容超长（最多 " + LOG_MESSAGE_MAX + " 字符）");
                }
                yield Optional.empty();
            }
            case Action.If a -> {
                int depth = ifDepth + 1;
                if (depth > MAX_IF_DEPTH) {
                    yield Optional.of("if 嵌套超过 " + MAX_IF_DEPTH + " 层（depth=" + depth + "）");
                }
                if (blank(a.condition())) {
                    yield Optional.of("if 条件不能为空");
                }
                if (a.condition().length() > CONDITION_MAX) {
                    yield Optional.of("if 条件超长（最多 " + CONDITION_MAX + " 字符）");
                }
                Optional<String> thenError = validateActions(a.then(), depth);
                if (thenError.isPresent()) {
                    yield thenError;
                }
                yield validateActions(a.elseActions(), depth);
            }
        };
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
