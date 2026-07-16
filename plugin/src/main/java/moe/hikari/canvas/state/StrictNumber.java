package moe.hikari.canvas.state;

import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * 数值字符串严格文法 + int 钳位的双端唯一权威（rendering.md §9.5）。
 * 前端镜像见 {@code web/src/timeline/interpolation.ts}（{@code STRICT_NUMBER} 正则 +
 * {@code parsePlainNumber} + {@code clampInt}）。
 *
 * <p>渲染层数值解析（{@code KeyframeInterpolator} 纯数字轨值）、变量数值 resolve
 * （{@code VariableInterpolator.resolveAsNumber}）、op 层数值串校验
 * （{@code TimelineOperations.parseValue}）三处共用同一文法——任一处私自
 * {@code Double.parseDouble}（接受 {@code 0x1p4} / 尾随 {@code d|f}）或 {@code parseFloat}
 * （接受 {@code 12abc} 前缀）都会让双端 / 不同输入路径出现解析分叉。</p>
 */
public final class StrictNumber {

    private StrictNumber() {}

    /**
     * 严格数值文法：可选符号 + 整数/小数 + 可选指数。{@code trim} 后<b>整串</b>匹配。
     * op 层校验（{@code matcher().matches()}）与渲染层解析（{@link #parse}）共用，避免重复定义。
     */
    public static final Pattern PATTERN =
            Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");

    /**
     * 严格解析为 double：{@code null} / 不匹配文法 / 非有限值 → {@code 0.0}（§9.5 fallback 链终点）。
     * trim 用 {@link String#trim()}（剥 {@code U+0000..U+0020}）——前端 {@code parsePlainNumber} 对齐
     * 此语义（JS {@code String.trim()} 会多剥 NBSP / 全角空格等全 Unicode 空白，故前端改用
     * {@code [U+0000..U+0020]} strip）。
     */
    public static double parse(@Nullable String raw) {
        if (raw == null) return 0.0;
        String trimmed = raw.trim();
        if (!PATTERN.matcher(trimmed).matches()) return 0.0;
        try {
            double v = Double.parseDouble(trimmed);
            return Double.isFinite(v) ? v : 0.0;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * int 范围钳位：{@code x/y/w/h/rotation} 是 record {@code int} 字段，插值 / 变量 resolve
     * 出的值 {@code Math.round} 后须钳到 {@code [Integer.MIN_VALUE, Integer.MAX_VALUE]} 再写回——
     * 否则 Java {@code (int)} 收窄静默回绕（{@code 3e9 → −1294967296}）而 JS number 不回绕，
     * 双端分叉。前端 {@code clampInt} 同此边界。
     */
    public static int clampInt(long rounded) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, rounded));
    }
}
