package moe.hikari.canvas.template;

/**
 * 模板的来源标记。决定加载优先级与"是否允许玩家覆盖"：
 *
 * <ul>
 *   <li>{@link #BUILTIN} — jar 内 {@code /templates/*.yml}，只读基线</li>
 *   <li>{@link #SERVER}  — {@code plugins/HikariCanvas/templates/*.yml}，服主可改，
 *       同 {@code id} 覆盖 BUILTIN</li>
 * </ul>
 *
 * <p>玩家模板（{@code user-templates/<uuid>/}）留 v1.x 后再加入第三个枚举值。</p>
 */
public enum TemplateSource {
    BUILTIN,
    SERVER
}
