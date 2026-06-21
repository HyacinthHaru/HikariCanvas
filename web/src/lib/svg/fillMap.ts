/**
 * fillMap.ts — SVG presentation attribute → HikariCanvas Fill / Stroke / fillRule / opacity
 *
 * 范围（B2 批次，纯色 MVP）：
 *   - mapFill:     fill="#hex" → SolidFill；fill="none" → undefined；命名色 → hex
 *                  fill="url(#id)" gradient 引用 → undefined（B3 接）
 *   - mapStroke:   stroke + stroke-width → Stroke
 *   - mapFillRule: fill-rule 属性 → 'nonzero' | 'evenodd' | undefined
 *   - mapOpacity:  opacity / fill-opacity 属性 → number | undefined
 *
 * 优先级：style="" 内联属性覆盖同名 presentation 属性。
 */

import type { Fill, Stroke, Stop, LinearGradient, RadialGradient } from '../../types/protocol';

// ---------- 命名色查表（小集，至少覆盖测试 + 常见色）----------

const NAMED_COLORS: Record<string, string> = {
    black:       '#000000',
    white:       '#ffffff',
    red:         '#ff0000',
    green:       '#008000',
    blue:        '#0000ff',
    yellow:      '#ffff00',
    cyan:        '#00ffff',
    aqua:        '#00ffff',
    magenta:     '#ff00ff',
    fuchsia:     '#ff00ff',
    gray:        '#808080',
    grey:        '#808080',
    silver:      '#c0c0c0',
    maroon:      '#800000',
    olive:       '#808000',
    lime:        '#00ff00',
    teal:        '#008080',
    navy:        '#000080',
    purple:      '#800080',
    orange:      '#ffa500',
    pink:        '#ffc0cb',
    brown:       '#a52a2a',
    transparent: 'none',
    none:        'none',
};

// ---------- 内部工具 ----------

/**
 * 解析 style="" 内联属性，返回 prop→value 映射（value 已 trim，property 小写）。
 * 例：`fill:#ff0000; stroke-width:2` → { fill: '#ff0000', 'stroke-width': '2' }
 */
function parseInlineStyle(style: string): Record<string, string> {
    const result: Record<string, string> = {};
    for (const decl of style.split(';')) {
        const colon = decl.indexOf(':');
        if (colon < 0) continue;
        const prop = decl.slice(0, colon).trim().toLowerCase();
        const val  = decl.slice(colon + 1).trim();
        if (prop && val) result[prop] = val;
    }
    return result;
}

/**
 * 读取 SVG 元素的某个 presentation 属性，style 内联覆盖 presentation 属性。
 * 返回 undefined 表示该属性未设置。
 */
function getAttr(el: Element, prop: string): string | undefined {
    const styleStr = el.getAttribute('style') ?? '';
    const inlineStyle = parseInlineStyle(styleStr);
    if (prop in inlineStyle) return inlineStyle[prop];
    const v = el.getAttribute(prop);
    return v !== null ? v : undefined;
}

/**
 * 规整颜色值为 `#rrggbb` 小写 hex，或 undefined（none/transparent）或 null（无法解析）。
 * - `#rgb`  → `#rrggbb`
 * - `#rrggbb` → 原样小写
 * - `#rrggbbaa` → 保留（含 alpha 通道时透传）
 * - 命名色 → 查表
 * - `none` / `transparent` → undefined（透明）
 * - 其它 → null（无法映射，调用方按需处理）
 */
function normalizeColor(raw: string): string | undefined | null {
    const v = raw.trim().toLowerCase();
    if (!v || v === 'none' || v === 'transparent') return undefined;

    // 命名色查表
    if (v in NAMED_COLORS) {
        const mapped = NAMED_COLORS[v];
        return mapped === 'none' ? undefined : mapped;
    }

    // hex 格式
    if (v.startsWith('#')) {
        const hex = v.slice(1);
        if (hex.length === 3) {
            // #rgb → #rrggbb
            return '#' + hex[0] + hex[0] + hex[1] + hex[1] + hex[2] + hex[2];
        }
        if (hex.length === 6 || hex.length === 8) {
            return '#' + hex;
        }
    }

    // url(#id) gradient 引用 → B3 接，此处返回 null 表示「有值但不支持」
    if (v.startsWith('url(')) return null;

    return null;
}

// ---------- Gradient 解析工具 ----------

/**
 * 将百分比字符串或小数字符串转换为 [0, 1] 范围的数字。
 * "50%" → 0.5, "0.5" → 0.5
 */
function parsePercent(v: string | null | undefined, def = 0): number {
    if (v == null || v === '') return def;
    const s = v.trim();
    if (s.endsWith('%')) return parseFloat(s) / 100;
    const n = parseFloat(s);
    return isNaN(n) ? def : n;
}

/**
 * 解析 <linearGradient> / <radialGradient> 内的 <stop> 子元素，
 * 返回 Stop[]；少于 2 个或超过 8 个时截断/返回 undefined。
 */
function parseStops(gradEl: Element): Stop[] | undefined {
    const stopEls = Array.from(gradEl.children).filter(
        (c) => c.tagName.toLowerCase() === 'stop',
    );
    if (stopEls.length < 2) return undefined;
    const truncated = stopEls.slice(0, 8);

    const stops: Stop[] = [];
    for (const s of truncated) {
        const offset = parsePercent(s.getAttribute('offset'), 0);
        const rawColor = s.getAttribute('stop-color') ?? 'black';
        const color = normalizeColor(rawColor);
        if (color === undefined || color === null) continue; // none / 无法解析跳过
        stops.push({ position: offset, color });
    }
    if (stops.length < 2) return undefined;
    // 按 position 升序
    stops.sort((a, b) => a.position - b.position);
    return stops;
}

/**
 * 将 linearGradient 的 x1/y1/x2/y2 转换为角度（度数，[0, 360)）。
 * SVG 坐标：y 轴向下。
 * angle 定义：0° = 沿 +x（左→右），90° = 沿 +y（上→下），顺时针为正。
 */
function linearGradientAngle(el: Element): number {
    const x1 = parsePercent(el.getAttribute('x1'), 0);
    const y1 = parsePercent(el.getAttribute('y1'), 0);
    const x2 = parsePercent(el.getAttribute('x2'), 1);
    const y2 = parsePercent(el.getAttribute('y2'), 0);
    const dx = x2 - x1;
    const dy = y2 - y1;
    // atan2(dy, dx)：dy>0 在 SVG 中是向下
    let deg = (Math.atan2(dy, dx) * 180) / Math.PI;
    if (deg < 0) deg += 360;
    return deg;
}

/**
 * 从 SVG 根元素中按 id 查找并解析渐变定义。
 * 返回 LinearGradient | RadialGradient | undefined。
 */
function resolveGradient(id: string, root: Element): LinearGradient | RadialGradient | undefined {
    const gradEl = root.querySelector(`#${CSS.escape(id)}`);
    if (!gradEl) return undefined;

    const tag = gradEl.tagName.toLowerCase();

    if (tag === 'lineargradient') {
        const angle = linearGradientAngle(gradEl);
        const stops = parseStops(gradEl);
        if (!stops) return undefined;
        return { type: 'linear', angle, stops };
    }

    if (tag === 'radialgradient') {
        const cx = parsePercent(gradEl.getAttribute('cx'), 0.5);
        const cy = parsePercent(gradEl.getAttribute('cy'), 0.5);
        // r%: 50% → 1.0  (r = r% / 50)
        const rRaw = gradEl.getAttribute('r');
        const rPct = rRaw != null ? parseFloat(rRaw) : 50;
        const r = (rRaw?.endsWith('%') ? rPct : rPct * 100) / 50;
        const stops = parseStops(gradEl);
        if (!stops) return undefined;
        return { type: 'radial', cx, cy, r, stops };
    }

    return undefined;
}

// ---------- 公开 API ----------

/**
 * 从 SVG 元素映射 `fill` 属性到 HikariCanvas `Fill`。
 * - `fill="#hex"` 或命名色 → `{ type: 'solid', color: '#rrggbb' }`
 * - `fill="none"` / `fill="transparent"` → `undefined`
 * - `fill="url(#id)"` + root 参数 → LinearGradient / RadialGradient（B3）
 * - `fill="url(#id)"` 且无 root 或 id 不存在 → `undefined`（优雅降级）
 * - 未设置 fill → `undefined`
 *
 * PB-4 降维忽略：gradientTransform / gradientUnits=userSpaceOnUse / spreadMethod / fx,fy
 */
export function mapFill(el: Element, root?: Element): Fill | undefined {
    const raw = getAttr(el, 'fill');
    if (raw === undefined) return undefined;

    const v = raw.trim().toLowerCase();

    // url(#id) gradient 引用
    if (v.startsWith('url(')) {
        if (!root) return undefined;
        // 提取 id：url(#foo) → foo
        const match = /url\(#([^)]+)\)/i.exec(raw.trim());
        if (!match) return undefined;
        const id = match[1].trim();
        return resolveGradient(id, root);
    }

    const color = normalizeColor(raw);
    if (color === undefined || color === null) return undefined; // none / 无法解析

    return { type: 'solid', color };
}

/**
 * 从 SVG 元素映射 `stroke` / `stroke-width` 属性到 HikariCanvas `Stroke`。
 * - 无 stroke 或 stroke="none" → `undefined`
 * - 有效 stroke 颜色 → `{ color: '#rrggbb', width: Number(stroke-width ?? 1) }`
 */
export function mapStroke(el: Element): Stroke | undefined {
    const rawStroke = getAttr(el, 'stroke');
    if (rawStroke === undefined) return undefined;

    const color = normalizeColor(rawStroke);
    if (color === undefined || color === null) return undefined; // stroke="none" 或无法解析

    const rawWidth = getAttr(el, 'stroke-width');
    const width = rawWidth !== undefined ? Number(rawWidth) : 1;

    return { color, width: isNaN(width) ? 1 : width };
}

/**
 * 从 SVG 元素读取 `fill-rule` 属性。
 * 仅返回 `'nonzero'` | `'evenodd'`，其它值或缺省 → `undefined`。
 */
export function mapFillRule(el: Element): 'nonzero' | 'evenodd' | undefined {
    const v = getAttr(el, 'fill-rule');
    if (v === 'nonzero' || v === 'evenodd') return v;
    return undefined;
}

/**
 * 从 SVG 元素读取 `opacity` 属性（也可扩展为 fill-opacity）。
 * 返回 [0, 1] 范围数值；缺省 → `undefined`。
 */
export function mapOpacity(el: Element): number | undefined {
    const v = getAttr(el, 'opacity') ?? getAttr(el, 'fill-opacity');
    if (v === undefined) return undefined;
    const n = Number(v);
    return isNaN(n) ? undefined : Math.max(0, Math.min(1, n));
}
