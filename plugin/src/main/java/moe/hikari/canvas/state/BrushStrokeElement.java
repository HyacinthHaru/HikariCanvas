package moe.hikari.canvas.state;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 笔触元素（M12 引入）。承载用户用笔刷工具画出的连续笔迹。
 *
 * <h2>语义</h2>
 * <ul>
 *   <li>{@link #points} —— 简化后的采样点列表（{@code brush.end} 时经 RDP 简化得到），
 *       每个点含相对 bbox 左上的 {@code (x, y, pressure)}</li>
 *   <li>{@link #size} —— 基础笔刷大小（px）。{@code pressureSize=true} 时实际宽度按
 *       压感缩放：{@code actualWidth = size × pressure}</li>
 *   <li>{@link #fill} —— 笔刷填充。v1 大概率是 {@link SolidFill}（纯色），但 Q7 决策允许
 *       {@link LinearGradient} / {@link RadialGradient}（M11 渐变能力复用）</li>
 *   <li>{@link #pressureSize} / {@link #pressureOpacity} —— 压感分别影响大小 / opacity</li>
 *   <li>{@link #renderMode} —— 复用 M11 的 {@code clean / dither}，dither 时笔触在 MC palette
 *       上有 Bayer 4×4 周期抖动让过渡更柔和</li>
 * </ul>
 *
 * <h2>坐标系</h2>
 * {@code points[i].x / y} 相对 element 左上原点（即 {@link #x()} / {@link #y()}）。
 * Transformer 改 x/y 时 points 不动；改 w/h 时 v1 不缩放 points（用户重画即可，M12 v1 不支持
 * brush stroke 的 resize；Q1=B 决策意味着 brush 是高保真元素，缩放语义未来再补）。
 *
 * <h2>渲染（M12-B 实施）</h2>
 * {@code points} 经 Catmull-Rom 拟合 → bezier 曲线，每对相邻点之间画一段 stroke，宽度按
 * {@code pressureSize} 公式取值。线段衔接 {@code CAP_ROUND} + {@code JOIN_ROUND} 保证无缝。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BrushStrokeElement(
        String id,
        int x, int y, int w, int h,
        int rotation,
        boolean locked,
        boolean visible,
        List<BrushPoint> points,
        int size,
        Fill fill,
        boolean pressureSize,
        boolean pressureOpacity,
        Float opacity,
        BlendMode blendMode,
        RenderMode renderMode
) implements Element {
}
