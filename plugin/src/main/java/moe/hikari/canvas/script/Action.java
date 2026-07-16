package moe.hikari.canvas.script;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * 墙脚本动作的多态联合：8 种动作 + {@link If} 条件分支（可递归嵌套）。
 * 契约见 {@code docs/scripting.md §2.2}。
 *
 * <p>wire 形态为扁平字段 + {@code type} 判别（camelCase），与 {@link Trigger} /
 * {@link moe.hikari.canvas.state.KfValue} 同范式（{@link ActionSerializer} /
 * {@link ActionDeserializer} 双向自定义、严格互逆）。{@link If} 的两个分支在 wire 上是
 * {@code "then"} / {@code "else"} 字段（Java 侧 {@code elseActions} 避开关键字）。</p>
 *
 * <p>数值范围 / 白名单等结构校验在 {@link ScriptRuleValidator} 做，本类型只管 wire 形态。</p>
 */
@JsonSerialize(using = ActionSerializer.class)
@JsonDeserialize(using = ActionDeserializer.class)
public sealed interface Action permits
        Action.SetVariable, Action.IncrementVariable, Action.SetElementProperty,
        Action.PlayTimeline, Action.PlaySound, Action.Wait,
        Action.RunCommand, Action.Log, Action.If,
        Action.SetElementProperties, Action.NudgeElement, Action.SendMessage,
        Action.SetRandomVariable, Action.ScaleVariable, Action.PlayTimelineAwait,
        Action.Repeat,
        Action.StopScript, Action.PlayParticle, Action.WaitUntil,
        Action.CopyVariable, Action.AppendVariable, Action.CloneElement, Action.DeleteElement,
        Action.RepeatUntil, Action.TweenBlock,
        Action.RandomBranch, Action.SetElementLayer, Action.RoundVariable, Action.ShowTitle {

    /** wire 判别字段 {@code type} 的取值（camelCase）。 */
    String wireType();

    /** 设置变量值（变量统一是 string，见 dynamic-data.md 决策 2）。 */
    record SetVariable(String fullName, String value) implements Action {
        @Override public String wireType() { return "setVariable"; }
    }

    /** 变量数值累加（按 double 解析当前值再加 delta）。 */
    record IncrementVariable(String fullName, double delta) implements Action {
        @Override public String wireType() { return "incrementVariable"; }
    }

    /** 设置元素属性（property 白名单见 {@link ScriptRuleValidator#ELEMENT_PROPERTIES}）。 */
    record SetElementProperty(String elementId, String property, String value) implements Action {
        @Override public String wireType() { return "setElementProperty"; }
    }

    /** 时间轴控制。op = play|pause|seek；seekMs 仅 seek 时非 null，null 不上 wire。 */
    record PlayTimeline(String timelineId, String op, Long seekMs) implements Action {
        @Override public String wireType() { return "playTimeline"; }
    }

    /** 播放声音。scope = near|all。 */
    record PlaySound(String soundId, double volume, double pitch, String scope) implements Action {
        @Override public String wireType() { return "playSound"; }
    }

    /** 等待毫秒数（范围见 {@link ScriptRuleValidator#WAIT_MIN} / {@code WAIT_MAX}）。 */
    record Wait(long ms) implements Action {
        @Override public String wireType() { return "wait"; }
    }

    /** 执行命令模板（白名单模板 + 参数替换）。 */
    record RunCommand(String templateId, java.util.Map<String, String> params) implements Action {
        @Override public String wireType() { return "runCommand"; }
        public RunCommand { params = params == null ? java.util.Map.of() : java.util.Map.copyOf(params); }
    }

    /** 写审计日志（调试用）。 */
    record Log(String message) implements Action {
        @Override public String wireType() { return "log"; }
    }

    /** elseActions 避开 java 关键字；wire 字段名是 "else"。空分支 = 空 list(非 null)。 */
    record If(String condition, java.util.List<Action> then,
              java.util.List<Action> elseActions) implements Action {
        @Override public String wireType() { return "if"; }
        public If {
            then = then == null ? java.util.List.of() : java.util.List.copyOf(then);
            elseActions = elseActions == null ? java.util.List.of() : java.util.List.copyOf(elseActions);
        }
    }

    /** 批量设元素属性（友好积木的序列化目标）。patch 键 = 属性名（白名单），
     * 值 = 字符串值；kind = 前端皮肤标记（moveTo/resize/...），后端执行忽略，仅透传存储。 */
    record SetElementProperties(String elementId, java.util.Map<String, String> patch,
                                String kind) implements Action {
        @Override public String wireType() { return "setElementProperties"; }
        public SetElementProperties {
            patch = patch == null ? java.util.Map.of() : java.util.Map.copyOf(patch);
        }
    }

    /** 相对移动元素（运行时读当前 x/y + 增量）。 */
    record NudgeElement(String elementId, double dx, double dy) implements Action {
        @Override public String wireType() { return "nudgeElement"; }
    }

    /**
     * 发消息。channel = chat|actionbar|title。target：
     * {@code "trigger"}（默认，发给触发该脚本的玩家）/ {@code "all"}（全服广播）。
     * 旧 payload 无 target → Deserializer 默 {@code "trigger"}（向后兼容）。
     */
    record SendMessage(String text, String channel, String target) implements Action {
        @Override public String wireType() { return "sendMessage"; }
    }

    /** 设随机数变量（min..max 闭区间均匀采样，输出整数若两端皆整）。 */
    record SetRandomVariable(String fullName, double min, double max) implements Action {
        @Override public String wireType() { return "setRandomVariable"; }
    }

    /** 变量乘 / 除（读当前值 × / ÷ factor）。op = multiply|divide。 */
    record ScaleVariable(String fullName, String op, double factor) implements Action {
        @Override public String wireType() { return "scaleVariable"; }
    }

    /** 播放时间轴并等播完（等一轮 durationMs，由 Runner 挂起续接）。 */
    record PlayTimelineAwait(String timelineId) implements Action {
        @Override public String wireType() { return "playTimelineAwait"; }
    }

    /**
     * 有界循环「重复 N 次」。count ∈ [1,100]（范围校验在 {@link ScriptRuleValidator}）；
     * body 为每轮执行的动作，由 {@code ScriptRunner} 展开 count 轮。展开时每轮 body[i] 的
     * blockId 用<b>同一 prefix</b> {@code <blockId>/body/<i>}（不带 round）——前端 body 只一份，
     * 守前后端同构；trace 同 blockId 重复 = 执行多次高亮多次（正确语义）。
     */
    record Repeat(int count, java.util.List<Action> body) implements Action {
        @Override public String wireType() { return "repeat"; }
        public Repeat {
            body = body == null ? java.util.List.of() : java.util.List.copyOf(body);
        }
    }

    /** 中止当前脚本运行（{@code ScriptRunner} 清帧栈，后续动作不执行）。无字段。 */
    record StopScript() implements Action {
        @Override public String wireType() { return "stopScript"; }
    }

    /**
     * 在墙世界坐标播放粒子。{@code particle = minecraft:xxx}（白名单见
     * {@link ScriptRuleValidator}）；{@code count} 个；{@code offset*} 为墙原点的偏移格。
     * wire 字段名 {@code particle}（双端同名）。
     */
    record PlayParticle(String particle, int count, double offsetX, double offsetY, double offsetZ)
            implements Action {
        @Override public String wireType() { return "playParticle"; }
    }

    /** 轮询条件，满足或 {@code timeoutMs} 超时才继续。condition 复用 {@link If} 的条件文法。 */
    record WaitUntil(String condition, long timeoutMs) implements Action {
        @Override public String wireType() { return "waitUntil"; }
    }

    /** 把 source 变量的当前值复制到 target 变量。 */
    record CopyVariable(String target, String source) implements Action {
        @Override public String wireType() { return "copyVariable"; }
    }

    /** 把 text（可含 ${var:X}）追加到 fullName 变量末尾。 */
    record AppendVariable(String fullName, String text) implements Action {
        @Override public String wireType() { return "appendVariable"; }
    }

    /** 克隆元素（新 id + 位置偏移 offsetX/Y）到同 layer。 */
    record CloneElement(String elementId, int offsetX, int offsetY) implements Action {
        @Override public String wireType() { return "cloneElement"; }
    }

    /** 删除元素。 */
    record DeleteElement(String elementId) implements Action {
        @Override public String wireType() { return "deleteElement"; }
    }

    /**
     * 重复执行 body 直到 condition 满足（while 语义：先查后做）或达 maxIterations
     * 上限。与 {@link Repeat}（固定 count 预展开）不同——本动作<b>不预展开</b>，由
     * {@code ScriptRunner} 动态调度（每轮查 condition 决定是否再来一轮，轮数记在 RunState）。
     * maxIterations ∈ [1,100]（范围校验在 {@link ScriptRuleValidator}，复用 repeat 上限）；
     * 与 Budget 双闸防失控。body[j] 的 blockId 用同一 prefix {@code <blockId>/body/<j>}
     * （不带轮数，守前后端同构，照 Repeat 范式）。
     */
    record RepeatUntil(String condition, int maxIterations,
                       java.util.List<Action> body) implements Action {
        @Override public String wireType() { return "repeatUntil"; }
        public RepeatUntil {
            body = body == null ? java.util.List.of() : java.util.List.copyOf(body);
        }
    }

    /**
     * 补间动画包裹（设计总纲 {@code docs/scripting-tween.md}）：在 {@code durationMs}
     * 毫秒内，将 {@code body} 里的属性动作从当前值插值到目标值，以 {@code easing} 函数缓动。
     *
     * <p><b>语义</b>：挂起式执行——脚本走到本动作后挂起 durationMs，补间完成才继续后续动作
     * （照 {@link PlayTimelineAwait} 范式）。执行侧见
     * {@link moe.hikari.canvas.script.engine.TweenScheduler}。</p>
     *
     * <p><b>body 约束</b>：只放「属性动作」白名单——{@link SetElementProperties}，且
     * {@code kind} ∈ {@link ScriptRuleValidator#TWEENABLE_KINDS}（moveTo / resize /
     * rotateTo / setOpacity / setColor）。非属性动作（发消息 / 播声音 / 改变量等）放包裹外。
     * 校验在 {@link ScriptRuleValidator}；wire 不限制（允许未知 kind 存储但校验期拒）。</p>
     *
     * <p><b>easing</b>：复用 {@link moe.hikari.canvas.state.Easing}（0.6 时间轴现成；
     * {@code null} 表现为 {@link moe.hikari.canvas.state.Easing#LINEAR}，校验层拒 null）。</p>
     *
     * <p>{@code durationMs ∈ [1, 60_000]}（校验常量 {@link ScriptRuleValidator#TWEEN_DURATION_MAX}）。</p>
     */
    record TweenBlock(long durationMs, moe.hikari.canvas.state.Easing easing,
                      java.util.List<Action> body) implements Action {
        @Override public String wireType() { return "tweenBlock"; }
        public TweenBlock {
            body = body == null ? java.util.List.of() : java.util.List.copyOf(body);
        }
    }

    /**
     * 随机分支（控制流，照 {@link If} 双臂）。{@code probability} ∈ [0,100]（百分比）；
     * 运行时 {@code rng.nextInt(100) < probability} → 压 then，否则压 else。
     * elseActions 避开 java 关键字；wire 字段名是 "else"。blockId 同构 "/then/<j>" / "/else/<j>"。
     */
    record RandomBranch(int probability, java.util.List<Action> then,
                        java.util.List<Action> elseActions) implements Action {
        @Override public String wireType() { return "randomBranch"; }
        public RandomBranch {
            then = then == null ? java.util.List.of() : java.util.List.copyOf(then);
            elseActions = elseActions == null ? java.util.List.of() : java.util.List.copyOf(elseActions);
        }
    }

    /**
     * 元素置顶/置底（结构性改 state，照 {@link CloneElement}/{@link DeleteElement}
     * 双路径）。{@code mode} ∈ {front, back}；front = 层内末尾（最上渲染），back = 层内开头（最下）。
     * 元素不存在 → error step。
     */
    record SetElementLayer(String elementId, String mode) implements Action {
        @Override public String wireType() { return "setElementLayer"; }
    }

    /**
     * 变量取整（async，照 {@link ScaleVariable}）。
     * {@code mode} ∈ {round, floor, ceil}；读变量当前值 → Double.parse → 取整 → 写回。
     * 非数值 → error step。
     */
    record RoundVariable(String fullName, String mode) implements Action {
        @Override public String wireType() { return "roundVariable"; }
    }

    /**
     * 标题弹窗（主线程，照 {@link SendMessage} doSendMessage）。
     * {@code target} ∈ {trigger, all}；title/subtitle 过 {@code ${var:X}} 插值；
     * ms → tick = ms/50；title 和 subtitle 至少一个非空；时长 ≥ 0。
     */
    record ShowTitle(String title, String subtitle,
                     int fadeInMs, int stayMs, int fadeOutMs,
                     String target) implements Action {
        @Override public String wireType() { return "showTitle"; }
    }
}
