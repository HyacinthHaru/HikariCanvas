package ac.haru.hikaricanvas.state;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * 关键帧值的多态联合：数值 / 字符串 / {@link Fill}。
 * 契约见 {@code docs/timeline.md §2.3} 与 {@code docs/protocol.md §7}
 * （{@code KfValue = number | string | Fill}）。
 *
 * <p>wire 形态与 {@link Fill} 的 string→Solid / object 按 type 分流同范式
 * （{@link KfValueSerializer} / {@link KfValueDeserializer}）：</p>
 * <ul>
 *   <li>JSON number → {@link Num}（数值类属性 x/y/w/h/rotation/opacity）</li>
 *   <li>JSON string → {@link Str}（离散类 text、颜色 hex、或 {@code ${var:X}} 模板 ——
 *       变量引用的取值时机见 rendering.md §9.5）</li>
 *   <li>JSON object → {@link FillV}（fill 属性；内部走 {@link FillDeserializer}）</li>
 * </ul>
 *
 * <p>值与属性类别的匹配校验在 op 层（{@code TimelineOperations}）做。</p>
 */
@JsonSerialize(using = KfValueSerializer.class)
@JsonDeserialize(using = KfValueDeserializer.class)
public sealed interface KfValue permits KfValue.Num, KfValue.Str, KfValue.FillV {

    /** 数值。 */
    record Num(double value) implements KfValue {}

    /** 字符串（文本内容 / 颜色 hex / {@code ${var:X}} 模板）。 */
    record Str(String value) implements KfValue {}

    /** Fill 联合（solid / linear / radial）。 */
    record FillV(Fill fill) implements KfValue {}

    static KfValue of(double v) {
        return new Num(v);
    }

    static KfValue of(String v) {
        return new Str(v);
    }

    static KfValue of(Fill v) {
        return new FillV(v);
    }
}
