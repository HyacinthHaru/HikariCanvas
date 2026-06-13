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
    /** 0.7.1 SendMessage.text 最大长度。 */
    public static final int MESSAGE_MAX = 256;
    /** 0.7.1 SetElementProperties.patch 最大键数。 */
    public static final int PATCH_MAX_KEYS = 8;
    /** 0.7.1 SetElementProperties.kind 最大长度。 */
    public static final int KIND_MAX = 32;
    /** 0.7.1 SendMessage.channel 白名单。 */
    public static final Set<String> MESSAGE_CHANNELS = Set.of("chat", "actionbar", "title");
    /** 0.7.1 ScaleVariable.op 白名单。 */
    public static final Set<String> SCALE_OPS = Set.of("multiply", "divide");
    /** 0.7.1 Repeat.count 范围（次）。 */
    public static final int REPEAT_MIN = 1;
    public static final int REPEAT_MAX = 100;
    /** 0.7.1-P5 PlayParticle.count 范围（个）。 */
    public static final int PARTICLE_COUNT_MIN = 1;
    public static final int PARTICLE_COUNT_MAX = 1000;
    /** 0.7.1-P5 WaitUntil.timeoutMs 范围（毫秒）。 */
    public static final long WAIT_UNTIL_TIMEOUT_MIN = 50L;
    public static final long WAIT_UNTIL_TIMEOUT_MAX = 60_000L;
    /** 0.7.2-P2 CloneElement.offsetX/Y 绝对值上限（合理偏移，方块/像素同量级）。 */
    public static final int ELEMENT_OFFSET_MAX = 4096;
    /** 0.7.1-P5 PlayParticle.particle 白名单（14 个，双端对齐）。 */
    public static final Set<String> PARTICLE_WHITELIST = Set.of(
            "minecraft:flame", "minecraft:smoke", "minecraft:heart", "minecraft:happy_villager",
            "minecraft:crit", "minecraft:enchant", "minecraft:portal", "minecraft:firework",
            "minecraft:note", "minecraft:cloud", "minecraft:lava", "minecraft:dripping_water",
            "minecraft:end_rod", "minecraft:totem_of_undying");

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
            // 0.7.1：3 个新触发器。playerLeaveRange 复用 NEAR_MIN..NEAR_MAX（与 playerNear
            // 同半径语义）；rightClickWall / playerQuit 无字段。
            case Trigger.PlayerLeaveRange t -> (t.rangeBlocks() < NEAR_MIN || t.rangeBlocks() > NEAR_MAX)
                    ? Optional.of("玩家离开半径需在 " + NEAR_MIN + ".." + NEAR_MAX + " 方块之间")
                    : Optional.empty();
            case Trigger.RightClickWall ignored -> Optional.empty();
            case Trigger.PlayerQuit ignored -> Optional.empty();
        };
    }

    /**
     * 递归积木计数：每个 Action 计 1，If 自身计 1 再加两分支，Repeat 自身计 1 再加 body
     * （0.7.1：<b>不乘 count</b>——这是积木<i>树节点数</i>硬限 {@value #MAX_TOTAL_BLOCKS}，
     * 非展开后的动作数；展开数由运行时 Budget 熔断，见 scripting-0.7.1.md §9）。
     */
    private static int countBlocks(List<Action> actions) {
        int count = 0;
        for (Action action : actions) {
            count++;
            if (action instanceof Action.If iff) {
                count += countBlocks(iff.then()) + countBlocks(iff.elseActions());
            } else if (action instanceof Action.Repeat rep) {
                count += countBlocks(rep.body());
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
            case Action.IncrementVariable a -> {
                if (blank(a.fullName())) {
                    yield Optional.of("累加变量的变量名不能为空");
                }
                if (!Double.isFinite(a.delta())) {
                    yield Optional.of("累加步长必须是有限数值");
                }
                yield Optional.empty();
            }
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
                // 取反区间写法：NaN 任何比较都为 false，连带被拒（finite 纪律）
                if (!(a.volume() >= VOLUME_MIN && a.volume() <= VOLUME_MAX)) {
                    yield Optional.of("音量需在 " + VOLUME_MIN + ".." + VOLUME_MAX + " 之间");
                }
                if (!(a.pitch() >= PITCH_MIN && a.pitch() <= PITCH_MAX)) {
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
                    // Map.copyOf 已保证 value 非 null，这里只查长度
                    if (e.getValue().length() > COMMAND_PARAM_MAX) {
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
            // 0.7.1：6 个新 Action 子类
            case Action.SetElementProperties a -> {
                if (blank(a.elementId())) {
                    yield Optional.of("设置元素属性缺少元素 ID");
                }
                if (a.patch().isEmpty()) {
                    yield Optional.of("批量设属性的 patch 不能为空");
                }
                if (a.patch().size() > PATCH_MAX_KEYS) {
                    yield Optional.of("patch 属性数超过 " + PATCH_MAX_KEYS);
                }
                if (a.kind() != null && a.kind().length() > KIND_MAX) {
                    yield Optional.of("kind 超长");
                }
                for (Map.Entry<String, String> e : a.patch().entrySet()) {
                    if (!ELEMENT_PROPERTIES.contains(e.getKey())) {
                        yield Optional.of("元素属性不在允许范围：" + e.getKey());
                    }
                    // text 空串是合法内容（ElementPropertyApplier.buildPatch 接受空文字）；
                    // 其余键（x/y/w/h/rotation/opacity/fill）空串会静默变 0 或 hex 失败，仍查空
                    if (!"text".equals(e.getKey()) && blank(e.getValue())) {
                        yield Optional.of("属性 " + e.getKey() + " 的值不能为空");
                    }
                }
                yield Optional.empty();
            }
            case Action.NudgeElement a -> {
                if (blank(a.elementId())) {
                    yield Optional.of("相对移动缺少元素 ID");
                }
                if (!Double.isFinite(a.dx()) || !Double.isFinite(a.dy())) {
                    yield Optional.of("相对移动的 dx/dy 必须是有限数值");
                }
                yield Optional.empty();
            }
            case Action.SendMessage a -> {
                if (a.text() == null) {
                    yield Optional.of("发消息内容不能为 null");
                }
                if (a.text().length() > MESSAGE_MAX) {
                    yield Optional.of("发消息内容超长（最多 " + MESSAGE_MAX + "）");
                }
                if (a.channel() == null || !MESSAGE_CHANNELS.contains(a.channel())) {
                    yield Optional.of("消息渠道不在允许范围：" + a.channel());
                }
                yield Optional.empty();
            }
            case Action.SetRandomVariable a -> {
                if (blank(a.fullName())) {
                    yield Optional.of("随机数变量名不能为空");
                }
                if (!Double.isFinite(a.min()) || !Double.isFinite(a.max())) {
                    yield Optional.of("随机区间必须是有限数值");
                }
                if (a.min() > a.max()) {
                    yield Optional.of("随机区间 min 不能大于 max");
                }
                yield Optional.empty();
            }
            case Action.ScaleVariable a -> {
                if (blank(a.fullName())) {
                    yield Optional.of("乘除变量名不能为空");
                }
                if (a.op() == null || !SCALE_OPS.contains(a.op())) {
                    yield Optional.of("运算不在允许范围：" + a.op());
                }
                if (!Double.isFinite(a.factor())) {
                    yield Optional.of("乘除系数必须是有限数值");
                }
                if ("divide".equals(a.op()) && a.factor() == 0.0) {
                    yield Optional.of("除数不能为 0");
                }
                yield Optional.empty();
            }
            case Action.PlayTimelineAwait a -> blank(a.timelineId())
                    ? Optional.of("播时间轴缺少时间轴 ID")
                    : Optional.empty();
            case Action.Repeat a -> {
                if (a.count() < REPEAT_MIN || a.count() > REPEAT_MAX) {
                    yield Optional.of("重复次数需在 " + REPEAT_MIN + ".." + REPEAT_MAX + " 之间");
                }
                if (a.body().isEmpty()) {
                    yield Optional.of("重复循环体不能为空");
                }
                // body 递归（ifDepth 不变——repeat 不增 if 嵌套深度）
                yield validateActions(a.body(), ifDepth);
            }
            // 0.7.1-P5：停止 / 粒子 / 等待直到
            case Action.StopScript ignored -> Optional.empty();
            case Action.PlayParticle a -> {
                if (a.particle() == null || !PARTICLE_WHITELIST.contains(a.particle())) {
                    yield Optional.of("粒子种类不在允许范围：" + a.particle());
                }
                if (!(a.count() >= PARTICLE_COUNT_MIN && a.count() <= PARTICLE_COUNT_MAX)) {
                    yield Optional.of("粒子数量需在 " + PARTICLE_COUNT_MIN + ".." + PARTICLE_COUNT_MAX + " 之间");
                }
                if (!(Double.isFinite(a.offsetX()) && Double.isFinite(a.offsetY())
                        && Double.isFinite(a.offsetZ()))) {
                    yield Optional.of("粒子偏移必须是有限数值");
                }
                yield Optional.empty();
            }
            case Action.WaitUntil a -> {
                if (blank(a.condition()) || a.condition().length() > CONDITION_MAX) {
                    yield Optional.of("等待条件不能为空且最多 " + CONDITION_MAX + " 字符");
                }
                if (!(a.timeoutMs() >= WAIT_UNTIL_TIMEOUT_MIN
                        && a.timeoutMs() <= WAIT_UNTIL_TIMEOUT_MAX)) {
                    yield Optional.of("超时时长需在 " + WAIT_UNTIL_TIMEOUT_MIN + ".."
                            + WAIT_UNTIL_TIMEOUT_MAX + " 毫秒之间");
                }
                yield Optional.empty();
            }
            // 0.7.2-P2：copy / append / clone / delete
            case Action.CopyVariable a -> (blank(a.target()) || blank(a.source()))
                    ? Optional.of("变量复制的目标 / 来源不能为空")
                    : Optional.empty();
            case Action.AppendVariable a -> {
                if (blank(a.fullName())) {
                    yield Optional.of("文本拼接的目标变量不能为空");
                }
                // text 复用 SetVariable 值长度上限（= VariableStore.MAX_VALUE_LENGTH，4096）
                if (a.text() != null && a.text().length() > SET_VALUE_MAX) {
                    yield Optional.of("拼接文本超长（最多 " + SET_VALUE_MAX + " 字符）");
                }
                yield Optional.empty();
            }
            case Action.CloneElement a -> {
                if (blank(a.elementId())) {
                    yield Optional.of("克隆元素的目标不能为空");
                }
                if (!(Math.abs(a.offsetX()) <= ELEMENT_OFFSET_MAX
                        && Math.abs(a.offsetY()) <= ELEMENT_OFFSET_MAX)) {
                    yield Optional.of("克隆偏移超范围（绝对值最多 " + ELEMENT_OFFSET_MAX + "）");
                }
                yield Optional.empty();
            }
            case Action.DeleteElement a -> blank(a.elementId())
                    ? Optional.of("删除元素的目标不能为空")
                    : Optional.empty();
        };
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
