package moe.hikari.canvas.script;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

/**
 * {@link Trigger} 反序列化：按 {@code type} 判别字段分流到六个子类 record。
 * 范式同 {@link moe.hikari.canvas.state.KfValueDeserializer}；畸形输入一律
 * {@code reportInputMismatch}（不抛 NPE、不给默认值）。
 */
public final class TriggerDeserializer extends JsonDeserializer<Trigger> {

    @Override
    public Trigger deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.readValueAsTree();
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            return ctxt.reportInputMismatch(Trigger.class,
                    "trigger must be an object, got: " + node.getNodeType());
        }
        JsonNode typeNode = node.get("type");
        if (typeNode == null || !typeNode.isTextual()) {
            return ctxt.reportInputMismatch(Trigger.class,
                    "trigger requires a textual 'type' field");
        }
        String type = typeNode.asText();
        return switch (type) {
            case "variableChange" -> new Trigger.VariableChange(
                    requireText(ctxt, node, "fullName", type));
            case "timer" -> new Trigger.Timer(
                    requireInt(ctxt, node, "intervalSeconds", type));
            case "playerJoin" -> new Trigger.PlayerJoin();
            case "playerKill" -> new Trigger.PlayerKill();
            case "playerNear" -> new Trigger.PlayerNear(
                    requireInt(ctxt, node, "rangeBlocks", type));
            case "wallReady" -> new Trigger.WallReady();
            // 0.7.1：3 个新触发器
            case "rightClickWall" -> new Trigger.RightClickWall();
            case "playerLeaveRange" -> new Trigger.PlayerLeaveRange(
                    requireInt(ctxt, node, "rangeBlocks", type));
            case "playerQuit" -> new Trigger.PlayerQuit();
            default -> ctxt.reportInputMismatch(Trigger.class,
                    "unknown trigger type: " + type);
        };
    }

    /** 必填文本字段；缺失 / 非文本 → reportInputMismatch。 */
    private static String requireText(DeserializationContext ctxt, JsonNode node,
                                      String field, String type) throws IOException {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual()) {
            return ctxt.reportInputMismatch(Trigger.class,
                    "trigger '" + type + "' requires textual field '" + field + "'");
        }
        return v.asText();
    }

    /**
     * 必填整数字段；缺失 / 非整数 → reportInputMismatch（不给默认值）。
     *
     * <p>0.7.0-P2(K8)：{@code canConvertToInt} 只查范围，会把 {@code 1.9} 静默截断成
     * {@code 1}——再查 {@code isIntegralNumber()}，wire 给非整数值（含 {@code 1.0}
     * 这种整值浮点）一律拒，双端语义零漂移。</p>
     */
    private static int requireInt(DeserializationContext ctxt, JsonNode node,
                                  String field, String type) throws IOException {
        JsonNode v = node.get(field);
        if (v == null || !v.isIntegralNumber() || !v.canConvertToInt()) {
            return ctxt.reportInputMismatch(Trigger.class,
                    "trigger '" + type + "' field '" + field + "' must be an integer");
        }
        return v.asInt();
    }
}
