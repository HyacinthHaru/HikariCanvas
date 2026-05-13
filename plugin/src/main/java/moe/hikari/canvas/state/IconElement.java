package moe.hikari.canvas.state;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 位图图标元素。M7 polish 实装（{@code docs/template-spec.md §4.6}）。
 *
 * <p>{@code source} 是图标资源名（不含路径前缀也不含扩展名），由服务端 {@link
 * moe.hikari.canvas.template.asset.TemplateAssetService} 解析到
 * {@code /template-assets/icons/&lt;source&gt;.png}（classpath，jar 内）或
 * {@code plugins/HikariCanvas/assets/icons/&lt;source&gt;.png}（服主自定义）。
 * <b>v1 安全约束：</b> source 必须匹配 {@code ^[a-z0-9_-]{1,32}$}，禁止任何路径分隔符。</p>
 *
 * <p>{@code tint} 可空：null = 原色绘制；非空 = 对 PNG 的 alpha 通道乘上该颜色（保留形状，
 * 改色）。前后端均用 {@code globalCompositeOperation='source-in'}（前端）/
 * {@code AlphaComposite.SrcIn} + 填色（后端）实现。</p>
 *
 * <p><b>M8 v2 新增：</b> {@code opacity / blendMode / renderMode}（追加到末尾；nullable）。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IconElement(
        String id,
        int x, int y, int w, int h,
        int rotation,
        boolean locked,
        boolean visible,
        String source,    // 图标资源名（whitelist 校验）
        String tint,      // null 或 #RRGGBB[AA]
        Float opacity,
        BlendMode blendMode,
        RenderMode renderMode
) implements Element {
}
