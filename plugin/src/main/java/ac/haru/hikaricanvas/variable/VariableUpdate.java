package ac.haru.hikaricanvas.variable;

import org.jetbrains.annotations.Nullable;

import java.time.Duration;

/**
 * Push API 批量更新条目。详见 {@code docs/dynamic-data.md §4.1
 * HikariCanvasAPI.setVariables}。
 *
 * @param value 新当前值（≤ 4096 char）；null 视为"清空"（{@link VariableStore} 用空字符串存）
 * @param ttl   TTL；null = 沿用原 TTL；{@link Duration#ZERO} = 永久
 */
public record VariableUpdate(
        @Nullable String value,
        @Nullable Duration ttl
) {}
