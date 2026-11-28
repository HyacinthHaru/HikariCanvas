package ac.haru.hikaricanvas.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Objects;

/**
 * {@link HikariCanvasAPI#setVariables} 批量推送时单条变量值的载荷。
 *
 * @param value 字符串值（不可为 {@code null}，长度 ≤ 4096；允许空串 {@code ""}，渲染期空串走兜底链）
 * @param ttl   有效期：{@code null} = 沿用变量当前有效期（首次自动创建时默认永久）；
 *              {@link Duration#ZERO} = 永久有效；正数低于 {@code 100ms}（含亚毫秒正数）自动向上钳制到
 *              {@code 100ms}；负数记录警告并丢弃该条更新
 */
public record VariableUpdate(
        @NotNull String value,
        @Nullable Duration ttl
) {
    public VariableUpdate {
        Objects.requireNonNull(value, "value");
    }
}
