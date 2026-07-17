package ac.haru.hikaricanvas.api;

import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Objects;

/**
 * {@link HikariCanvasAPI#setVariables} 批量推送时单条变量值的载荷。
 *
 * @param value 字符串值（非 null；空串合法，长度受 {@code MAX_VALUE_LENGTH=4096} 限制）
 * @param ttl   TTL；{@code null} = 沿用变量当前 TTL；{@link Duration#ZERO} = 永久；
 *              {@code >0} 必须 ≥ 100ms（{@code VariableStore.MIN_TTL_MS}），否则 setVariable
 *              内部抛 {@code VariableException.TTL_INVALID} 被 API 静默 + log warn
 */
public record VariableUpdate(
        String value,
        @Nullable Duration ttl
) {
    public VariableUpdate {
        Objects.requireNonNull(value, "value");
    }
}
