/**
 * svgToElements.ts
 *
 * SVG 文本 → ElementDraft[]。
 * 纯函数：无副作用，无 WS 调用，无 store 依赖。
 *
 * 处理 path / rect / circle / ellipse / line / polyline / polygon（返回 type='path'）
 * 与内嵌 <image>（data: URL）。
 */

import { parseSvg } from './svgParse';
import { complexityGuard } from './svgSecurity';
import { shapeToPathD } from './shapesToPath';
import { parsePathCommands, commandsToD } from './normalizeD';
import { bakeMatrix, commandsBBox, rebaseToOrigin } from './bakePath';
import {
    mapOpacity,
    getPresentationAttr,
    resolveFillValue,
    resolveStrokeValue,
    SVG_DEFAULT_CURRENT_COLOR,
    SVG_DEFAULT_FILL_COLOR,
} from './fillMap';
import { parseTransform, mul, IDENTITY, applyPoint } from './transform';
import type { Mat } from './transform';
import type { Fill, Stroke } from '../../types/protocol';

export interface ElementDraft {
    type: 'path' | 'image';
    props: Record<string, unknown>;
    /** 嵌入位图的 data URL，仅 type='image' 时存在 */
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
 * 沿祖先链（元素自身 → 父 → … → svg 根）找第一个写了 prop 的节点，返回它的值。
 * 都没写返回 undefined。
 *
 * <p>`fill` / `stroke` / `stroke-width` / `fill-rule` / `color` 在 SVG 里都是<b>可继承</b>属性，
 * 而现实中的图标 SVG 极常把它们写在外层 &lt;g&gt; 上、叶子 path 一个样式属性都不带。
 * 只读叶子属性会把整组图形判成"没样式"丢掉——这就是导入产出 0 元素的成因之一。</p>
 */
function inheritedAttr(shape: Element, root: Element, prop: string): string | undefined {
    let cur: Element | null = shape;
    while (cur) {
        const v = getPresentationAttr(cur, prop);
        if (v !== undefined) return v;
        if (cur === root) break;
        cur = cur.parentElement;
    }
    return undefined;
}

/**
 * 按 SVG 规则定出这个形状最终的 fill。
 *
 * <ul>
 *   <li>祖先链上一处都没写 fill → 规范初始值 <b>黑色</b>（不是"无填充"）</li>
 *   <li>写了 `none` / `transparent` → 无填充</li>
 *   <li>写了但读不懂（坏 hex、引用不到的渐变）→ 同样回落黑色，别让形状凭空消失</li>
 * </ul>
 */
function resolveShapeFill(shape: Element, root: Element): Fill | undefined {
    const raw = inheritedAttr(shape, root, 'fill');
    if (raw === undefined) return { type: 'solid', color: SVG_DEFAULT_FILL_COLOR };
    const currentColor = inheritedAttr(shape, root, 'color') ?? SVG_DEFAULT_CURRENT_COLOR;
    const res = resolveFillValue(raw, root, currentColor);
    if (res.kind === 'fill') return res.fill;
    if (res.kind === 'none') return undefined;
    return { type: 'solid', color: SVG_DEFAULT_FILL_COLOR };
}

/** 按 SVG 规则定出这个形状最终的 stroke。stroke 初始值就是 none，故缺省 / 读不懂都返回无描边。 */
function resolveShapeStroke(shape: Element, root: Element): Stroke | undefined {
    const raw = inheritedAttr(shape, root, 'stroke');
    if (raw === undefined) return undefined;
    const currentColor = inheritedAttr(shape, root, 'color') ?? SVG_DEFAULT_CURRENT_COLOR;
    return resolveStrokeValue(raw, inheritedAttr(shape, root, 'stroke-width'), currentColor);
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

    // 构建 viewBox 缩放矩阵（仅当 viewBox + targetWidth/targetHeight 均存在时）
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
        // 处理 <image> 标签（嵌入位图）
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

        // Step 4: fill / stroke / fillRule / opacity（前三个都按祖先链继承 + 规范默认值解析）
        const rootEl = doc.root as unknown as Element;
        const fill = resolveShapeFill(shape, rootEl);
        const stroke = resolveShapeStroke(shape, rootEl);
        const fillRuleRaw = inheritedAttr(shape, rootEl, 'fill-rule');
        const fillRule = fillRuleRaw === 'nonzero' || fillRuleRaw === 'evenodd' ? fillRuleRaw : undefined;
        const opacity = mapOpacity(shape);

        // fill 和 stroke 都为空 → 跳过。走到这里只剩「显式 fill='none' 且没描边」一种情况，
        // 即用户确实画了个看不见的形状；缺省 / 值读不懂都已在上面回落成黑色填充。
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
