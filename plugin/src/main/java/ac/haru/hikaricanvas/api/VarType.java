package ac.haru.hikaricanvas.api;

/**
 * 外部插件向 {@link HikariCanvasAPI#declareKey} 声明变量类型时使用的枚举提示。
 *
 * <p>位于独立的公开 {@code ac.haru.hikaricanvas.api} 包内（打包不重定位），确保外部插件编译期与运行期类路径长期稳定。
 * 详见 {@code docs/api.md} 与 {@code docs/dynamic-data.md §1}。</p>
 */
public enum VarType {
    /** 普通字符串。编辑器展示常规文本输入框。 */
    STRING,
    /** 数值（仍以字符串形式传输存储，数值合法性由推送方保证）。编辑器展示数值输入控件。 */
    NUMBER,
    /** 布尔值。编辑器展示开关控件。 */
    BOOLEAN,
    /** 颜色值（十六进制颜色串如 {@code "#FF0000"}）。编辑器展示取色器。 */
    COLOR
}
