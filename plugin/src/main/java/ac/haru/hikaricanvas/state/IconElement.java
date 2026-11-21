package ac.haru.hikaricanvas.state;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.regex.Pattern;

/**
 * 矢量图标元素（SVG 矢量路径 + {@link Fill} 联合类型）。契约见 {@code docs/rendering.md §4.4}。
 *
 * <p><b>source 命名 schema（{@code <pack>/<name>}）：</b></p>
 * <ul>
 *   <li>{@code fa-solid/<name>} — Font Awesome Free Solid（如 {@code fa-solid/heart}）</li>
 *   <li>{@code fa-regular/<name>} — Font Awesome Free Regular</li>
 *   <li>{@code fa-brands/<name>} — Font Awesome Free Brands</li>
 *   <li>{@code user/<id>} — 用户自定义 SVG（{@code plugins/HikariCanvas/icons/<id>.svg}）</li>
 * </ul>
 *
 * <p><b>校验：</b> source 必须匹配 {@link #SOURCE_RE}（{@code <pack>/<name>}），长度 ≤ 64。
 * {@code pack} 部分 {@code ^[a-z0-9_-]+$}；{@code name} 部分支持 {@code .}（{@code ^[a-z0-9_.-]+$}）。</p>
 *
 * <p><b>fill 替代 tint：</b> 矢量 path 的填充走 {@link Fill} 联合类型，与 Rect/Circle/Shape
 * 共用 {@link ac.haru.hikaricanvas.render.FillPaintBuilder}。{@code fill == null} 表示用默认黑色。</p>
 *
 * <p><b>tint 字段保留为 deprecated：</b> 旧 .canvas 工程若只写了 {@code tint}，{@link
 * IconElementDeserializer} 会把它升级为 {@code SolidFill(tint)}；新协议不应再写入 {@code tint}。
 * 同时存在 {@code fill} + {@code tint} 时以 {@code fill} 为准。
 * Java record 不能在 component 上挂 {@code @Deprecated}，故只在文档中标注，行为靠
 * deserializer + 显式 fill-优先 策略控制。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = IconElementDeserializer.class)
public record IconElement(
        String id,
        int x, int y, int w, int h,
        int rotation,
        boolean locked,
        boolean visible,
        String source,    // 矢量图标资源名（<pack>/<name>，如 fa-solid/heart / user/foo）
        String tint,      // deprecated：仅供旧数据向后兼容；新协议改用 fill
        Float opacity,
        BlendMode blendMode,
        RenderMode renderMode,
        Fill fill         // 矢量 path 填充；null = 默认黑色
) implements Element {

    /**
     * source 校验：必须为 {@code <pack>/<name>} 矢量形态。
     *
     * <p>pack：{@code ^[a-z0-9_-]+$}（不允许 dot）；name：{@code ^[a-z0-9_.-]+$}（允许 dot）。
     * 总长度 ≤ 64。</p>
     */
    public static final Pattern SOURCE_RE =
            Pattern.compile("^[a-z0-9_-]+/[a-z0-9_.-]+$");

    public static final int SOURCE_MAX_LEN = 64;

    public static boolean isValidSource(String s) {
        return s != null && s.length() <= SOURCE_MAX_LEN && SOURCE_RE.matcher(s).matches();
    }

    /** 提取 {@code <pack>/<name>} 中的 {@code pack} 段；不含 {@code /} 时返 {@code null}。 */
    public static String packOf(String source) {
        if (source == null) return null;
        int slash = source.indexOf('/');
        return slash < 0 ? null : source.substring(0, slash);
    }
}
