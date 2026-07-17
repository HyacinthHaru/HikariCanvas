package moe.hikari.canvas.script;

import moe.hikari.canvas.state.EasingType;

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
 * <p>注意：{@link Action.If#condition()} 这里只查非空 + 长度上限；
 * 表达式语法由 {@code ConditionEvaluator#checkSyntax} 校验（独立条件文法，非模板表达式）。</p>
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
    /** SetElementProperty.property 白名单。
     * "color"（TextElement.color，setColor 友好积木 + 补间 color 分支用）。
     * fill 保留：rect/circle/path/shape/brush/icon 仍用 fill；setElementProperty 通用动作
     * 继续用 fill；仅 setColor 友好积木对 TextElement 用 color（TweenScheduler color 分支）。
     */
    public static final Set<String> ELEMENT_PROPERTIES =
            Set.of("x", "y", "w", "h", "rotation", "opacity", "text", "fill", "color");
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
    /** SendMessage.text 最大长度。 */
    public static final int MESSAGE_MAX = 256;
    /** SetElementProperties.patch 最大键数。 */
    public static final int PATCH_MAX_KEYS = 8;
    /** SetElementProperties.kind 最大长度。 */
    public static final int KIND_MAX = 32;
    /** SendMessage.channel 白名单。 */
    public static final Set<String> MESSAGE_CHANNELS = Set.of("chat", "actionbar", "title");
    /** SendMessage.target 白名单（trigger=触发玩家 / all=全服广播）。 */
    public static final Set<String> MESSAGE_TARGETS = Set.of("trigger", "all");
    /** ScaleVariable.op 白名单。 */
    public static final Set<String> SCALE_OPS = Set.of("multiply", "divide");
    /** Repeat.count 范围（次）。 */
    public static final int REPEAT_MIN = 1;
    public static final int REPEAT_MAX = 100;
    /** PlayParticle.count 范围（个）。 */
    public static final int PARTICLE_COUNT_MIN = 1;
    public static final int PARTICLE_COUNT_MAX = 1000;
    /** WaitUntil.timeoutMs 范围（毫秒）。 */
    public static final long WAIT_UNTIL_TIMEOUT_MIN = 50L;
    public static final long WAIT_UNTIL_TIMEOUT_MAX = 60_000L;
    /** CloneElement.offsetX/Y 绝对值上限（合理偏移，方块/像素同量级）。 */
    public static final int ELEMENT_OFFSET_MAX = 4096;
    /** TweenBlock.durationMs 上限（毫秒；1 ms 下限直接比较）。 */
    public static final long TWEEN_DURATION_MAX = 60_000L;
    /**
     * TweenBlock.body 每条 {@link Action.SetElementProperties} 的 {@code kind} 白名单
     * （补间属性动作种类；对齐前端 {@code FRIENDLY_ELEMENT_DEFS}）。
     * 含：moveTo / resize / rotateTo / setOpacity / setColor（共 5 种数值/颜色属性可补间；
     * show / hide / setText 是离散状态，不进补间范围；nudgeElement 是相对移动，单独走 NudgeElement）。
     */
    public static final Set<String> TWEENABLE_KINDS =
            Set.of("moveTo", "resize", "rotateTo", "setOpacity", "setColor");
    /** RandomBranch.probability 范围（百分比，含边界）。 */
    public static final int RANDOM_BRANCH_PROB_MIN = 0;
    public static final int RANDOM_BRANCH_PROB_MAX = 100;
    /** SetElementLayer.mode 白名单。 */
    public static final Set<String> ELEMENT_LAYER_MODES = Set.of("front", "back");
    /** RoundVariable.mode 白名单。 */
    public static final Set<String> ROUND_MODES = Set.of("round", "floor", "ceil");
    /** ShowTitle 时长上限（毫秒）。 */
    public static final int SHOW_TITLE_FADE_MAX_MS = 10_000;   // fadeIn / fadeOut ≤ 10s
    public static final int SHOW_TITLE_STAY_MAX_MS = 60_000;   // stay ≤ 60s
    /** ShowTitle.target 白名单（复用 SendMessage 的 target 集合）。 */
    public static final Set<String> TITLE_TARGETS = Set.of("trigger", "all");
    /** ShowTitle title / subtitle 最大长度（单个字段）。 */
    public static final int TITLE_TEXT_MAX = 256;

    /** PlayParticle.particle 白名单（14 个，双端对齐）。 */
    public static final Set<String> PARTICLE_WHITELIST = Set.of(
            "minecraft:flame", "minecraft:smoke", "minecraft:heart", "minecraft:happy_villager",
            "minecraft:crit", "minecraft:enchant", "minecraft:portal", "minecraft:firework",
            "minecraft:note", "minecraft:cloud", "minecraft:lava", "minecraft:dripping_water",
            "minecraft:end_rod", "minecraft:totem_of_undying");

    private ScriptRuleValidator() {
    }

    /**
     * 校验整条规则。empty = 合法；present = 结构化 {@link ValidationError}
     * （key + 命名参数，由 dispatcher 按编辑器 locale 渲染）。
     */
    public static Optional<ValidationError> validate(ScriptRule rule) {
        if (rule == null) {
            return Optional.of(ValidationError.of("ruleNull"));
        }
        if (rule.name() == null || rule.name().isBlank()) {
            return Optional.of(ValidationError.of("nameBlank"));
        }
        if (rule.name().length() > NAME_MAX) {
            return Optional.of(ValidationError.of("nameTooLong", "max", NAME_MAX));
        }
        if (rule.trigger() == null) {
            return Optional.of(ValidationError.of("triggerMissing"));
        }
        if (rule.actions().isEmpty()) {
            return Optional.of(ValidationError.of("actionsEmpty"));
        }
        if (rule.blockLayout() != null && rule.blockLayout().length() > BLOCK_LAYOUT_MAX) {
            return Optional.of(ValidationError.of("blockLayoutTooLong", "max", BLOCK_LAYOUT_MAX));
        }
        Optional<ValidationError> triggerError = validateTrigger(rule.trigger());
        if (triggerError.isPresent()) {
            return triggerError;
        }
        int total = countBlocks(rule.actions());
        if (total > MAX_TOTAL_BLOCKS) {
            return Optional.of(ValidationError.of("blocksTotalExceeded",
                    "total", total, "max", MAX_TOTAL_BLOCKS));
        }
        return validateActions(rule.actions(), 0);
    }

    /** 触发器各子类的字段范围校验。 */
    private static Optional<ValidationError> validateTrigger(Trigger trigger) {
        return switch (trigger) {
            case Trigger.VariableChange t -> blank(t.fullName())
                    ? Optional.of(ValidationError.of("triggerVarChangeNameBlank"))
                    : Optional.empty();
            case Trigger.Timer t -> (t.intervalSeconds() < TIMER_MIN || t.intervalSeconds() > TIMER_MAX)
                    ? Optional.of(ValidationError.of("timerRange", "min", TIMER_MIN, "max", TIMER_MAX))
                    : Optional.empty();
            case Trigger.PlayerNear t -> (t.rangeBlocks() < NEAR_MIN || t.rangeBlocks() > NEAR_MAX)
                    ? Optional.of(ValidationError.of("playerNearRange", "min", NEAR_MIN, "max", NEAR_MAX))
                    : Optional.empty();
            case Trigger.PlayerJoin ignored -> Optional.empty();
            case Trigger.PlayerKill ignored -> Optional.empty();
            case Trigger.WallReady ignored -> Optional.empty();
            // playerLeaveRange 复用 NEAR_MIN..NEAR_MAX（与 playerNear
            // 同半径语义）；rightClickWall / playerQuit 无字段。
            case Trigger.PlayerLeaveRange t -> (t.rangeBlocks() < NEAR_MIN || t.rangeBlocks() > NEAR_MAX)
                    ? Optional.of(ValidationError.of("playerLeaveRange", "min", NEAR_MIN, "max", NEAR_MAX))
                    : Optional.empty();
            case Trigger.RightClickWall ignored -> Optional.empty();
            case Trigger.PlayerQuit ignored -> Optional.empty();
        };
    }

    /**
     * 递归积木计数：每个 Action 计 1，If 自身计 1 再加两分支，Repeat 自身计 1 再加 body
     * （<b>不乘 count</b>——这是积木<i>树节点数</i>硬限 {@value #MAX_TOTAL_BLOCKS}，
     * 非展开后的动作数；展开数由运行时 Budget 熔断）。
     */
    private static int countBlocks(List<Action> actions) {
        int count = 0;
        for (Action action : actions) {
            count++;
            if (action instanceof Action.If iff) {
                count += countBlocks(iff.then()) + countBlocks(iff.elseActions());
            } else if (action instanceof Action.Repeat rep) {
                count += countBlocks(rep.body());
            } else if (action instanceof Action.RepeatUntil ru) {
                // repeatUntil body 计入节点数（同 Repeat，不乘轮数——动态轮数由 Budget 兜底）
                count += countBlocks(ru.body());
            } else if (action instanceof Action.TweenBlock tb) {
                // tween body 计入节点数（body 里的属性动作都是树节点）
                count += countBlocks(tb.body());
            } else if (action instanceof Action.RandomBranch rb) {
                // 随机分支 then/else 计入节点数（同 If）
                count += countBlocks(rb.then()) + countBlocks(rb.elseActions());
            }
        }
        return count;
    }

    /** 逐个动作校验；ifDepth = 进入本层前已有的 if 嵌套层数。 */
    private static Optional<ValidationError> validateActions(List<Action> actions, int ifDepth) {
        for (Action action : actions) {
            Optional<ValidationError> error = validateAction(action, ifDepth);
            if (error.isPresent()) {
                return error;
            }
        }
        return Optional.empty();
    }

    private static Optional<ValidationError> validateAction(Action action, int ifDepth) {
        return switch (action) {
            case Action.SetVariable a -> {
                if (blank(a.fullName())) {
                    yield Optional.of(ValidationError.of("setVarNameBlank"));
                }
                if (a.value() == null) {
                    yield Optional.of(ValidationError.of("setVarValueNull"));
                }
                if (a.value().length() > SET_VALUE_MAX) {
                    yield Optional.of(ValidationError.of("setVarValueTooLong", "max", SET_VALUE_MAX));
                }
                yield Optional.empty();
            }
            case Action.IncrementVariable a -> {
                if (blank(a.fullName())) {
                    yield Optional.of(ValidationError.of("incrementVarNameBlank"));
                }
                if (!Double.isFinite(a.delta())) {
                    yield Optional.of(ValidationError.of("incrementDeltaNotFinite"));
                }
                yield Optional.empty();
            }
            case Action.SetElementProperty a -> {
                if (blank(a.elementId())) {
                    yield Optional.of(ValidationError.of("setElementPropMissingId"));
                }
                if (a.property() == null || !ELEMENT_PROPERTIES.contains(a.property())) {
                    yield Optional.of(ValidationError.of("elementPropNotAllowed", "property", a.property()));
                }
                if (blank(a.value())) {
                    yield Optional.of(ValidationError.of("setElementPropValueBlank"));
                }
                yield Optional.empty();
            }
            case Action.PlayTimeline a -> {
                if (blank(a.timelineId())) {
                    yield Optional.of(ValidationError.of("timelineMissingId"));
                }
                if (a.op() == null || !TIMELINE_OPS.contains(a.op())) {
                    yield Optional.of(ValidationError.of("timelineOpNotAllowed", "op", a.op()));
                }
                if ("seek".equals(a.op()) && a.seekMs() == null) {
                    yield Optional.of(ValidationError.of("timelineSeekMissingMs"));
                }
                if (a.seekMs() != null && a.seekMs() < 0) {
                    yield Optional.of(ValidationError.of("timelineSeekNegative"));
                }
                yield Optional.empty();
            }
            case Action.PlaySound a -> {
                if (blank(a.soundId()) || a.soundId().length() > SOUND_ID_MAX) {
                    yield Optional.of(ValidationError.of("soundIdInvalid", "max", SOUND_ID_MAX));
                }
                // 取反区间写法：NaN 任何比较都为 false，连带被拒（finite 纪律）
                if (!(a.volume() >= VOLUME_MIN && a.volume() <= VOLUME_MAX)) {
                    yield Optional.of(ValidationError.of("soundVolumeRange", "min", VOLUME_MIN, "max", VOLUME_MAX));
                }
                if (!(a.pitch() >= PITCH_MIN && a.pitch() <= PITCH_MAX)) {
                    yield Optional.of(ValidationError.of("soundPitchRange", "min", PITCH_MIN, "max", PITCH_MAX));
                }
                if (a.scope() == null || !SOUND_SCOPES.contains(a.scope())) {
                    yield Optional.of(ValidationError.of("soundScopeNotAllowed", "scope", a.scope()));
                }
                yield Optional.empty();
            }
            case Action.Wait a -> (a.ms() < WAIT_MIN || a.ms() > WAIT_MAX)
                    ? Optional.of(ValidationError.of("waitRange", "min", WAIT_MIN, "max", WAIT_MAX))
                    : Optional.empty();
            case Action.RunCommand a -> {
                if (blank(a.templateId())) {
                    yield Optional.of(ValidationError.of("commandMissingTemplateId"));
                }
                for (Map.Entry<String, String> e : a.params().entrySet()) {
                    // Map.copyOf 已保证 value 非 null，这里只查长度
                    if (e.getValue().length() > COMMAND_PARAM_MAX) {
                        yield Optional.of(ValidationError.of("commandParamTooLong",
                                "name", e.getKey(), "max", COMMAND_PARAM_MAX));
                    }
                }
                yield Optional.empty();
            }
            case Action.Log a -> {
                if (a.message() == null) {
                    yield Optional.of(ValidationError.of("logMessageNull"));
                }
                if (a.message().length() > LOG_MESSAGE_MAX) {
                    yield Optional.of(ValidationError.of("logMessageTooLong", "max", LOG_MESSAGE_MAX));
                }
                yield Optional.empty();
            }
            case Action.If a -> {
                int depth = ifDepth + 1;
                if (depth > MAX_IF_DEPTH) {
                    yield Optional.of(ValidationError.of("ifDepthExceeded", "max", MAX_IF_DEPTH, "depth", depth));
                }
                if (blank(a.condition())) {
                    yield Optional.of(ValidationError.of("ifConditionBlank"));
                }
                if (a.condition().length() > CONDITION_MAX) {
                    yield Optional.of(ValidationError.of("ifConditionTooLong", "max", CONDITION_MAX));
                }
                Optional<ValidationError> thenError = validateActions(a.then(), depth);
                if (thenError.isPresent()) {
                    yield thenError;
                }
                yield validateActions(a.elseActions(), depth);
            }
            case Action.SetElementProperties a -> {
                if (blank(a.elementId())) {
                    yield Optional.of(ValidationError.of("setElementPropMissingId"));
                }
                if (a.patch().isEmpty()) {
                    yield Optional.of(ValidationError.of("patchEmpty"));
                }
                if (a.patch().size() > PATCH_MAX_KEYS) {
                    yield Optional.of(ValidationError.of("patchTooManyKeys", "max", PATCH_MAX_KEYS));
                }
                if (a.kind() != null && a.kind().length() > KIND_MAX) {
                    yield Optional.of(ValidationError.of("kindTooLong", "max", KIND_MAX));
                }
                for (Map.Entry<String, String> e : a.patch().entrySet()) {
                    if (!ELEMENT_PROPERTIES.contains(e.getKey())) {
                        yield Optional.of(ValidationError.of("elementPropNotAllowed", "property", e.getKey()));
                    }
                    // text 空串是合法内容（ElementPropertyApplier.buildPatch 接受空文字）；
                    // color 空串同理（hex 空串会失败，仍检查空）；
                    // 其余键（x/y/w/h/rotation/opacity/fill）空串会静默变 0 或 hex 失败，仍查空
                    if (!"text".equals(e.getKey()) && blank(e.getValue())) {
                        yield Optional.of(ValidationError.of("patchPropValueBlank", "property", e.getKey()));
                    }
                }
                yield Optional.empty();
            }
            case Action.NudgeElement a -> {
                if (blank(a.elementId())) {
                    yield Optional.of(ValidationError.of("nudgeMissingId"));
                }
                if (!Double.isFinite(a.dx()) || !Double.isFinite(a.dy())) {
                    yield Optional.of(ValidationError.of("nudgeDeltaNotFinite"));
                }
                yield Optional.empty();
            }
            case Action.SendMessage a -> {
                if (a.text() == null) {
                    yield Optional.of(ValidationError.of("messageTextNull"));
                }
                if (a.text().length() > MESSAGE_MAX) {
                    yield Optional.of(ValidationError.of("messageTextTooLong", "max", MESSAGE_MAX));
                }
                if (a.channel() == null || !MESSAGE_CHANNELS.contains(a.channel())) {
                    yield Optional.of(ValidationError.of("messageChannelNotAllowed", "channel", a.channel()));
                }
                // target 白名单（trigger / all）
                if (a.target() == null || !MESSAGE_TARGETS.contains(a.target())) {
                    yield Optional.of(ValidationError.of("messageTargetNotAllowed", "target", a.target()));
                }
                yield Optional.empty();
            }
            case Action.SetRandomVariable a -> {
                if (blank(a.fullName())) {
                    yield Optional.of(ValidationError.of("randomVarNameBlank"));
                }
                if (!Double.isFinite(a.min()) || !Double.isFinite(a.max())) {
                    yield Optional.of(ValidationError.of("randomRangeNotFinite"));
                }
                if (a.min() > a.max()) {
                    yield Optional.of(ValidationError.of("randomRangeMinGtMax"));
                }
                yield Optional.empty();
            }
            case Action.ScaleVariable a -> {
                if (blank(a.fullName())) {
                    yield Optional.of(ValidationError.of("scaleVarNameBlank"));
                }
                if (a.op() == null || !SCALE_OPS.contains(a.op())) {
                    yield Optional.of(ValidationError.of("scaleOpNotAllowed", "op", a.op()));
                }
                if (!Double.isFinite(a.factor())) {
                    yield Optional.of(ValidationError.of("scaleFactorNotFinite"));
                }
                if ("divide".equals(a.op()) && a.factor() == 0.0) {
                    yield Optional.of(ValidationError.of("scaleDivideByZero"));
                }
                yield Optional.empty();
            }
            case Action.PlayTimelineAwait a -> blank(a.timelineId())
                    ? Optional.of(ValidationError.of("timelineAwaitMissingId"))
                    : Optional.empty();
            case Action.Repeat a -> {
                if (a.count() < REPEAT_MIN || a.count() > REPEAT_MAX) {
                    yield Optional.of(ValidationError.of("repeatCountRange", "min", REPEAT_MIN, "max", REPEAT_MAX));
                }
                if (a.body().isEmpty()) {
                    yield Optional.of(ValidationError.of("repeatBodyEmpty"));
                }
                // body 递归（ifDepth 不变——repeat 不增 if 嵌套深度）
                yield validateActions(a.body(), ifDepth);
            }
            // 停止 / 粒子 / 等待直到
            case Action.StopScript ignored -> Optional.empty();
            case Action.PlayParticle a -> {
                if (a.particle() == null || !PARTICLE_WHITELIST.contains(a.particle())) {
                    yield Optional.of(ValidationError.of("particleNotAllowed", "particle", a.particle()));
                }
                if (!(a.count() >= PARTICLE_COUNT_MIN && a.count() <= PARTICLE_COUNT_MAX)) {
                    yield Optional.of(ValidationError.of("particleCountRange",
                            "min", PARTICLE_COUNT_MIN, "max", PARTICLE_COUNT_MAX));
                }
                if (!(Double.isFinite(a.offsetX()) && Double.isFinite(a.offsetY())
                        && Double.isFinite(a.offsetZ()))) {
                    yield Optional.of(ValidationError.of("particleOffsetNotFinite"));
                }
                yield Optional.empty();
            }
            case Action.WaitUntil a -> {
                if (blank(a.condition()) || a.condition().length() > CONDITION_MAX) {
                    yield Optional.of(ValidationError.of("waitUntilConditionInvalid", "max", CONDITION_MAX));
                }
                if (!(a.timeoutMs() >= WAIT_UNTIL_TIMEOUT_MIN
                        && a.timeoutMs() <= WAIT_UNTIL_TIMEOUT_MAX)) {
                    yield Optional.of(ValidationError.of("waitUntilTimeoutRange",
                            "min", WAIT_UNTIL_TIMEOUT_MIN, "max", WAIT_UNTIL_TIMEOUT_MAX));
                }
                yield Optional.empty();
            }
            // copy / append / clone / delete
            case Action.CopyVariable a -> (blank(a.target()) || blank(a.source()))
                    ? Optional.of(ValidationError.of("copyVarTargetOrSourceBlank"))
                    : Optional.empty();
            case Action.AppendVariable a -> {
                if (blank(a.fullName())) {
                    yield Optional.of(ValidationError.of("appendVarNameBlank"));
                }
                // text 复用 SetVariable 值长度上限（= VariableStore.MAX_VALUE_LENGTH，4096）
                if (a.text() != null && a.text().length() > SET_VALUE_MAX) {
                    yield Optional.of(ValidationError.of("appendTextTooLong", "max", SET_VALUE_MAX));
                }
                yield Optional.empty();
            }
            case Action.CloneElement a -> {
                if (blank(a.elementId())) {
                    yield Optional.of(ValidationError.of("cloneElementIdBlank"));
                }
                if (!(Math.abs(a.offsetX()) <= ELEMENT_OFFSET_MAX
                        && Math.abs(a.offsetY()) <= ELEMENT_OFFSET_MAX)) {
                    yield Optional.of(ValidationError.of("cloneOffsetOutOfRange", "max", ELEMENT_OFFSET_MAX));
                }
                yield Optional.empty();
            }
            case Action.DeleteElement a -> blank(a.elementId())
                    ? Optional.of(ValidationError.of("deleteElementIdBlank"))
                    : Optional.empty();
            // 重复直到条件（condition 非空 + maxIterations∈[1,100] + body 非空递归）
            case Action.RepeatUntil a -> {
                if (blank(a.condition()) || a.condition().length() > CONDITION_MAX) {
                    yield Optional.of(ValidationError.of("repeatUntilConditionInvalid", "max", CONDITION_MAX));
                }
                if (a.maxIterations() < REPEAT_MIN || a.maxIterations() > REPEAT_MAX) {
                    yield Optional.of(ValidationError.of("repeatUntilMaxRange", "min", REPEAT_MIN, "max", REPEAT_MAX));
                }
                if (a.body().isEmpty()) {
                    yield Optional.of(ValidationError.of("repeatBodyEmpty"));
                }
                // body 递归（ifDepth 不变——repeatUntil 不增 if 嵌套深度，同 Repeat）
                yield validateActions(a.body(), ifDepth);
            }
            // 随机分支 / 置顶置底 / 取整 / 标题弹窗
            case Action.RandomBranch a -> {
                if (a.probability() < RANDOM_BRANCH_PROB_MIN
                        || a.probability() > RANDOM_BRANCH_PROB_MAX) {
                    yield Optional.of(ValidationError.of("randomBranchProbRange",
                            "min", RANDOM_BRANCH_PROB_MIN, "max", RANDOM_BRANCH_PROB_MAX));
                }
                // RandomBranch 与 If 同语义——递增 ifDepth 并查 MAX_IF_DEPTH（双端对齐）
                int depth = ifDepth + 1;
                if (depth > MAX_IF_DEPTH) {
                    yield Optional.of(ValidationError.of("ifRandomBranchDepthExceeded", "max", MAX_IF_DEPTH, "depth", depth));
                }
                // then / else 都可为空（与 If 同语义）；递归校验
                Optional<ValidationError> thenErr = validateActions(a.then(), depth);
                if (thenErr.isPresent()) yield thenErr;
                yield validateActions(a.elseActions(), depth);
            }
            case Action.SetElementLayer a -> {
                if (blank(a.elementId())) {
                    yield Optional.of(ValidationError.of("elementLayerMissingId"));
                }
                if (a.mode() == null || !ELEMENT_LAYER_MODES.contains(a.mode())) {
                    yield Optional.of(ValidationError.of("elementLayerModeNotAllowed", "mode", a.mode()));
                }
                yield Optional.empty();
            }
            case Action.RoundVariable a -> {
                if (blank(a.fullName())) {
                    yield Optional.of(ValidationError.of("roundVarNameBlank"));
                }
                if (a.mode() == null || !ROUND_MODES.contains(a.mode())) {
                    yield Optional.of(ValidationError.of("roundModeNotAllowed", "mode", a.mode()));
                }
                yield Optional.empty();
            }
            case Action.ShowTitle a -> {
                // title 和 subtitle 至少一个非空
                boolean titleEmpty = a.title() == null || a.title().isBlank();
                boolean subtitleEmpty = a.subtitle() == null || a.subtitle().isBlank();
                if (titleEmpty && subtitleEmpty) {
                    yield Optional.of(ValidationError.of("titleBothEmpty"));
                }
                if (a.title() != null && a.title().length() > TITLE_TEXT_MAX) {
                    yield Optional.of(ValidationError.of("titleTooLong", "max", TITLE_TEXT_MAX));
                }
                if (a.subtitle() != null && a.subtitle().length() > TITLE_TEXT_MAX) {
                    yield Optional.of(ValidationError.of("subtitleTooLong", "max", TITLE_TEXT_MAX));
                }
                // 时长 ≥ 0 + 上限
                if (a.fadeInMs() < 0 || a.fadeInMs() > SHOW_TITLE_FADE_MAX_MS) {
                    yield Optional.of(ValidationError.of("titleFadeInRange", "max", SHOW_TITLE_FADE_MAX_MS));
                }
                if (a.stayMs() < 0 || a.stayMs() > SHOW_TITLE_STAY_MAX_MS) {
                    yield Optional.of(ValidationError.of("titleStayRange", "max", SHOW_TITLE_STAY_MAX_MS));
                }
                if (a.fadeOutMs() < 0 || a.fadeOutMs() > SHOW_TITLE_FADE_MAX_MS) {
                    yield Optional.of(ValidationError.of("titleFadeOutRange", "max", SHOW_TITLE_FADE_MAX_MS));
                }
                if (a.target() == null || !TITLE_TARGETS.contains(a.target())) {
                    yield Optional.of(ValidationError.of("titleTargetNotAllowed", "target", a.target()));
                }
                yield Optional.empty();
            }
            // tween：补间动画包裹（docs/scripting-tween.md）
            case Action.TweenBlock a -> {
                // durationMs 范围 [1, TWEEN_DURATION_MAX]
                if (a.durationMs() < 1 || a.durationMs() > TWEEN_DURATION_MAX) {
                    yield Optional.of(ValidationError.of("tweenDurationRange", "max", TWEEN_DURATION_MAX));
                }
                // easing 非 null（wire 层缺失已 fallback LINEAR；但 record 构造后 null 是坏状态）
                if (a.easing() == null) {
                    yield Optional.of(ValidationError.of("tweenEasingNull"));
                }
                // easing.type 合法
                if (a.easing().type() == null) {
                    yield Optional.of(ValidationError.of("tweenEasingTypeUnknown"));
                }
                // CUBIC_BEZIER 时 bezier 必须是 4 个有限参数，且 x1/x2 ∈ [0,1]
                if (a.easing().type() == EasingType.CUBIC_BEZIER) {
                    List<Double> bz = a.easing().bezier();
                    if (bz == null || bz.size() != 4) {
                        yield Optional.of(ValidationError.of("cubicBezierNeeds4"));
                    }
                    for (double v : bz) {
                        if (!Double.isFinite(v)) {
                            yield Optional.of(ValidationError.of("cubicBezierControlNotFinite"));
                        }
                    }
                    double x1 = bz.get(0), x2 = bz.get(2);
                    if (x1 < 0.0 || x1 > 1.0 || x2 < 0.0 || x2 > 1.0) {
                        yield Optional.of(ValidationError.of("cubicBezierXOutOfRange"));
                    }
                } else if (a.easing().bezier() != null) {
                    yield Optional.of(ValidationError.of("bezierOnlyForCubicBezier"));
                }
                // body 不能为空
                if (a.body().isEmpty()) {
                    yield Optional.of(ValidationError.of("tweenBodyEmpty"));
                }
                // body 每条必须是属性动作白名单（SetElementProperties + kind ∈ TWEENABLE_KINDS）
                for (Action bodyAction : a.body()) {
                    if (!(bodyAction instanceof Action.SetElementProperties sep)) {
                        yield Optional.of(ValidationError.of("tweenBodyNotPropertyAction"));
                    }
                    String kind = sep.kind();
                    if (kind == null || kind.isBlank() || !TWEENABLE_KINDS.contains(kind)) {
                        yield Optional.of(ValidationError.of("tweenBodyKindNotAllowed", "kind", kind));
                    }
                    // body 里的属性动作自身递归校验
                    Optional<ValidationError> bodyErr = validateAction(bodyAction, ifDepth);
                    if (bodyErr.isPresent()) yield bodyErr;
                }
                // TODO P3: 同属性在 body 里重复警告（v1 先放行，P3 补）
                yield Optional.empty();
            }
        };
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
