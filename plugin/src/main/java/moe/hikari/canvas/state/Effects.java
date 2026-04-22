package moe.hikari.canvas.state;

/**
 * TextElement 的效果族聚合。契约对应 {@code docs/protocol.md §7 TextElement.effects}。
 *
 * <p>三个字段均可 null，表示对应效果不启用。整个 {@code Effects} 也可为 null
 * （等价于三字段全 null）。Jackson 反序列化规则：</p>
 * <ul>
 *   <li>{@code effects} 字段不存在 → null</li>
 *   <li>{@code "effects": {}} → {@code Effects(null, null, null)}</li>
 *   <li>{@code "effects": {"stroke": {...}}} → {@code Effects(Stroke, null, null)}</li>
 * </ul>
 *
 * <p>渲染顺序（{@code docs/rendering.md §5.4}，从后到前）：
 * {@code glow → shadow → stroke → fill}。</p>
 */
public record Effects(Stroke stroke, Shadow shadow, Glow glow) {
}
