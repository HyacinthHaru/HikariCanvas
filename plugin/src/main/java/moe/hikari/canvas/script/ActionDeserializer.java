package moe.hikari.canvas.script;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link Action} 反序列化：按 {@code type} 判别字段分流到九个子类 record。
 * 范式同 {@link TriggerDeserializer}；畸形输入一律 {@code reportInputMismatch}
 * （不抛 NPE、不给默认值）。
 *
 * <p>特殊点：{@code playTimeline.seekMs} 可选（缺失 = null）；{@code if} 的
 * {@code then} / {@code else} 数组逐元素 {@code ctxt.readTreeAsValue} 递归，
 * 缺分支按空 list。</p>
 */
public final class ActionDeserializer extends JsonDeserializer<Action> {

    @Override
    public Action deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.readValueAsTree();
        if (node == null || node.isNull()) {
            return null;
        }
        return fromNode(node, ctxt);
    }

    /** 树形态入口（if 分支递归也走这里）。 */
    private Action fromNode(JsonNode node, DeserializationContext ctxt) throws IOException {
        if (!node.isObject()) {
            return ctxt.reportInputMismatch(Action.class,
                    "action must be an object, got: " + node.getNodeType());
        }
        JsonNode typeNode = node.get("type");
        if (typeNode == null || !typeNode.isTextual()) {
            return ctxt.reportInputMismatch(Action.class,
                    "action requires a textual 'type' field");
        }
        String type = typeNode.asText();
        return switch (type) {
            case "setVariable" -> new Action.SetVariable(
                    requireText(ctxt, node, "fullName", type),
                    requireText(ctxt, node, "value", type));
            case "incrementVariable" -> new Action.IncrementVariable(
                    requireText(ctxt, node, "fullName", type),
                    requireDouble(ctxt, node, "delta", type));
            case "setElementProperty" -> new Action.SetElementProperty(
                    requireText(ctxt, node, "elementId", type),
                    requireText(ctxt, node, "property", type),
                    requireText(ctxt, node, "value", type));
            case "playTimeline" -> new Action.PlayTimeline(
                    requireText(ctxt, node, "timelineId", type),
                    requireText(ctxt, node, "op", type),
                    optionalLong(ctxt, node, "seekMs", type));
            case "playSound" -> new Action.PlaySound(
                    requireText(ctxt, node, "soundId", type),
                    requireDouble(ctxt, node, "volume", type),
                    requireDouble(ctxt, node, "pitch", type),
                    requireText(ctxt, node, "scope", type));
            case "wait" -> new Action.Wait(
                    requireLong(ctxt, node, "ms", type));
            case "runCommand" -> new Action.RunCommand(
                    requireText(ctxt, node, "templateId", type),
                    readParams(ctxt, node, type));
            case "log" -> new Action.Log(
                    requireText(ctxt, node, "message", type));
            case "if" -> new Action.If(
                    requireText(ctxt, node, "condition", type),
                    readBranch(ctxt, node, "then", type),
                    readBranch(ctxt, node, "else", type));
            default -> ctxt.reportInputMismatch(Action.class,
                    "unknown action type: " + type);
        };
    }

    /** 必填文本字段；缺失 / 非文本 → reportInputMismatch。 */
    private static String requireText(DeserializationContext ctxt, JsonNode node,
                                      String field, String type) throws IOException {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual()) {
            return ctxt.reportInputMismatch(Action.class,
                    "action '" + type + "' requires textual field '" + field + "'");
        }
        return v.asText();
    }

    /** 必填数值字段；缺失 / 非数值 → reportInputMismatch。 */
    private static double requireDouble(DeserializationContext ctxt, JsonNode node,
                                        String field, String type) throws IOException {
        JsonNode v = node.get(field);
        if (v == null || !v.isNumber()) {
            return ctxt.reportInputMismatch(Action.class,
                    "action '" + type + "' requires numeric field '" + field + "'");
        }
        return v.asDouble();
    }

    /** 必填长整数字段；缺失 / 非整数 → reportInputMismatch。 */
    private static long requireLong(DeserializationContext ctxt, JsonNode node,
                                    String field, String type) throws IOException {
        JsonNode v = node.get(field);
        if (v == null || !v.canConvertToLong()) {
            return ctxt.reportInputMismatch(Action.class,
                    "action '" + type + "' requires int field '" + field + "'");
        }
        return v.asLong();
    }

    /** 可选长整数字段；缺失 = null，存在但非整数 → reportInputMismatch。 */
    private static Long optionalLong(DeserializationContext ctxt, JsonNode node,
                                     String field, String type) throws IOException {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.canConvertToLong()) {
            return ctxt.reportInputMismatch(Action.class,
                    "action '" + type + "' field '" + field + "' must be int");
        }
        return v.asLong();
    }

    /** runCommand 的 params object → Map（缺失当空 map；值必须全是 string）。 */
    private static Map<String, String> readParams(DeserializationContext ctxt, JsonNode node,
                                                  String type) throws IOException {
        JsonNode v = node.get("params");
        if (v == null || v.isNull()) {
            return Map.of();
        }
        if (!v.isObject()) {
            return ctxt.reportInputMismatch(Action.class,
                    "action '" + type + "' field 'params' must be an object");
        }
        Map<String, String> out = new LinkedHashMap<>();
        var it = v.fields();
        while (it.hasNext()) {
            var e = it.next();
            if (!e.getValue().isTextual()) {
                return ctxt.reportInputMismatch(Action.class,
                        "action '" + type + "' params value '" + e.getKey() + "' must be string");
            }
            out.put(e.getKey(), e.getValue().asText());
        }
        return out;
    }

    /** if 分支数组逐元素递归；缺分支按空 list。 */
    private List<Action> readBranch(DeserializationContext ctxt, JsonNode node,
                                    String field, String type) throws IOException {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return List.of();
        }
        if (!v.isArray()) {
            return ctxt.reportInputMismatch(Action.class,
                    "action '" + type + "' field '" + field + "' must be an array");
        }
        List<Action> out = new ArrayList<>(v.size());
        for (JsonNode elem : v) {
            out.add(ctxt.readTreeAsValue(elem, Action.class));
        }
        return out;
    }
}
