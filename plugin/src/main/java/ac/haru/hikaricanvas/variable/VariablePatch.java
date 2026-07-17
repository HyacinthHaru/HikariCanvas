package ac.haru.hikaricanvas.variable;

import org.jetbrains.annotations.Nullable;

/**
 * {@code variable.update} op 用：可选字段，只有非 null 字段被应用。详见
 * {@code docs/dynamic-data.md §3.1}。
 *
 * <p><b>不可改的字段</b>：namespace / key（重命名 = delete + create，由前端保证）。
 * 这里只允许改 type / defaultValue；currentValue 走 {@link VariableStore#setValue}。</p>
 */
public record VariablePatch(
        @Nullable VarType type,
        @Nullable String defaultValue
) {
    public boolean isEmpty() {
        return type == null && defaultValue == null;
    }
}
