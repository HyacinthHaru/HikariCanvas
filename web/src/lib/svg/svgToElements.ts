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
import { shapeToPathD } from './shapesToPath';
import { parsePathCommands, commandsToD } from './normalizeD';
import { bakeMatrix, commandsBBox, rebaseToOrigin } from './bakePath';
import { mapFill, mapStroke, mapFillRule, mapOpacity } from './fillMap';
import { parseTransform, mul, IDENTITY } from './transform';
import type { Mat } from './transform';

export interface ElementDraft {
    type: 'path' | 'image';
    props: Record<string, unknown>;
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
export function svgToElements(svg: string, opts?: { maxBytes?: number }): ElementDraft[] {
    const doc = parseSvg(svg, opts?.maxBytes);
    const drafts: ElementDraft[] = [];

    for (const shape of doc.shapes) {
        // Step 1: path d
        const rawD = shapeToPathD(shape);
        if (rawD === null) {
            // image 或不支持形状 → B3 留
            continue;
        }

        // Step 2: 祖先链 transform 矩阵
        const m = ancestorMatrix(shape, doc.root as unknown as Element);

        // Step 3: 归一化 → 烘焙矩阵 → bbox → rebase → commandsToD
        let cmds = parsePathCommands(rawD);
        cmds = bakeMatrix(cmds, m);
        const bbox = commandsBBox(cmds);
        cmds = rebaseToOrigin(cmds, bbox.x, bbox.y);
        const d = commandsToD(cmds);

        // Step 4: fill / stroke / fillRule / opacity
        const fill = mapFill(shape);
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
