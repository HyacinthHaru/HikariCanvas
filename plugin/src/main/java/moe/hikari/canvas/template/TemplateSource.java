package moe.hikari.canvas.template;

/**
 * 模板的来源标记。决定加载优先级与"是否允许玩家覆盖"：
 *
 * <ul>
 *   <li>{@link #BUILTIN} — jar 内 {@code /templates/*.yml}，只读基线</li>
 *   <li>{@link #SERVER}  — {@code plugins/HikariCanvas/templates/*.yml}，服主可改，
 *       同 {@code id} 覆盖 BUILTIN</li>
 *   <li>{@link #USER}    — {@code plugins/HikariCanvas/user-templates/<uuid>/*.yml}（M14
 *       创意工坊），玩家发布。id 形如 {@code user-<uuid8>-<slug>}，不允许覆盖 builtin/server</li>
 * </ul>
 */
public enum TemplateSource {
    BUILTIN,
    SERVER,
    USER
}
