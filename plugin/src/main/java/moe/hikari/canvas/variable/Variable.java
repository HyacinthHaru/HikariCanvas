package moe.hikari.canvas.variable;

import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * 内存态变量记录。详见 {@code docs/dynamic-data.md §2.1}。
 *
 * <p>所有四类数据源（玩家变量 / 插件 push / 系统内置 / PAPI 桥接）统一用此 record 表达；
 * 持久化的只有 {@code namespace="user"} 的子集（落 {@code user_variables} 表）。</p>
 *
 * <p><b>fullName 编码</b>：
 * <ul>
 *   <li>普通 namespace：{@code <namespace>/<key>}（如 {@code bedwars/red_score}）</li>
 *   <li>user 变量：{@code user:<wallId>/<key>}（如 {@code user:w-3a17/红队比分}）——
 *       wallId 用冒号分隔避免 {@code user/<key>} 与 {@code user:<wall>/<key>} 冲突；
 *       对外 placeholder 文本里仍写 {@code ${var:user/红队比分}}，由 Compositor 注入 wallId。</li>
 * </ul></p>
 *
 * <p><b>线程安全</b>：record immutable；{@link #referencedByWalls} 由 {@link VariableStore}
 * 用 ConcurrentHashMap.Set view 维护，外部读取应视为不可变快照。</p>
 *
 * @param namespace          变量命名空间，{@code [a-zA-Z_][a-zA-Z0-9_:]*} ≤ 32（冒号给 user:<wallId> 用）
 * @param key                变量 key，{@code [a-zA-Z0-9_.-]+} ≤ 64
 * @param type               类型 hint
 * @param defaultValue       fallback 值；null = 无 fallback
 * @param currentValue       当前缓存值（push 写入 / 手动设 / Tier 3 算）
 * @param updatedAt          上次更新时间戳（unix ms）
 * @param ttl                TTL ms；0 = 永久；&gt;0 = 过期后渲染走 fallback 链
 * @param source             来源描述："manual" / "system" / 插件 plain name；null = 未声明
 * @param referencedByWalls  哪些 wall 引用该变量（倒排索引；Compositor 渲染前更新）
 */
public record Variable(
        String namespace,
        String key,
        VarType type,
        @Nullable String defaultValue,
        @Nullable String currentValue,
        long updatedAt,
        long ttl,
        @Nullable String source,
        Set<String> referencedByWalls
) {

    /**
     * 组合 fullName。普通 namespace = {@code <namespace>/<key>}；user 变量 namespace 已含
     * {@code user:<wallId>} 整段，本方法不知道也不需要拆。
     */
    public String fullName() {
        return namespace + "/" + key;
    }

    /** {@link #ttl} > 0 且 {@code now - updatedAt > ttl}。0 = 永久不过期。 */
    public boolean isStale(long now) {
        return ttl > 0 && (now - updatedAt) > ttl;
    }
}
