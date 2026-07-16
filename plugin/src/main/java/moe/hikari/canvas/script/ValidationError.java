package moe.hikari.canvas.script;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 脚本校验错误的结构化载体，取代原先"返回中文字符串"。{@code key} 是 lang yml 里
 * {@code script.validate.<key>} 的后缀；{@code params} 是命名占位符（如 {@code max=64}）。
 * 由 {@code ScriptOpDispatcher} 用 {@code Messages} 按编辑器 locale 渲染成最终文案。
 */
public record ValidationError(String key, Map<String, String> params) {

    /** 无参报错。 */
    public static ValidationError of(String key) {
        return new ValidationError(key, Map.of());
    }

    /** 带命名参数的报错。{@code kv} 必须成对：key1, val1, key2, val2 …（val 用 String.valueOf 转）。 */
    public static ValidationError of(String key, Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("params must be key-value pairs");
        }
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), String.valueOf(kv[i + 1]));
        }
        return new ValidationError(key, m);
    }
}
