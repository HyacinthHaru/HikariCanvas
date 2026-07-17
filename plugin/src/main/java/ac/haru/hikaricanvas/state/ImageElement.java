package ac.haru.hikaricanvas.state;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 位图图片元素。{@code source} 是上传时返回的 {@code sha256[:16]} 内容寻址 hash；
 * 服务端从 {@code plugins/HikariCanvas/uploads/<source>.png} 加载真实文件，跨 wall
 * 引用同 hash 文件零重复存储（详见 {@code docs/architecture.md §3.7}）。
 *
 * <p>{@code mask} 可选：非空时按 SVG path 几何蒙版裁切，详见 {@link Mask}。Dither 与
 * mask 的叠加顺序「先 dither 再 mask」由 per-element off-buffer 路径自然
 * 达成（见 {@code docs/rendering.md §4.4}）。</p>
 *
 * <p><b>v1 安全约束：</b> {@code source} 必须匹配 {@code ^[0-9a-f]{16}$}（16 字符小写
 * hex）；非法 hash 拒（杜绝路径穿越与文件枚举）。</p>
 *
 * <p>共通字段（含 {@code opacity / blendMode / renderMode}）见 {@link Element}。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImageElement(
        String id,
        int x, int y, int w, int h,
        int rotation,
        boolean locked,
        boolean visible,
        String source,   // sha256[:16] 小写 hex 16 字符
        Mask mask,       // null = 无蒙版
        Float opacity,
        BlendMode blendMode,
        RenderMode renderMode
) implements Element {
}
