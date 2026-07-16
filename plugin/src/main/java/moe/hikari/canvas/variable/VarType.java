package moe.hikari.canvas.variable;

/**
 * 用户变量 / 插件变量 / 系统变量的类型 hint。
 *
 * <p>HikariCanvas 内部所有变量值都按 <b>string</b> 存储（详见 {@code docs/dynamic-data.md §0
 * 设计哲学 / §16-2}）；这里的 {@code VarType} 只是给编辑器 UI 一个 hint，让它在交互上
 * 渲染合适的输入控件（number → numeric input、color → color picker、boolean → toggle）。
 * 业务语义（是不是合法的数 / 合法的颜色）由插件侧负责。</p>
 *
 * <p>STRING / NUMBER / BOOLEAN / COLOR；list / map / object 尚未支持。</p>
 */
public enum VarType {
    STRING,
    NUMBER,
    BOOLEAN,
    COLOR
}
