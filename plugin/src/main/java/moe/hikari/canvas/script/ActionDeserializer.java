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
 * {@code then} / {@code else} 数组逐元素直接递归 {@code fromNode}，
 * 缺分支按空 list，分支元素为 null 一律拒绝。</p>
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
            // 0.7.1：6 个新 Action 子类
            case "setElementProperties" -> new Action.SetElementProperties(
                    requireText(ctxt, node, "elementId", type),
                    readStringMap(ctxt, node, "patch", type),
                    optionalText(node, "kind"));   // kind 可空 → optionalText 返 null（record 透传）
            case "nudgeElement" -> new Action.NudgeElement(
                    requireText(ctxt, node, "elementId", type),
                    requireDouble(ctxt, node, "dx", type),
                    requireDouble(ctxt, node, "dy", type));
            case "sendMessage" -> new Action.SendMessage(
                    requireText(ctxt, node, "text", type),
                    requireText(ctxt, node, "channel", type));
            case "setRandomVariable" -> new Action.SetRandomVariable(
                    requireText(ctxt, node, "fullName", type),
                    requireDouble(ctxt, node, "min", type),
                    requireDouble(ctxt, node, "max", type));
            case "scaleVariable" -> new Action.ScaleVariable(
                    requireText(ctxt, node, "fullName", type),
                    requireText(ctxt, node, "op", type),
                    requireDouble(ctxt, node, "factor", type));
            case "playTimelineAwait" -> new Action.PlayTimelineAwait(
                    requireText(ctxt, node, "timelineId", type));
            case "repeat" -> new Action.Repeat(
                    requireInt(ctxt, node, "count", type),
                    readBranch(ctxt, node, "body", type));   // 复用 if 分支递归读取
            // 0.7.1-P5：停止 / 粒子 / 等待直到
            case "stopScript" -> new Action.StopScript();
            case "playParticle" -> new Action.PlayParticle(
                    requireText(ctxt, node, "particle", type),
                    requireInt(ctxt, node, "count", type),
                    requireDouble(ctxt, node, "offsetX", type),
                    requireDouble(ctxt, node, "offsetY", type),
                    requireDouble(ctxt, node, "offsetZ", type));
            case "waitUntil" -> new Action.WaitUntil(
                    requireText(ctxt, node, "condition", type),
                    requireLong(ctxt, node, "timeoutMs", type));
            // 0.7.2-P2：copy / append / clone / delete
            case "copyVariable" -> new Action.CopyVariable(
                    requireText(ctxt, node, "target", type),
                    requireText(ctxt, node, "source", type));
            case "appendVariable" -> new Action.AppendVariable(
                    requireText(ctxt, node, "fullName", type),
                    requireText(ctxt, node, "text", type));
            case "cloneElement" -> new Action.CloneElement(
                    requireText(ctxt, node, "elementId", type),
                    requireInt(ctxt, node, "offsetX", type),
                    requireInt(ctxt, node, "offsetY", type));
            case "deleteElement" -> new Action.DeleteElement(
                    requireText(ctxt, node, "elementId", type));
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

    /**
     * 必填整数字段（int）；缺失 / 非整数 → reportInputMismatch。0.7.1 repeat.count 用，
     * 同 {@link #requireLong} 的 K8 纪律（{@code canConvertToInt} 查范围 + {@code
     * isIntegralNumber} 拒整值浮点 {@code 3.0}）。
     */
    private static int requireInt(DeserializationContext ctxt, JsonNode node,
                                  String field, String type) throws IOException {
        JsonNode v = node.get(field);
        if (v == null || !v.isIntegralNumber() || !v.canConvertToInt()) {
            return ctxt.reportInputMismatch(Action.class,
                    "action '" + type + "' field '" + field + "' must be an integer");
        }
        return v.asInt();
    }

    /**
     * 必填长整数字段；缺失 / 非整数 → reportInputMismatch。
     *
     * <p>0.7.0-P2(K8)：{@code canConvertToLong} 只查范围，会把 {@code 100.5} 静默截断——
     * 再查 {@code isIntegralNumber()}，wire 给非整数值（含 {@code 100.0} 整值浮点）一律拒。
     * {@code delta} 不走本方法（本就是 double）。</p>
     */
    private static long requireLong(DeserializationContext ctxt, JsonNode node,
                                    String field, String type) throws IOException {
        JsonNode v = node.get(field);
        if (v == null || !v.isIntegralNumber() || !v.canConvertToLong()) {
            return ctxt.reportInputMismatch(Action.class,
                    "action '" + type + "' field '" + field + "' must be an integer");
        }
        return v.asLong();
    }

    /** 可选长整数字段；缺失 = null，存在但非整数（K8 含整值浮点）→ reportInputMismatch。 */
    private static Long optionalLong(DeserializationContext ctxt, JsonNode node,
                                     String field, String type) throws IOException {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isIntegralNumber() || !v.canConvertToLong()) {
            return ctxt.reportInputMismatch(Action.class,
                    "action '" + type + "' field '" + field + "' must be an integer");
        }
        return v.asLong();
    }

    /** 可选文本字段；缺失 / null / 非文本 → null（0.7.1 setElementProperties.kind 用）。 */
    private static String optionalText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || !v.isTextual()) ? null : v.asText();
    }

    /**
     * 0.7.1：通用 string map object → {@code Map<String,String>}（缺失当空 map；
     * 值必须全是 string）。范式同 {@link #readParams}，参数化字段名供 setElementProperties.patch 用。
     */
    private static Map<String, String> readStringMap(DeserializationContext ctxt, JsonNode node,
                                                     String field, String type) throws IOException {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return Map.of();
        }
        if (!v.isObject()) {
            return ctxt.reportInputMismatch(Action.class,
                    "action '" + type + "' field '" + field + "' must be an object");
        }
        Map<String, String> out = new LinkedHashMap<>();
        var it = v.fields();
        while (it.hasNext()) {
            var e = it.next();
            if (!e.getValue().isTextual()) {
                return ctxt.reportInputMismatch(Action.class,
                        "action '" + type + "' field '" + field + "' value '"
                                + e.getKey() + "' must be string");
            }
            out.put(e.getKey(), e.getValue().asText());
        }
        return out;
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

    /** if 分支数组逐元素递归（直接走 {@link #fromNode}）；缺分支按空 list；元素为 null 拒绝。 */
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
            if (elem == null || elem.isNull()) {
                return ctxt.reportInputMismatch(Action.class,
                        "action '" + type + "' field '" + field + "' 分支元素不能为 null");
            }
            out.add(fromNode(elem, ctxt));
        }
        return out;
    }
}
