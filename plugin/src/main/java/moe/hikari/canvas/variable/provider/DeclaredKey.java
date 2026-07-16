package moe.hikari.canvas.variable.provider;

import moe.hikari.canvas.variable.VarType;
import org.jetbrains.annotations.Nullable;

/**
 * Provider 声明可用 key 的元数据（编辑器自动补全用）。
 *
 * <p>静态 namespace（如 {@code system}）通过 {@link VariableProvider#declaredKeys()} 返完整 key
 * 列表；动态 namespace（如 {@code scoreboard}）走 {@link VariableProvider#isDynamic()} = true，
 * 不返 declared keys，编辑器 UI 应给出模板字符串说明（如 {@code scoreboard.<obj>.<player>}）。</p>
 *
 * <p>由 {@code GET /api/variable/list-all-namespaces} 端点序列化下发。</p>
 *
 * @param key         完整 key（如 {@code "server.time"} / {@code "wall.id"}），不含 namespace 前缀
 * @param type        值类型 hint，给编辑器选输入控件
 * @param description 给编辑器 UI 显示的人类可读说明；可空
 * @param ttlMs       TTL（毫秒）；让前端知道刷新频率。0 表示永久（如 {@code wall.id}）
 */
public record DeclaredKey(
        String key,
        VarType type,
        @Nullable String description,
        long ttlMs
) {}
