package moe.hikari.canvas.api;

/**
 * 外部插件向 {@link HikariCanvasAPI} 声明变量类型时使用的 hint enum。
 *
 * <p>API 包独立的 {@code VarType}（不引用 {@code moe.hikari.canvas.variable.VarType}）——
 * 让外部插件 import 路径稳定在 {@code moe.hikari.canvas.api.*}，shadowJar relocate 时
 * 通过 {@code exclude moe/hikari/canvas/api/**} 保持公开。</p>
 *
 * <p>语义与内部 {@code moe.hikari.canvas.variable.VarType} 一一对应，由
 * {@code HikariCanvasAPIImpl.convertVarType} 做转换。</p>
 *
 * <p>0.4.0 范围：STRING / NUMBER / BOOLEAN / COLOR。详见 {@code docs/dynamic-data.md §0}。</p>
 */
public enum VarType {
    /** 普通字符串。编辑器渲染普通 text input。 */
    STRING,
    /** 数值（仍以 string 形式存储；语义由插件侧保证）。编辑器渲染 numeric input。 */
    NUMBER,
    /** 布尔。编辑器渲染 toggle。 */
    BOOLEAN,
    /** 颜色（hex 字符串如 {@code "#FF0000"}）。编辑器渲染 color picker。 */
    COLOR
}
