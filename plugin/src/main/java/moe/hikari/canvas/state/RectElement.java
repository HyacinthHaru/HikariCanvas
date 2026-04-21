package moe.hikari.canvas.state;

/**
 * 矩形元素。支持纯填充、纯描边（空心框）、填充 + 描边混合三种形态：
 *
 * <ul>
 *   <li>纯填充：{@code fill != null}、{@code stroke == null}</li>
 *   <li>空心框：{@code fill == null}、{@code stroke != null && stroke.width > 0}</li>
 *   <li>混合：两者都非空</li>
 * </ul>
 *
 * <p>{@code fill} 为 {@code null} 时 Jackson 序列化因 {@code NON_NULL} 策略会省略该字段，
 * 与 {@code protocol.md §7 RectElement.fill: string} 的「可选」语义吻合。</p>
 */
public record RectElement(
        String id,
        int x, int y, int w, int h,
        int rotation,
        boolean locked,
        boolean visible,
        String fill,     // 可为 null（空心框）
        Stroke stroke    // 可为 null（纯填充）
) implements Element {
}
