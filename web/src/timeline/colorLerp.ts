/**
 * sRGB 线性空间色彩 / Fill 插值——后端 `render/ColorLerp.java` 的前端镜像。
 *
 * 数学权威定义见 `docs/rendering.md §9.4`：lerp 形式 `a + (b−a)×t`、`round(x×255)` 半数进位、
 * 输出含 alpha 当且仅当任一输入含 alpha、解析失败 step——**两端写死相同**，由共享向量
 * `rendering-test/color-lerp-vectors.json`（expected 精确字符串匹配）在两端 CI 校验。
 */
import type { Fill } from '../types/protocol';

/** 标准 sRGB 传递函数：8-bit 归一分量 → 线性。 */
export function srgbDecode(c: number): number {
    return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
}

/** 标准 sRGB 传递函数：线性 → 归一分量。 */
export function srgbEncode(l: number): number {
    return l <= 0.0031308 ? 12.92 * l : 1.055 * Math.pow(l, 1.0 / 2.4) - 0.055;
}

/** 解析 #RRGGBB / #RRGGBBAA → [r,g,b,a,hasAlpha]；非法 → null。 */
function parseHex(s: string | null | undefined): [number, number, number, number, boolean] | null {
    if (!s) return null;
    const h = s.startsWith('#') ? s.slice(1) : s;
    if (h.length !== 6 && h.length !== 8) return null;
    if (!/^[0-9a-fA-F]+$/.test(h)) return null;
    const r = parseInt(h.slice(0, 2), 16);
    const g = parseInt(h.slice(2, 4), 16);
    const b = parseInt(h.slice(4, 6), 16);
    const a = h.length === 8 ? parseInt(h.slice(6, 8), 16) : 255;
    return [r, g, b, a, h.length === 8];
}

function clamp255(v: number): number {
    return Math.max(0, Math.min(255, v));
}

function hex2(v: number): string {
    return v.toString(16).toUpperCase().padStart(2, '0');
}

/** 单 RGB 分量：归一 → decode → lerp（a + (b−a)×t 形式）→ encode → 8-bit。 */
function lerpChannelGamma(a8: number, b8: number, t: number): number {
    const a = srgbDecode(a8 / 255.0);
    const b = srgbDecode(b8 / 255.0);
    const v = a + (b - a) * t;
    return clamp255(Math.round(srgbEncode(v) * 255.0));
}

/**
 * hex 颜色插值。输入 #RRGGBB / #RRGGBBAA（大小写不敏感，缺省 alpha=FF）；输出大写带 #，
 * 含 alpha 当且仅当任一输入含 alpha。任一侧解析失败 → step（返回 a 原样）。
 */
export function lerpHex(a: string, b: string, t: number): string {
    const pa = parseHex(a);
    const pb = parseHex(b);
    if (!pa || !pb) return a;
    const r = lerpChannelGamma(pa[0], pb[0], t);
    const g = lerpChannelGamma(pa[1], pb[1], t);
    const bl = lerpChannelGamma(pa[2], pb[2], t);
    const al = clamp255(Math.round(pa[3] + (pb[3] - pa[3]) * t));   // alpha 线性，不经 gamma
    const hasAlpha = pa[4] || pb[4];
    return hasAlpha
        ? `#${hex2(r)}${hex2(g)}${hex2(bl)}${hex2(al)}`
        : `#${hex2(r)}${hex2(g)}${hex2(bl)}`;
}

type StopLike = { position: number; color: string };

/** 逐 stop 插值；stop 数不一致 / 颜色缺失 → null（调用方 step）。 */
function lerpStops(a: StopLike[] | undefined, b: StopLike[] | undefined, t: number): StopLike[] | null {
    if (!a || !b || a.length !== b.length || a.length === 0) return null;
    const out: StopLike[] = [];
    for (let i = 0; i < a.length; i++) {
        const sa = a[i];
        const sb = b[i];
        if (!sa || !sb || sa.color == null || sb.color == null) return null;
        out.push({
            position: sa.position + (sb.position - sa.position) * t,
            color: lerpHex(sa.color, sb.color, t),
        });
    }
    return out;
}

/**
 * Fill 插值（§9.4）：仅同类型且同 stop 数时逐 stop 插值（linear 的 angle、radial 的
 * cx/cy/r 线性）；类型 / stop 数不一致或任一侧缺失 → step（返回 a）。
 */
export function lerpFill(a: Fill, b: Fill, t: number): Fill {
    if (!a || !b) return a;
    if (a.type === 'solid' && b.type === 'solid') {
        if (a.color == null || b.color == null) return a;
        return { type: 'solid', color: lerpHex(a.color, b.color, t) };
    }
    if (a.type === 'linear' && b.type === 'linear') {
        const stops = lerpStops(a.stops, b.stops, t);
        if (!stops) return a;
        return { type: 'linear', angle: a.angle + (b.angle - a.angle) * t, stops };
    }
    if (a.type === 'radial' && b.type === 'radial') {
        const stops = lerpStops(a.stops, b.stops, t);
        if (!stops) return a;
        return {
            type: 'radial',
            cx: a.cx + (b.cx - a.cx) * t,
            cy: a.cy + (b.cy - a.cy) * t,
            r: a.r + (b.r - a.r) * t,
            stops,
        };
    }
    return a;   // 类型不一致 → step
}
