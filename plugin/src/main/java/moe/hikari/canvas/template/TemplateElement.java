package moe.hikari.canvas.template;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * 模板布局中的单个元素。契约见 {@code docs/template-spec.md §4}。
 *
 * <p>v1 实装 {@link Text} / {@link Rect} / {@link Line}。{@code icon} 在 §4.6 列出
 * 但 v1 不实装（解析阶段被 {@link TemplateLoader} 拒绝）。</p>
 *
 * <p><b>字段类型偏宽松：</b> {@code w / h / visible / x / y} 既能是常量（int/bool）
 * 也能是 {@code "${param}"} / {@code "auto"} / {@code "100%"} 等字符串，统一用
 * {@link Object} 持有；M6-C 实例化阶段按字段语义解析。</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = TemplateElement.Text.class, name = "text"),
        @JsonSubTypes.Type(value = TemplateElement.Rect.class, name = "rect"),
        @JsonSubTypes.Type(value = TemplateElement.Line.class, name = "line"),
        @JsonSubTypes.Type(value = TemplateElement.Icon.class, name = "icon"),
})
public sealed interface TemplateElement
        permits TemplateElement.Text, TemplateElement.Rect, TemplateElement.Line, TemplateElement.Icon {

    /** 元素类型字符串。Jackson 由 {@code @JsonTypeInfo} 自动填充。 */
    String type();

    /** 局部 id（可空，加载器为空则自动生成 {@code e-N}）。 */
    String id();

    Object x();
    Object y();
    Object w();
    Object h();
    int rotation();
    Object visible();

    @JsonProperty("z_order")
    int zOrder();

    @JsonProperty("visible_when")
    String visibleWhen();

    // ---------------- text ----------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Text(
            String type,
            String id,
            Object x, Object y, Object w, Object h,
            int rotation,
            Object visible,
            @JsonProperty("z_order") int zOrder,
            @JsonProperty("visible_when") String visibleWhen,

            String content,
            String font,
            Integer size,
            String color,
            String align,
            @JsonProperty("line_height") Double lineHeight,
            @JsonProperty("letter_spacing") Double letterSpacing,
            Boolean vertical,
            TemplateEffects effects
    ) implements TemplateElement {}

    // ---------------- rect ----------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Rect(
            String type,
            String id,
            Object x, Object y, Object w, Object h,
            int rotation,
            Object visible,
            @JsonProperty("z_order") int zOrder,
            @JsonProperty("visible_when") String visibleWhen,

            String fill,
            TemplateEffects.Stroke stroke
    ) implements TemplateElement {}

    // ---------------- line ----------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Line(
            String type,
            String id,
            Object x, Object y, Object w, Object h,
            int rotation,
            Object visible,
            @JsonProperty("z_order") int zOrder,
            @JsonProperty("visible_when") String visibleWhen,

            List<Integer> from,
            List<Integer> to,
            Integer width,
            String color
    ) implements TemplateElement {}

    // ---------------- icon ----------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Icon(
            String type,
            String id,
            Object x, Object y, Object w, Object h,
            int rotation,
            Object visible,
            @JsonProperty("z_order") int zOrder,
            @JsonProperty("visible_when") String visibleWhen,

            /** 图标资源名（不带路径 / 扩展名）；验证后由 TemplateAssetService 解析为 PNG */
            String source,
            /** 染色 #RRGGBB[AA]；null = 原色 */
            String tint
    ) implements TemplateElement {}
}
