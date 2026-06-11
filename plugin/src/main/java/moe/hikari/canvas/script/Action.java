package moe.hikari.canvas.script;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * 墙脚本动作的多态联合（0.7 引入）：8 种动作 + {@link If} 条件分支（可递归嵌套）。
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
        Action.SetRandomVariable, Action.ScaleVariable, Action.PlayTimelineAwait {

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

    /** 执行命令模板（白名单模板 + 参数替换，模板侧在后续批次落地）。 */
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

    /** 0.7.1：批量设元素属性（友好积木的序列化目标）。patch 键 = 属性名（白名单），
     * 值 = 字符串值；kind = 前端皮肤标记（moveTo/resize/...），后端执行忽略，仅透传存储。 */
    record SetElementProperties(String elementId, java.util.Map<String, String> patch,
                                String kind) implements Action {
        @Override public String wireType() { return "setElementProperties"; }
        public SetElementProperties {
            patch = patch == null ? java.util.Map.of() : java.util.Map.copyOf(patch);
        }
    }

    /** 0.7.1：相对移动元素（运行时读当前 x/y + 增量）。 */
    record NudgeElement(String elementId, double dx, double dy) implements Action {
        @Override public String wireType() { return "nudgeElement"; }
    }

    /** 0.7.1：给触发该脚本的玩家发消息。channel = chat|actionbar|title。 */
    record SendMessage(String text, String channel) implements Action {
        @Override public String wireType() { return "sendMessage"; }
    }

    /** 0.7.1：设随机数变量（min..max 闭区间均匀采样，输出整数若两端皆整）。 */
    record SetRandomVariable(String fullName, double min, double max) implements Action {
        @Override public String wireType() { return "setRandomVariable"; }
    }

    /** 0.7.1：变量乘 / 除（读当前值 × / ÷ factor）。op = multiply|divide。 */
    record ScaleVariable(String fullName, String op, double factor) implements Action {
        @Override public String wireType() { return "scaleVariable"; }
    }

    /** 0.7.1：播放时间轴并等播完（等一轮 durationMs，由 Runner 挂起续接）。 */
    record PlayTimelineAwait(String timelineId) implements Action {
        @Override public String wireType() { return "playTimelineAwait"; }
    }
}
