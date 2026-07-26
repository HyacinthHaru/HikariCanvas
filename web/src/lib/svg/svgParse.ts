import { preParseGuard, stripDangerous, SvgImportError } from './svgSecurity';

export interface SvgDoc {
    root: SVGSVGElement;
    shapes: Element[];
    viewBox: [number, number, number, number] | null;
    width: number | null;
    height: number | null;
}

const SHAPE_TAGS = new Set(['path', 'rect', 'circle', 'ellipse', 'line', 'polyline', 'polygon', 'image']);

export function parseSvg(svg: string, maxBytes = 512 * 1024): SvgDoc {
    preParseGuard(svg, maxBytes);
    const doc = new DOMParser().parseFromString(svg, 'image/svg+xml');
    if (doc.querySelector('parsererror')) throw new SvgImportError('SVG_MALFORMED', 'SVG 解析失败');
    const root = doc.documentElement as unknown as SVGSVGElement;
    stripDangerous(root);
    const shapes: Element[] = [];
    const walk = (el: Element) => {
        if (SHAPE_TAGS.has(el.tagName.toLowerCase())) shapes.push(el);
        [...el.children].forEach(walk);
    };
    walk(root);
    return {
        root,
        shapes,
        viewBox: parseViewBox(root.getAttribute('viewBox')),
        width: parseLen(root.getAttribute('width')),
        height: parseLen(root.getAttribute('height')),
    };
}

function parseViewBox(v: string | null): [number, number, number, number] | null {
    if (!v) return null;
    const n = v.trim().split(/[\s,]+/).map(Number);
    return n.length === 4 && n.every(Number.isFinite) ? [n[0], n[1], n[2], n[3]] : null;
}

function parseLen(v: string | null): number | null {
    if (!v) return null;
    // 百分比是相对外层视口的，导入时没有"外层"可参照，只能当作没声明尺寸。
    // （原来这里 parseFloat('100%') 得 100，会被当成 100px。）
    if (v.trim().endsWith('%')) return null;
    const n = parseFloat(v);   // 忽略单位（px 默认）
    return Number.isFinite(n) ? n : null;
}
