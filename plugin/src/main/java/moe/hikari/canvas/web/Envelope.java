package moe.hikari.canvas.web;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对外 WebSocket 消息信封，契约见 docs/protocol.md §2。
 * {@code id} 和 {@code ts} 可为 null；序列化时由
 * {@link WebServer} 配置的全局 {@code NON_NULL} 策略统一忽略。
 */
public record Envelope(int v, String op, String id, Long ts, Object payload) {

    public static Envelope of(String op, String id, Object payload) {
        return new Envelope(1, op, id, System.currentTimeMillis(), payload);
    }

    public static Envelope pong(String id) {
        return of("pong", id, Map.of());
    }

    public static Envelope error(String id, String code, String message) {
        // Map.of 不允许 null value —— 调用方常传 message=null（handleAuth 拒绝 token 时），
        // 得用 LinkedHashMap 手动 put，Jackson NON_NULL 策略会自动省略 null。
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        if (message != null) payload.put("message", message);
        payload.put("retryable", false);
        return of("error", id, payload);
    }
}
