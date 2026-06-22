/**
 * shapesToPath.ts
 * 把 SVG 基本形状元素转换为等价的 path `d` 字符串。
 *
 * 支持: rect / circle / ellipse / line / polyline / polygon / path
 * 不支持(返回 null): image 及其它未知元素
 */

/** kappa = 4*(sqrt(2)-1)/3 ≈ 0.5522847498 — 贝塞尔近似圆弧的控制点系数 */
const KAPPA = 0.5522847498;

/**
 * 数字紧凑格式:整数输出整数字符串,浮点数最多 4 位小数并去尾零。
 * 例: 10 → "10", 2.7614 → "2.7614", 3.0 → "3"
 */
function num(n: number): string {
    if (Number.isInteger(n)) return String(n);
    return parseFloat(n.toFixed(4)).toString();
}

/** 读取属性,缺省 0 */
function attr(el: Element, name: string, fallback = 0): number {
    const v = el.getAttribute(name);
    return v !== null ? parseFloat(v) : fallback;
}

/** 解析 points 属性为 [x, y][] */
function parsePoints(el: Element): [number, number][] {
    const raw = el.getAttribute('points') ?? '';
    const nums = raw.trim().split(/[\s,]+/).filter(Boolean).map(Number);
    const pts: [number, number][] = [];
    for (let i = 0; i + 1 < nums.length; i += 2) {
        pts.push([nums[i], nums[i + 1]]);
    }
    return pts;
}

/**
 * 4 段三次贝塞尔近似椭圆,从右极点 (cx+rx, cy) 顺时针出发。
 * 格式: M{cx+rx} {cy} C... C... C... C... Z
 */
function ellipseToPath(cx: number, cy: number, rx: number, ry: number): string {
    const kx = rx * KAPPA;
    const ky = ry * KAPPA;

    // 4 个极点坐标 (右 → 下 → 左 → 上,顺时针)
    // 右 (cx+rx, cy), 下 (cx, cy+ry), 左 (cx-rx, cy), 上 (cx, cy-ry)
    const segments: string[] = [
        `M${num(cx + rx)} ${num(cy)}`,
        // 右 → 下
        `C${num(cx + rx)} ${num(cy + ky)} ${num(cx + kx)} ${num(cy + ry)} ${num(cx)} ${num(cy + ry)}`,
        // 下 → 左
        `C${num(cx - kx)} ${num(cy + ry)} ${num(cx - rx)} ${num(cy + ky)} ${num(cx - rx)} ${num(cy)}`,
        // 左 → 上
        `C${num(cx - rx)} ${num(cy - ky)} ${num(cx - kx)} ${num(cy - ry)} ${num(cx)} ${num(cy - ry)}`,
        // 上 → 右
        `C${num(cx + kx)} ${num(cy - ry)} ${num(cx + rx)} ${num(cy - ky)} ${num(cx + rx)} ${num(cy)}`,
        'Z',
    ];
    return segments.join(' ');
}

/**
 * 将 SVG 元素转换为等价的 path `d` 字符串。
 * - `<path>` 直接返回自身 `d` 属性
 * - `<image>` 返回 null
 * - 其它不支持的元素返回 null
 */
export function shapeToPathD(el: Element): string | null {
    const tag = el.tagName.toLowerCase().replace(/^svg:/, '');

    switch (tag) {
        case 'path': {
            return el.getAttribute('d');
        }

        case 'rect': {
            const x = attr(el, 'x');
            const y = attr(el, 'y');
            const w = attr(el, 'width');
            const h = attr(el, 'height');
            if (w <= 0 || h <= 0) return null;
            return `M${num(x)} ${num(y)} L${num(x + w)} ${num(y)} L${num(x + w)} ${num(y + h)} L${num(x)} ${num(y + h)} Z`;
        }

        case 'line': {
            const x1 = attr(el, 'x1');
            const y1 = attr(el, 'y1');
            const x2 = attr(el, 'x2');
            const y2 = attr(el, 'y2');
            return `M${num(x1)} ${num(y1)} L${num(x2)} ${num(y2)}`;
        }

        case 'polyline': {
            const pts = parsePoints(el);
            if (pts.length === 0) return null;
            const [first, ...rest] = pts;
            const parts = [`M${num(first[0])} ${num(first[1])}`];
            for (const [px, py] of rest) parts.push(`L${num(px)} ${num(py)}`);
            return parts.join(' ');
        }

        case 'polygon': {
            const pts = parsePoints(el);
            if (pts.length === 0) return null;
            const [first, ...rest] = pts;
            const parts = [`M${num(first[0])} ${num(first[1])}`];
            for (const [px, py] of rest) parts.push(`L${num(px)} ${num(py)}`);
            parts.push('Z');
            return parts.join(' ');
        }

        case 'circle': {
            const cx = attr(el, 'cx');
            const cy = attr(el, 'cy');
            const r = attr(el, 'r');
            if (r <= 0) return null;
            return ellipseToPath(cx, cy, r, r);
        }

        case 'ellipse': {
            const cx = attr(el, 'cx');
            const cy = attr(el, 'cy');
            const rx = attr(el, 'rx');
            const ry = attr(el, 'ry');
            if (rx <= 0 || ry <= 0) return null;
            return ellipseToPath(cx, cy, rx, ry);
        }

        case 'image':
        default:
            return null;
    }
}
