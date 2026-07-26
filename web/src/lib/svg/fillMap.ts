/**
 * fillMap.ts — SVG presentation attribute → HikariCanvas Fill / Stroke / fillRule / opacity
 *
 * 范围：
 *   - mapFill:     fill="#hex" → SolidFill；fill="none" → undefined；命名色 → hex
 *                  fill="url(#id)" gradient 引用 → 解析或 undefined
 *   - mapStroke:   stroke + stroke-width → Stroke
 *   - mapFillRule: fill-rule 属性 → 'nonzero' | 'evenodd' | undefined
 *   - mapOpacity:  opacity / fill-opacity 属性 → number | undefined
 *
 * 优先级：style="" 内联属性覆盖同名 presentation 属性。
 *
 * <h3>为什么另有一套 resolveFillValue / resolveStrokeValue</h3>
 * mapFill / mapStroke 只看元素<b>自己</b>的属性，且把「没写 fill」和「fill='none'」
 * 都返回 undefined。这两点合起来会丢图：SVG 规范里 fill 的初始值是<b>黑色</b>而不是无填充，
 * 而且 fill 是可继承属性（常见写法是把 fill 写在父 &lt;g&gt; 上）。于是「什么样式都没写的
 * path」（Font Awesome 实心图标就是这形态）和「样式写在父 g 上的图标」会被上游判成
 * 「fill 与 stroke 都为空」直接丢弃，导入结果是 0 个元素。
 *
 * resolveFillValue 把结果拆成三态（有填充 / 显式无填充 / 值解析不了），让上游能区分
 * 「用户明确要求透明」和「我们读不懂这个值」——后者按 SVG 的错误处理规则回落到默认黑色，
 * 而不是把整个形状丢掉。属性继承由调用方（svgToElements）沿祖先链取值后传进来。
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
 *
 * <p>导出给 svgToElements 做祖先链继承查找用（`fill` / `stroke` 等都是可继承属性，
 * 常见写法是把它们写在父 &lt;g&gt; 上）。</p>
 */
export function getPresentationAttr(el: Element, prop: string): string | undefined {
    return getAttr(el, prop);
}

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
            if (!/^[0-9a-f]{3}$/.test(hex)) return null;
            return '#' + hex[0] + hex[0] + hex[1] + hex[1] + hex[2] + hex[2];
        }
        if (hex.length === 6 || hex.length === 8) {
            if (!/^[0-9a-f]{6}([0-9a-f]{2})?$/.test(hex)) return null;
            return '#' + hex;
        }
    }

    // url(#id) gradient 引用 → 此处返回 null 表示「有值但不支持」（gradient 解析在 mapFill）
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
 * SVG 规范里 `fill` 的初始值是黑色（不是「无填充」）。没写 fill 的形状回落到这个颜色。
 */
export const SVG_DEFAULT_FILL_COLOR = '#000000';

/**
 * `currentColor` 的兜底取值。
 *
 * <p>规范上 `currentColor` 等于当前元素继承到的 CSS `color` 属性；SVG 文件里若没写
 * `color`，浏览器用的初始值就是黑色。<b>不</b>取编辑器主题前景色：深色主题下前景是接近
 * 白色的 hsl 值，把图标导成白色贴到浅色画布上等于看不见，而且主题 token 是 hsl()
 * 形态、本模块的颜色解析只认 hex 与命名色。祖先链上写了 `color` 属性时按规范用它，
 * 这条路径由 svgToElements 传 currentColor 参数进来。</p>
 */
export const SVG_DEFAULT_CURRENT_COLOR = '#000000';

/**
 * fill 值的三态解析结果。
 *
 * <ul>
 *   <li>{@code fill} —— 解析出了具体填充（纯色 / 渐变）</li>
 *   <li>{@code none} —— 用户明确要求不填充（`none` / `transparent`）</li>
 *   <li>{@code unresolved} —— 写了值但读不懂（坏 hex、认不出的命名色、引用不到的渐变）。
 *       按 SVG 的错误处理规则，调用方应回落到默认值而不是丢弃形状</li>
 * </ul>
 */
export type FillResolution =
    | { kind: 'fill'; fill: Fill }
    | { kind: 'none' }
    | { kind: 'unresolved' };

/**
 * 解析一个已经取好的 `fill` 属性字符串（继承由调用方完成）。
 *
 * @param raw          fill 属性原值
 * @param root         svg 根元素，用于解析 `url(#id)` 渐变引用
 * @param currentColor `currentColor` 关键字要换成的颜色，缺省黑色
 */
export function resolveFillValue(raw: string, root?: Element, currentColor?: string): FillResolution {
    const v = raw.trim().toLowerCase();

    // url(#id) gradient 引用
    if (v.startsWith('url(')) {
        if (!root) return { kind: 'unresolved' };
        // 提取 id：url(#foo) → foo
        const match = /url\(#([^)]+)\)/i.exec(raw.trim());
        if (!match) return { kind: 'unresolved' };
        const grad = resolveGradient(match[1].trim(), root);
        return grad ? { kind: 'fill', fill: grad } : { kind: 'unresolved' };
    }

    if (v === 'currentcolor') {
        const color = normalizeColor(currentColor ?? SVG_DEFAULT_CURRENT_COLOR);
        if (color === undefined) return { kind: 'none' };
        if (color === null) return { kind: 'fill', fill: { type: 'solid', color: SVG_DEFAULT_CURRENT_COLOR } };
        return { kind: 'fill', fill: { type: 'solid', color } };
    }

    const color = normalizeColor(raw);
    if (color === undefined) return { kind: 'none' };      // none / transparent
    if (color === null) return { kind: 'unresolved' };     // 读不懂的值
    return { kind: 'fill', fill: { type: 'solid', color } };
}

/**
 * 解析一对已经取好的 `stroke` / `stroke-width` 属性字符串（继承由调用方完成）。
 * `stroke` 的初始值是 `none`，所以「读不懂」与「显式 none」都返回 undefined —— 与 fill 不同，
 * 描边缺省本来就没有，回落不会丢内容。
 */
export function resolveStrokeValue(
    rawStroke: string,
    rawWidth?: string,
    currentColor?: string,
): Stroke | undefined {
    const source = rawStroke.trim().toLowerCase() === 'currentcolor'
        ? (currentColor ?? SVG_DEFAULT_CURRENT_COLOR)
        : rawStroke;
    const color = normalizeColor(source);
    if (color === undefined || color === null) return undefined;
    const width = rawWidth !== undefined ? Number(rawWidth) : 1;
    return { color, width: isNaN(width) ? 1 : width };
}

/**
 * 从 SVG 元素映射 `fill` 属性到 HikariCanvas `Fill`。
 * - `fill="#hex"` 或命名色 → `{ type: 'solid', color: '#rrggbb' }`
 * - `fill="none"` / `fill="transparent"` → `undefined`
 * - `fill="url(#id)"` + root 参数 → LinearGradient / RadialGradient
 * - `fill="url(#id)"` 且无 root 或 id 不存在 → `undefined`（优雅降级）
 * - 未设置 fill → `undefined`
 *
 * <p>只看元素自身属性、不做继承与默认值回落；导入主流程走
 * {@link resolveFillValue}（见文件头说明）。</p>
 *
 * PB-4 降维忽略：gradientTransform / gradientUnits=userSpaceOnUse / spreadMethod / fx,fy
 */
export function mapFill(el: Element, root?: Element): Fill | undefined {
    const raw = getAttr(el, 'fill');
    if (raw === undefined) return undefined;
    const res = resolveFillValue(raw, root);
    return res.kind === 'fill' ? res.fill : undefined;
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
