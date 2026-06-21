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
 * SVG 复杂度上限：形状数 + 估算总顶点数。超限 throw SvgImportError('SVG_TOO_COMPLEX')。
 * 顶点为粗估——path 用 d 里的命令字母数累加、基本形状用常数，不必精确，只为挡住超大输入。
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
            // 命令字母数粗估顶点（M/L/Q/C/H/V/A/S/T/Z 及小写）
            const cmds = d.match(/[MmLlHhVvQqCcSsTtAaZz]/g);
            total += cmds ? cmds.length : 0;
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

/** 解析后闸:原地剥离危险节点 + on* 事件属性 + 外链 image。D10 不支持项一并剔除。 */
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
        // 如 <a xlink:href="javascript:...">）。image 的外链单独按 D10 删整个元素。
        for (const name of ['href', 'xlink:href']) {
            const v = el.getAttribute(name);
            if (v && /^javascript:/i.test(v)) el.removeAttribute(name);
        }

        // image 外链 href / xlink:href（Task 16: 只允许 data: 前缀，外链静默丢弃并 warn）
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
