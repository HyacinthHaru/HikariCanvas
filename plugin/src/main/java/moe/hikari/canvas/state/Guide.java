package moe.hikari.canvas.state;

/**
 * 编辑器参考线（M8 v2 引入）。
 *
 * <p>由用户从画布标尺拖出，仅供前端预览参考，<b>不参与</b>渲染到 MC 地图，
 * 也不影响调色板量化输出。服务端只是中继 + 持久化。</p>
 *
 * @param axis     {@code "x"} = 垂直参考线（影响 x 坐标判断）；
 *                 {@code "y"} = 水平参考线
 * @param position 像素坐标（画布内绝对值，{@code 0 .. widthMaps*128} 或 {@code 0 .. heightMaps*128}）
 */
public record Guide(String axis, int position) {
}
