/**
 * svgToElements.ts — Task 13 MVP 闸
 *
 * SVG 文本 → ElementDraft[]。
 * 纯函数：无副作用，无 WS 调用，无 store 依赖。
 *
 * 本期只处理 path / rect / circle / ellipse / line / polyline / polygon（返回 type='path'）；
 * image 形状跳过（留 B3）。
 */

import { parseSvg } from './svgParse';
import { complexityGuard } from './svgSecurity';
import { shapeToPathD } from './shapesToPath';
import { parsePathCommands, commandsToD } from './normalizeD';
import { bakeMatrix, commandsBBox, rebaseToOrigin } from './bakePath';
import { mapFill, mapStroke, mapFillRule, mapOpacity } from './fillMap';
import { parseTransform, mul, IDENTITY, applyPoint } from './transform';
import type { Mat } from './transform';

export interface ElementDraft {
    type: 'path' | 'image';
    props: Record<string, unknown>;
    /** Task 16: 嵌入位图的 data URL，仅 type='image' 时存在 */
    dataUrl?: string;
}

/**
 * 从 shape 元素向上爬 parentElement 至 svg root，收集每级 transform 属性，
 * 累乘为单一仿射矩阵。
 *
 * 累乘语义（从外到内，即从 root 向叶方向）：
 *   m = IDENTITY
 *   for (从最外层祖先 → 元素自身)
 *     m = mul(m, parseTransform(node.transform))
 *
 * 这样 applyPoint(m, p) = 最外祖先 ∘ ... ∘ 自身(p)，
 * 与 SVG 规范一致（子节点 transform 先作用，父节点后作用）。
 */
function ancestorMatrix(shape: Element, root: Element): Mat {
    // 收集从 shape 到 root（含自身）的节点链
    const chain: Element[] = [];
    let cur: Element | null = shape;
    while (cur && cur !== root.parentElement) {
        chain.push(cur);
        if (cur === root) break;
        cur = cur.parentElement;
    }
    // chain[0] = shape 自身, chain[last] = root（或更高层）
    // 翻转为从外到内顺序
    chain.reverse();

    let m: Mat = [...IDENTITY] as Mat;
    for (const node of chain) {
        const t = node.getAttribute('transform');
        if (t) {
            m = mul(m, parseTransform(t));
        }
    }
    return m;
}

/**
 * svgToElements(svg, opts?)
 *
 * 纯函数；DOMParser 只在 happy-dom / 浏览器环境下可用（测试加 @vitest-environment happy-dom）。
 */
export function svgToElements(
    svg: string,
    opts?: { maxBytes?: number; targetWidth?: number; targetHeight?: number },
): ElementDraft[] {
    const doc = parseSvg(svg, opts?.maxBytes);
    complexityGuard(doc.shapes);
    const drafts: ElementDraft[] = [];

    // Task 15: 构建 viewBox 缩放矩阵（仅当 viewBox + targetWidth/targetHeight 均存在时）
    let viewBoxMat: Mat = [...IDENTITY] as Mat;
    if (doc.viewBox && opts?.targetWidth != null && opts?.targetHeight != null) {
        const [minX, minY, vbW, vbH] = doc.viewBox;
        const sx = opts.targetWidth / vbW;
        const sy = opts.targetHeight / vbH;
        // viewBoxMat = translate(-minX*sx, -minY*sy) ∘ scale(sx, sy)
        // 即：先 scale，再 translate（translate 作为外层 m1）
        const scaleMat: Mat = [sx, 0, 0, sy, 0, 0];
        const translateMat: Mat = [1, 0, 0, 1, -minX * sx, -minY * sy];
        viewBoxMat = mul(translateMat, scaleMat);
    }

    for (const shape of doc.shapes) {
        // Task 16: 处理 <image> 标签（嵌入位图）
        if (shape.tagName.toLowerCase() === 'image') {
            const href = shape.getAttribute('href') ?? shape.getAttribute('xlink:href') ?? '';
            if (!href.startsWith('data:')) {
                // 拒绝外部 URL（安全考量，仅允许 data: 前缀）
                console.warn('[svgToElements] <image> external URL rejected:', href.slice(0, 64));
                continue;
            }
            // 获取 x/y/width/height，应用祖先矩阵 + viewBox 矩阵
            const ix = parseFloat(shape.getAttribute('x') ?? '0') || 0;
            const iy = parseFloat(shape.getAttribute('y') ?? '0') || 0;
            const iw = parseFloat(shape.getAttribute('width') ?? '0') || 0;
            const ih = parseFloat(shape.getAttribute('height') ?? '0') || 0;
            const ancestorMat = ancestorMatrix(shape, doc.root as unknown as Element);
            const m = mul(viewBoxMat, ancestorMat);
            // 对四个角点应用变换，取 bbox
            const [x0, y0] = applyPoint(m, ix, iy);
            const [x1, y1] = applyPoint(m, ix + iw, iy);
            const [x2, y2] = applyPoint(m, ix, iy + ih);
            const [x3, y3] = applyPoint(m, ix + iw, iy + ih);
            const bx = Math.min(x0, x1, x2, x3);
            const by = Math.min(y0, y1, y2, y3);
            const bw = Math.max(x0, x1, x2, x3) - bx;
            const bh = Math.max(y0, y1, y2, y3) - by;
            drafts.push({
                type: 'image',
                props: { x: bx, y: by, w: bw, h: bh },
                dataUrl: href,
            });
            continue;
        }

        // Step 1: path d
        const rawD = shapeToPathD(shape);
        if (rawD === null) {
            // 不支持形状 → 跳过
            continue;
        }

        // Step 2: 祖先链 transform 矩阵（viewBoxMat 作为最外层）
        const ancestorMat = ancestorMatrix(shape, doc.root as unknown as Element);
        const m = mul(viewBoxMat, ancestorMat);

        // Step 3: 归一化 → 烘焙矩阵 → bbox → rebase → commandsToD
        let cmds = parsePathCommands(rawD);
        cmds = bakeMatrix(cmds, m);
        const bbox = commandsBBox(cmds);
        cmds = rebaseToOrigin(cmds, bbox.x, bbox.y);
        const d = commandsToD(cmds);

        // Step 4: fill / stroke / fillRule / opacity
        const fill = mapFill(shape, doc.root as unknown as Element);
        const stroke = mapStroke(shape);
        const fillRule = mapFillRule(shape);
        const opacity = mapOpacity(shape);

        // fill 和 stroke 都为空 → 跳过（后端 path 无样式无意义）
        if (!fill && !stroke) continue;

        // Step 5: 组装 draft props（只放有值的字段）
        const props: Record<string, unknown> = {
            x: bbox.x,
            y: bbox.y,
            w: bbox.w,
            h: bbox.h,
            d,
            ...(fill    ? { fill }     : {}),
            ...(stroke  ? { stroke }   : {}),
            ...(fillRule ? { fillRule } : {}),
            ...(opacity != null ? { opacity } : {}),
        };

        drafts.push({ type: 'path', props });
    }

    return drafts;
}
