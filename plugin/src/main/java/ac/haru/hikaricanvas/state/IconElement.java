package ac.haru.hikaricanvas.state;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.regex.Pattern;

/**
 * 矢量图标元素（SVG 矢量 + Fill 联合类型）。契约见 {@code docs/template-spec.md §4.6}。
 *
 * <p><b>source 命名 schema：</b></p>
 * <ul>
 *   <li>{@code fa-solid/<name>} — Font Awesome Free Solid（如 {@code fa-solid/heart}）</li>
 *   <li>{@code fa-regular/<name>} — Font Awesome Free Regular</li>
 *   <li>{@code fa-brands/<name>} — Font Awesome Free Brands</li>
 *   <li>{@code material/<name>} — Material Symbols Outlined（IconRegistry 留 hook）</li>
 *   <li>{@code user/<id>} — 用户自定义 SVG（{@code plugins/HikariCanvas/icons/<id>.svg}）</li>
 *   <li><b>不含 {@code /} 的 legacy 形态</b>（如 {@code "heart"}）— 走 PNG 兼容路径
 *       （{@link ac.haru.hikaricanvas.template.asset.TemplateAssetService}），加载旧模板用</li>
 * </ul>
 *
 * <p><b>校验：</b> source 必须匹配 {@link #SOURCE_RE}，长度 ≤ 64。path 部分支持 {@code .}
 * （形如 {@code material/star.fill}）。pack 部分 {@code ^[a-z0-9_-]+$}。</p>
 *
 * <p><b>fill 替代 tint：</b> 矢量 path 的填充走 {@link Fill} 联合类型，与 Rect/Circle/Shape
 * 共用 {@link ac.haru.hikaricanvas.render.FillPaintBuilder}。{@code fill == null} 表示用 pack 默认
 * 色（IconRenderer 退回原 PNG 路径或 black）。</p>
 *
 * <p><b>tint 字段保留为 deprecated：</b> 旧 .canvas / 模板若只写了 {@code tint}，{@link
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
        String source,    // 图标资源名（whitelist 校验；fa-solid/heart / user/foo / 或 legacy "heart"）
        String tint,      // deprecated：仅供旧模板向后兼容；新协议改用 fill
        Float opacity,
        BlendMode blendMode,
        RenderMode renderMode,
        Fill fill         // 替代 tint；矢量 path 填充。null = pack 默认色
) implements Element {

    /**
     * source 校验。
     *
     * <ul>
     *   <li>{@code <id>} — legacy PNG 形态（旧数据）；不含 {@code /}</li>
     *   <li>{@code <pack>/<name>} — 矢量形态；pack 与 name 各自字符集</li>
     * </ul>
     *
     * <p>pack：{@code ^[a-z0-9_-]+$}（不允许 dot）；name：{@code ^[a-z0-9_.-]+$}（允许 dot，
     * Material Symbols 用如 {@code star.fill}）。总长度 ≤ 64。</p>
     */
    public static final Pattern SOURCE_RE =
            Pattern.compile("^[a-z0-9_-]+(/[a-z0-9_.-]+)?$");

    public static final int SOURCE_MAX_LEN = 64;

    public static boolean isValidSource(String s) {
        return s != null && s.length() <= SOURCE_MAX_LEN && SOURCE_RE.matcher(s).matches();
    }

    /** legacy PNG 形态（不含 {@code /}）。{@code TemplateAssetService} 路径处理。 */
    public static boolean isLegacySource(String s) {
        return s != null && s.indexOf('/') < 0;
    }

    /** 矢量形态返 {@code pack}；legacy 返 {@code null}。 */
    public static String packOf(String source) {
        if (source == null) return null;
        int slash = source.indexOf('/');
        return slash < 0 ? null : source.substring(0, slash);
    }

    /** 矢量形态返 {@code name}；legacy 返 source 本身。 */
    public static String nameOf(String source) {
        if (source == null) return null;
        int slash = source.indexOf('/');
        return slash < 0 ? source : source.substring(slash + 1);
    }
}
