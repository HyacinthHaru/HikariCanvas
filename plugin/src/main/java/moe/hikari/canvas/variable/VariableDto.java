package moe.hikari.canvas.variable;

import org.jetbrains.annotations.Nullable;

/**
 * 给前端 wire 用的变量视图（ready payload + /api/variable/list 端点）。
 *
 * <p>核心目的是把 {@link Variable#referencedByWalls} 字段挡在外面——那是 VariableStore
 * 内部倒排索引，跨 wall 信息泄露给浏览器既无用又是元数据嗅探面。其他字段透传。</p>
 *
 * <p>序列化形态见 {@code docs/protocol.md §3.2}（ready payload 的 {@code variables} 数组）
 * 与 {@code docs/dynamic-data.md §3.3}（HTTP 端点 P3 共用）。</p>
 *
 * <p>{@code userglobal/*} namespace 额外携带 {@link #ownerUuid} / {@link #ownerName}
 * 字段（其他 namespace 为 null），让前端 Picker / Panel 区分"我的全局 / 其他全局"。
 * 工厂方法 {@link #from(Variable, VariableStore)} 自动按 namespace 形态注入。</p>
 */
public record VariableDto(
        String namespace,
        String key,
        VarType type,
        @Nullable String defaultValue,
        @Nullable String currentValue,
        long updatedAt,
        long ttl,
        @Nullable String source,
        @Nullable String ownerUuid,
        @Nullable String ownerName
) {
    /**
     * 从内存 {@link Variable} 投影（无 owner 注入路径，保留向后兼容）；故意丢掉
     * {@link Variable#referencedByWalls}。
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
                v.source(),
                null,
                null
        );
    }

    /**
     * 从 {@link Variable} 投影并自动为 {@code userglobal/*} 注入 owner 信息。
     * 调用方需传入 {@link VariableStore} 让本方法查 {@code getGlobalOwner}；其他 namespace
     * 的变量 owner 字段固定 null。
     */
    public static VariableDto from(Variable v, @Nullable VariableStore store) {
        String ownerUuid = null;
        String ownerName = null;
        if (store != null
                && VariableStore.USER_GLOBAL_NAMESPACE.equals(v.namespace())) {
            var info = store.getGlobalOwner(v.key()).orElse(null);
            if (info != null) {
                ownerUuid = info.ownerUuid().toString();
                ownerName = info.ownerName();
            }
        }
        return new VariableDto(
                v.namespace(),
                v.key(),
                v.type(),
                v.defaultValue(),
                v.currentValue(),
                v.updatedAt(),
                v.ttl(),
                v.source(),
                ownerUuid,
                ownerName
        );
    }
}
