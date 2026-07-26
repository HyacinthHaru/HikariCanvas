export class SvgImportError extends Error {
    constructor(public code: string, message: string) { super(message); }
}

/** 解析前闸:体积上限 + 拒 XML 实体(DOCTYPE/ENTITY,防十亿笑 + XXE 表面)。 */
export function preParseGuard(svg: string, maxBytes: number): void {
    if (svg.length > maxBytes) {
        throw new SvgImportError('SVG_TOO_LARGE', `SVG 体积 ${svg.length} 超过上限 ${maxBytes}`);
    }
    if (/<!DOCTYPE/i.test(svg) || /<!ENTITY/i.test(svg)) {
        throw new SvgImportError('SVG_HAS_ENTITY', 'SVG 含 DOCTYPE/ENTITY,已拒绝');
    }
}

/**
 * 数一个 path `d` 串里有多少个数字（O(n) 扫描，不建中间数组）。
 *
 * <p>为什么不是数命令字母：SVG 允许一个命令字母后面跟任意多组参数（`M0 0 L` 后面接
 * 两万组坐标全归那一个 `L`）。按字母数估顶点的话，这种写法只算 1 个顶点，五万顶点的
 * 上限等于不存在，解析阶段照样会展开出十几万个命令对象。</p>
 */
function countNumbers(d: string): number {
    let count = 0;
    let i = 0;
    const n = d.length;
    const isDigit = (c: number): boolean => c >= 48 && c <= 57;
    while (i < n) {
        const c = d.charCodeAt(i);
        // 数字可以以 0-9 / . / + / - 开头
        if (!(isDigit(c) || c === 46 || c === 43 || c === 45)) { i++; continue; }
        count++;
        let seenDot = c === 46;
        i++;
        while (i < n) {
            const cc = d.charCodeAt(i);
            if (isDigit(cc)) { i++; continue; }
            if (cc === 46 && !seenDot) { seenDot = true; i++; continue; }
            if (cc === 101 || cc === 69) {   // e / E 科学记数，后面可带一个符号
                let j = i + 1;
                if (j < n && (d.charCodeAt(j) === 43 || d.charCodeAt(j) === 45)) j++;
                if (j < n && isDigit(d.charCodeAt(j))) { i = j + 1; continue; }
            }
            break;
        }
    }
    return count;
}

/**
 * SVG 复杂度上限：形状数 + 估算总顶点数。超限 throw SvgImportError('SVG_TOO_COMPLEX')。
 * 顶点为粗估——path 按实际参数组数（每 2 个数字算一个点）、基本形状用常数，
 * 不必精确，只为挡住超大输入。
 */
export function complexityGuard(
    shapes: Element[],
    limits?: { maxShapes?: number; maxTotalVertices?: number },
): void {
    const maxShapes = limits?.maxShapes ?? 500;
    const maxTotalVertices = limits?.maxTotalVertices ?? 50000;

    if (shapes.length > maxShapes) {
        throw new SvgImportError('SVG_TOO_COMPLEX', `形状数 ${shapes.length} 超过上限 ${maxShapes}`);
    }

    let total = 0;
    for (const el of shapes) {
        const tag = el.tagName.toLowerCase();
        if (tag === 'path') {
            const d = el.getAttribute('d') ?? '';
            // 命令字母数（Z 之类不带参数的命令靠它计入）与「参数组数」取大者。
            const cmds = d.match(/[MmLlHhVvQqCcSsTtAaZz]/g);
            const byLetter = cmds ? cmds.length : 0;
            const byParams = Math.ceil(countNumbers(d) / 2);
            total += Math.max(byLetter, byParams);
        } else if (tag === 'polyline' || tag === 'polygon') {
            const pts = (el.getAttribute('points') ?? '').trim().split(/[\s,]+/).filter(Boolean);
            total += Math.ceil(pts.length / 2);
        } else {
            total += 4;   // rect/circle/ellipse/line/image 常数
        }
        if (total > maxTotalVertices) {
            throw new SvgImportError('SVG_TOO_COMPLEX', `估算顶点数超过上限 ${maxTotalVertices}`);
        }
    }
}

/** 危险标签列表(小写)——用 tagName.toLowerCase() 匹配,绕过 happy-dom querySelectorAll 大小写敏感问题。 */
const DANGEROUS_TAGS = new Set([
    'script', 'foreignobject', 'use', 'symbol',
    'animate', 'animatetransform', 'animatemotion', 'set', 'style',
]);

/** 解析后闸:原地剥离危险节点 + on* 事件属性 + 外链 image。不支持项一并剔除。 */
export function stripDangerous(root: Element): void {
    // 深度优先遍历,收集要删除的节点,避免边走边删破坏迭代
    const toRemove: Element[] = [];

    const walk = (el: Element) => {
        const tag = el.tagName.toLowerCase();

        // 危险标签
        if (DANGEROUS_TAGS.has(tag)) {
            toRemove.push(el);
            return; // 子节点跟着一起删,无需继续遍历
        }

        // on* 事件属性
        for (const attr of [...el.attributes]) {
            if (/^on/i.test(attr.name)) {
                el.removeAttribute(attr.name);
            }
        }

        // defense-in-depth：任意元素的 javascript: href 删属性（覆盖非 image 元素，
        // 如 <a xlink:href="javascript:...">）。image 的外链单独删整个元素。
        for (const name of ['href', 'xlink:href']) {
            const v = el.getAttribute(name);
            if (v && /^javascript:/i.test(v)) el.removeAttribute(name);
        }

        // image 外链 href / xlink:href（只允许 data: 前缀，外链静默丢弃并 warn）
        if (tag === 'image') {
            const href = el.getAttribute('href') ?? el.getAttribute('xlink:href') ?? '';
            if (href && !/^data:/i.test(href)) {
                console.warn('[svgToElements] <image> external URL rejected:', href.slice(0, 64));
                toRemove.push(el);
                return;
            }
        }

        [...el.children].forEach(walk);
    };

    walk(root);
    toRemove.forEach((n) => n.remove());
}
