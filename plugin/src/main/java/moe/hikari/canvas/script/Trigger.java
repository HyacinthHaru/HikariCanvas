package moe.hikari.canvas.script;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * 墙脚本触发器的多态联合（0.7 引入）：六种触发时机。
 * 契约见 {@code docs/scripting.md §2.2}。
 *
 * <p>wire 形态为扁平字段 + {@code type} 判别（camelCase），与 {@link moe.hikari.canvas.state.KfValue}
 * 同范式（{@link TriggerSerializer} / {@link TriggerDeserializer} 双向自定义、严格互逆）：</p>
 * <ul>
 *   <li>{@code {"type":"variableChange","fullName":"user/score"}} → {@link VariableChange}</li>
 *   <li>{@code {"type":"timer","intervalSeconds":30}} → {@link Timer}</li>
 *   <li>{@code {"type":"playerJoin"}} → {@link PlayerJoin}</li>
 *   <li>{@code {"type":"playerKill"}} → {@link PlayerKill}</li>
 *   <li>{@code {"type":"playerNear","rangeBlocks":8}} → {@link PlayerNear}</li>
 *   <li>{@code {"type":"wallReady"}} → {@link WallReady}</li>
 * </ul>
 *
 * <p>数值范围等结构校验在 {@link ScriptRuleValidator} 做，本类型只管 wire 形态。</p>
 */
@JsonSerialize(using = TriggerSerializer.class)
@JsonDeserialize(using = TriggerDeserializer.class)
public sealed interface Trigger permits
        Trigger.VariableChange, Trigger.Timer, Trigger.PlayerJoin,
        Trigger.PlayerKill, Trigger.PlayerNear, Trigger.WallReady,
        Trigger.RightClickWall, Trigger.PlayerLeaveRange, Trigger.PlayerQuit {

    /** wire 判别字段 {@code type} 的取值（camelCase）。 */
    String wireType();

    /** 变量值变化时触发（fullName 为完整变量名，如 {@code user/score}）。 */
    record VariableChange(String fullName) implements Trigger {
        @Override public String wireType() { return "variableChange"; }
    }

    /** 固定间隔定时触发（秒）。 */
    record Timer(int intervalSeconds) implements Trigger {
        @Override public String wireType() { return "timer"; }
    }

    /** 玩家加入服务器时触发（全局面，见 {@link ScriptPermissions}）。 */
    record PlayerJoin() implements Trigger {
        @Override public String wireType() { return "playerJoin"; }
    }

    /** 玩家击杀时触发（全局面，见 {@link ScriptPermissions}）。 */
    record PlayerKill() implements Trigger {
        @Override public String wireType() { return "playerKill"; }
    }

    /** 玩家靠近 wall 指定方块半径内触发。 */
    record PlayerNear(int rangeBlocks) implements Trigger {
        @Override public String wireType() { return "playerNear"; }
    }

    /** wall 投影就绪（部署完成）时触发。 */
    record WallReady() implements Trigger {
        @Override public String wireType() { return "wallReady"; }
    }

    /** 0.7.1：玩家右键墙的 ItemFrame 时触发（全局面，见 {@link ScriptPermissions}）。 */
    record RightClickWall() implements Trigger {
        @Override public String wireType() { return "rightClickWall"; }
    }

    /**
     * 0.7.1：玩家离开 wall 指定方块半径时触发（墙级，复用 {@code PlayerNearSampler}
     * 的离开沿；同 {@link PlayerNear} 仅需基础 {@link ScriptPermissions#NODE_EDIT}）。
     */
    record PlayerLeaveRange(int rangeBlocks) implements Trigger {
        @Override public String wireType() { return "playerLeaveRange"; }
    }

    /** 0.7.1：玩家退出服务器时触发（全局面，见 {@link ScriptPermissions}）。 */
    record PlayerQuit() implements Trigger {
        @Override public String wireType() { return "playerQuit"; }
    }
}
