package moe.hikari.canvas.render;

import moe.hikari.canvas.state.Fill;
import moe.hikari.canvas.state.LinearGradient;
import moe.hikari.canvas.state.RadialGradient;
import moe.hikari.canvas.state.SolidFill;
import moe.hikari.canvas.state.Stop;

import java.util.ArrayList;
import java.util.List;

/**
 * sRGB 线性空间色彩 / Fill 插值（0.6 P3）。数学权威定义见 {@code docs/rendering.md §9.4}——
 * 本类与前端 {@code web/src/timeline/colorLerp.ts} 是同一份定义的双端实现，逐位等价细则
 * （lerp 形式 {@code a + (b−a)×t}、{@code round(x×255)} 半数进位、输出含 alpha 当且仅当任一
 * 输入含 alpha、解析失败 step）两端写死相同，由共享向量
 * {@code rendering-test/color-lerp-vectors.json}（expected 精确字符串匹配）在两端 CI 校验。
 *
 * <p>直接在 8-bit sRGB 上线性插值会让中间色偏暗（黑→白中点是 {@code #BCBCBC} 不是
 * {@code #808080}）；故 RGB 分量走 gamma 解码 → 线性插值 → 编码，alpha 线性不经 gamma。</p>
 */
public final class ColorLerp {

    private ColorLerp() {}

    /** 标准 sRGB 传递函数：8-bit 归一分量 → 线性。 */
    static double srgbDecode(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** 标准 sRGB 传递函数：线性 → 归一分量。 */
    static double srgbEncode(double l) {
        return l <= 0.0031308 ? 12.92 * l : 1.055 * Math.pow(l, 1.0 / 2.4) - 0.055;
    }

    /**
     * hex 颜色插值。输入 {@code #RRGGBB} / {@code #RRGGBBAA}（大小写不敏感，缺省 alpha=FF）；
     * 输出大写带 {@code #}，含 alpha 当且仅当任一输入含 alpha。任一侧解析失败 → step
     * （返回 {@code a} 原样，不抛错；§9.4 细则）。
     */
    public static String lerpHex(String a, String b, double t) {
        int[] pa = parseHex(a);
        int[] pb = parseHex(b);
        if (pa == null || pb == null) return a;
        int r = lerpChannelGamma(pa[0], pb[0], t);
        int g = lerpChannelGamma(pa[1], pb[1], t);
        int bl = lerpChannelGamma(pa[2], pb[2], t);
        int al = clamp255(roundHalfUp(pa[3] + (pb[3] - pa[3]) * t));   // alpha 线性，不经 gamma
        boolean hasAlpha = pa[4] == 1 || pb[4] == 1;
        return hasAlpha
                ? String.format("#%02X%02X%02X%02X", r, g, bl, al)
                : String.format("#%02X%02X%02X", r, g, bl);
    }

    /**
     * Fill 插值（§9.4）：仅同类型（solid/linear/radial）且同 stop 数时逐 stop 插值
     * （stop 位置线性、stop 颜色 {@link #lerpHex}；linear 的 angle、radial 的 cx/cy/r 线性）；
     * 类型 / stop 数不一致或任一侧 null → step（返回 {@code a}）。
     */
    public static Fill lerpFill(Fill a, Fill b, double t) {
        if (a == null || b == null) return a;
        if (a instanceof SolidFill sa && b instanceof SolidFill sb) {
            if (sa.color() == null || sb.color() == null) return a;
            return new SolidFill(lerpHex(sa.color(), sb.color(), t));
        }
        if (a instanceof LinearGradient la && b instanceof LinearGradient lb) {
            List<Stop> stops = lerpStops(la.stops(), lb.stops(), t);
            if (stops == null) return a;
            double angle = la.angle() + (lb.angle() - la.angle()) * t;
            return new LinearGradient(angle, stops);
        }
        if (a instanceof RadialGradient ra && b instanceof RadialGradient rb) {
            List<Stop> stops = lerpStops(ra.stops(), rb.stops(), t);
            if (stops == null) return a;
            return new RadialGradient(
                    ra.cx() + (rb.cx() - ra.cx()) * t,
                    ra.cy() + (rb.cy() - ra.cy()) * t,
                    ra.r() + (rb.r() - ra.r()) * t,
                    stops);
        }
        return a;   // 类型不一致 → step
    }

    /** 逐 stop 插值；stop 数不一致 / 任一侧 null / stop 颜色缺失 → null（调用方 step）。 */
    private static List<Stop> lerpStops(List<Stop> a, List<Stop> b, double t) {
        if (a == null || b == null || a.size() != b.size() || a.isEmpty()) return null;
        List<Stop> out = new ArrayList<>(a.size());
        for (int i = 0; i < a.size(); i++) {
            Stop sa = a.get(i);
            Stop sb = b.get(i);
            if (sa == null || sb == null || sa.color() == null || sb.color() == null) return null;
            double pos = sa.position() + (sb.position() - sa.position()) * t;
            out.add(new Stop(pos, lerpHex(sa.color(), sb.color(), t)));
        }
        return out;
    }

    /** 单 RGB 分量：归一 → decode → lerp（a + (b−a)×t 形式）→ encode → 8-bit。 */
    private static int lerpChannelGamma(int a8, int b8, double t) {
        double a = srgbDecode(a8 / 255.0);
        double b = srgbDecode(b8 / 255.0);
        double v = a + (b - a) * t;
        return clamp255(roundHalfUp(srgbEncode(v) * 255.0));
    }

    /** Java Math.round(double) 语义 = floor(x + 0.5)，与 JS Math.round 对正数一致（§9.4）。 */
    private static long roundHalfUp(double x) {
        return Math.round(x);
    }

    private static int clamp255(long v) {
        return (int) Math.max(0, Math.min(255, v));
    }

    /** 解析 #RRGGBB / #RRGGBBAA → {r,g,b,a,hasAlpha(0/1)}；非法 → null。 */
    private static int[] parseHex(String s) {
        if (s == null) return null;
        String h = s.startsWith("#") ? s.substring(1) : s;
        if (h.length() != 6 && h.length() != 8) return null;
        // 逐字符白名单（与 TS 端正则 /^[0-9a-fA-F]+$/ 对齐）——Integer.parseInt(.., 16)
        // 会接受 '+'/'-' 前缀，垃圾输入下两端 step 行为会分叉
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return null;
        }
        int r = Integer.parseInt(h.substring(0, 2), 16);
        int g = Integer.parseInt(h.substring(2, 4), 16);
        int b = Integer.parseInt(h.substring(4, 6), 16);
        int a = h.length() == 8 ? Integer.parseInt(h.substring(6, 8), 16) : 255;
        return new int[] {r, g, b, a, h.length() == 8 ? 1 : 0};
    }
}
