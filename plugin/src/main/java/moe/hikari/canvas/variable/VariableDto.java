package moe.hikari.canvas.variable;

import org.jetbrains.annotations.Nullable;

/**
 * 给前端 wire 用的变量视图（0.4.0-P2-F：ready payload + 后续 /api/variable/list 端点）。
 *
 * <p>核心目的是把 {@link Variable#referencedByWalls} 字段挡在外面——那是 VariableStore
 * 内部倒排索引，跨 wall 信息泄露给浏览器既无用又是元数据嗅探面。其他字段透传。</p>
 *
 * <p>序列化形态见 {@code docs/protocol.md §3.2}（ready payload 的 {@code variables} 数组）
 * 与 {@code docs/dynamic-data.md §3.3}（HTTP 端点 P3 共用）。</p>
 *
 * @param namespace    {@link Variable#namespace}，原样
 * @param key          {@link Variable#key}，原样
 * @param type         {@link Variable#type}，Jackson 默认枚举名序列化（"NUMBER" / "STRING"...）
 * @param defaultValue {@link Variable#defaultValue}，可 null
 * @param currentValue {@link Variable#currentValue}，可 null
 * @param updatedAt    {@link Variable#updatedAt}
 * @param ttl          {@link Variable#ttl}
 * @param source       {@link Variable#source}，可 null
 */
public record VariableDto(
        String namespace,
        String key,
        VarType type,
        @Nullable String defaultValue,
        @Nullable String currentValue,
        long updatedAt,
        long ttl,
        @Nullable String source
) {
    /**
     * 从内存 {@link Variable} 投影；故意丢掉 {@link Variable#referencedByWalls}。
     */
    public static VariableDto from(Variable v) {
        return new VariableDto(
                v.namespace(),
                v.key(),
                v.type(),
                v.defaultValue(),
                v.currentValue(),
                v.updatedAt(),
                v.ttl(),
                v.source()
        );
    }
}
