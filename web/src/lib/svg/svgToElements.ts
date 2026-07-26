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
    matrixScale,
    resolveFillValue,
    resolveStrokeValue,
    SVG_DEFAULT_CURRENT_COLOR,
    SVG_DEFAULT_FILL_COLOR,
} from './fillMap';
import { parseTransform, mul, IDENTITY, applyPoint } from './transform';
import type { Mat } from './transform';
import type { Fill, Stop, Stroke } from '../../types/protocol';

export interface ElementDraft {
    type: 'path' | 'image';
    props: Record<string, unknown>;
    /** 嵌入位图的 data URL，仅 type='image' 时存在 */
    dataUrl?: string;
}

/**
 * 服务端对 element.add 的硬约束，抄自后端 `ElementValidator` / `PathDValidator` / `FillValidator`。
 *
 * <p>前端不预检的话，超限的元素在服务端一律 INVALID_PAYLOAD 静默丢掉，而导入对话框还
 * 报"成功导入 N 个"——用户看到的数字和画布上的东西对不上。改后端阈值时这里要跟着改。</p>
 */
export const BACKEND_LIMITS = {
    /** PathDValidator.MAX_LEN */
    maxPathDLen: 4096,
    /** ElementValidator.MAX_COORD（x/y 允许负，取绝对值比） */
    maxCoord: 10_000,
    /** ElementValidator.MAX_DIM（w/h 必须 ≥1） */
    maxDim: 10_000,
    /** ElementValidator.MAX_STROKE_WIDTH */
    maxStrokeWidth: 128,
    /** FillValidator.MIN_STOPS / MAX_STOPS */
    minStops: 2,
    maxStops: 8,
    /** FillValidator.validateRadius：r ∈ (0, 2] */
    maxRadialR: 2,
} as const;

/** 出口预检把某个形状挡下来时的原因（给用户看的汇总用）。 */
export type DropReason = 'PATH_TOO_LONG' | 'OUT_OF_RANGE' | 'EMPTY_SHAPE';

/** 被挡下来的形状数量统计。{@link svgToElementsDetailed} 返回。 */
export type DropCounts = Partial<Record<DropReason, number>>;

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

/**
 * 按 SVG 规则定出这个形状最终的 stroke。stroke 初始值就是 none，故缺省 / 读不懂都返回无描边。
 *
 * @param scale 形状身上累计的变换倍率；线宽要跟着坐标一起缩放，否则
 *        `<g transform="scale(4)">` 里的 1px 线导进来还是 1px，比原图细四倍。
 */
function resolveShapeStroke(shape: Element, root: Element, scale: number): Stroke | undefined {
    const raw = inheritedAttr(shape, root, 'stroke');
    if (raw === undefined) return undefined;
    const currentColor = inheritedAttr(shape, root, 'color') ?? SVG_DEFAULT_CURRENT_COLOR;
    return resolveStrokeValue(raw, inheritedAttr(shape, root, 'stroke-width'), currentColor, scale);
}

/**
 * 把渐变收敛到服务端接受的范围（停止点 2..8、位置 [0,1] 且非递减、半径 (0,2]、角度 [0,360)）。
 * 收不回来（如停止点不足 2 个）就返回 undefined，交给调用方回落。
 */
function sanitizeFill(fill: Fill | undefined): Fill | undefined {
    if (!fill) return undefined;
    if (fill.type === 'solid') return fill;

    const clamped: Stop[] = [];
    let last = 0;
    for (const s of fill.stops ?? []) {
        if (!s || typeof s.color !== 'string') continue;
        const p = Number.isFinite(s.position) ? Math.min(1, Math.max(0, s.position)) : last;
        const pos = Math.max(p, last);   // 非递减
        last = pos;
        clamped.push({ position: pos, color: s.color });
        if (clamped.length >= BACKEND_LIMITS.maxStops) break;
    }
    if (clamped.length < BACKEND_LIMITS.minStops) return undefined;

    if (fill.type === 'linear') {
        const a = Number.isFinite(fill.angle) ? ((fill.angle % 360) + 360) % 360 : 0;
        return { type: 'linear', angle: a, stops: clamped };
    }
    const unit = (v: number, def: number): number =>
        (Number.isFinite(v) ? Math.min(1, Math.max(0, v)) : def);
    const r = Number.isFinite(fill.r) && fill.r > 0
        ? Math.min(BACKEND_LIMITS.maxRadialR, fill.r)
        : 1;
    return { type: 'radial', cx: unit(fill.cx, 0.5), cy: unit(fill.cy, 0.5), r, stops: clamped };
}

/**
 * 算 viewBox → 画布坐标的映射矩阵（契约见 docs/rendering.md §11.2）。
 *
 * <ul>
 *   <li>导入方指定了目标宽高 → 按目标尺寸缩放</li>
 *   <li>没指定但 SVG 自己声明了像素 width/height → 以声明尺寸为准。
 *       `viewBox="0 0 24 24" width="96"` 这种图标就该导成 96px，而不是 24px</li>
 *   <li>两者都没有 → 不缩放，只把 viewBox 原点平移到 (0,0)。
 *       minX/minY 为负时不平移的话元素坐标是负的，直接落到画布外看不见</li>
 * </ul>
 *
 * viewBox 宽高为 0 / 负 / 非有限时视为没写（否则除零得 Infinity，整张图坐标全废）。
 */
function buildViewBoxMatrix(
    viewBox: readonly [number, number, number, number] | null,
    declaredW: number | null,
    declaredH: number | null,
    opts?: { targetWidth?: number; targetHeight?: number },
): Mat {
    if (!viewBox) return [...IDENTITY] as Mat;
    const [minX, minY, vbW, vbH] = viewBox;
    if (!(Number.isFinite(vbW) && Number.isFinite(vbH) && vbW > 0 && vbH > 0)) {
        return [...IDENTITY] as Mat;
    }
    const positive = (v: number | null | undefined): number | null =>
        (typeof v === 'number' && Number.isFinite(v) && v > 0 ? v : null);
    const tw = positive(opts?.targetWidth) ?? positive(declaredW);
    const th = positive(opts?.targetHeight) ?? positive(declaredH);
    if (tw === null || th === null) {
        // 不缩放，只归零原点
        return [1, 0, 0, 1, -minX, -minY];
    }
    const sx = tw / vbW;
    const sy = th / vbH;
    // viewBoxMat = translate(-minX*sx, -minY*sy) ∘ scale(sx, sy)
    // 即：先 scale，再 translate（translate 作为外层 m1）
    const scaleMat: Mat = [sx, 0, 0, sy, 0, 0];
    const translateMat: Mat = [1, 0, 0, 1, -minX * sx, -minY * sy];
    return mul(translateMat, scaleMat);
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
    return svgToElementsDetailed(svg, opts).drafts;
}

/**
 * 与 {@link svgToElements} 相同，另外返回"哪些形状被出口预检挡下来了"。
 *
 * <p>出口预检是必需的：服务端对 d 串长度、宽高下限、线宽、渐变停止点都有硬约束，
 * 不合规的元素一律 INVALID_PAYLOAD 丢掉，前端却照样把它算进"成功导入 N 个"。
 * 能收敛的（线宽取整、渐变越界、d 串降精度）就地收敛，实在不行才丢并计数。</p>
 */
export function svgToElementsDetailed(
    svg: string,
    opts?: { maxBytes?: number; targetWidth?: number; targetHeight?: number },
): { drafts: ElementDraft[]; dropped: DropCounts } {
    const doc = parseSvg(svg, opts?.maxBytes);
    complexityGuard(doc.shapes);
    const drafts: ElementDraft[] = [];
    const dropped: DropCounts = {};
    const drop = (reason: DropReason): void => { dropped[reason] = (dropped[reason] ?? 0) + 1; };

    const viewBoxMat = buildViewBoxMatrix(doc.viewBox, doc.width, doc.height, opts);

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
            const box = normalizeBox(bx, by, bw, bh);
            if (!box) { drop('OUT_OF_RANGE'); continue; }
            drafts.push({
                type: 'image',
                // w/h 为 0 表示 <image> 没写尺寸；上传拿到真实像素尺寸后由调用方补齐
                props: { x: box.x, y: box.y, w: bw > 0 ? box.w : 0, h: bh > 0 ? box.h : 0 },
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
        // 服务端对 d 串有 4096 字符上限，超了整条元素被拒。先降小数精度试着塞进去
        // （画布是地图像素，小数点后两位以下肉眼无差），实在塞不下才丢。
        const d = fitPathD(cmds);
        if (d === null) { drop('PATH_TOO_LONG'); continue; }

        // Step 4: fill / stroke / fillRule / opacity（前三个都按祖先链继承 + 规范默认值解析）
        const rootEl = doc.root as unknown as Element;
        const fill = sanitizeFill(resolveShapeFill(shape, rootEl));
        const rawStroke = resolveShapeStroke(shape, rootEl, matrixScale(m));
        const fillRuleRaw = inheritedAttr(shape, rootEl, 'fill-rule');
        const fillRule = fillRuleRaw === 'nonzero' || fillRuleRaw === 'evenodd' ? fillRuleRaw : undefined;
        const opacity = mapOpacity(shape);

        // 线宽落到服务端接受的整数区间。没有填充时至少留 1px，否则服务端按
        // "既没填充又没描边"整条拒掉——0.5px 的细线本来就该按 1px 画。
        const stroke = normalizeStroke(rawStroke, fill !== undefined);

        // fill 和 stroke 都为空 → 跳过。走到这里只剩「显式 fill='none' 且没描边」一种情况，
        // 即用户确实画了个看不见的形状；缺省 / 值读不懂都已在上面回落成黑色填充。
        if (!fill && !stroke) { drop('EMPTY_SHAPE'); continue; }

        // 宽高必须 ≥1：水平线 / 零高细条的 bbox 高度是 0，服务端 validateDim 直接拒。
        const box = normalizeBox(bbox.x, bbox.y, bbox.w, bbox.h);
        if (!box) { drop('OUT_OF_RANGE'); continue; }

        // Step 5: 组装 draft props（只放有值的字段）
        const props: Record<string, unknown> = {
            x: box.x,
            y: box.y,
            w: box.w,
            h: box.h,
            d,
            ...(fill    ? { fill }     : {}),
            ...(stroke  ? { stroke }   : {}),
            ...(fillRule ? { fillRule } : {}),
            ...(opacity != null ? { opacity } : {}),
        };

        drafts.push({ type: 'path', props });
    }

    return { drafts, dropped };
}

/** d 串降精度重出，直到塞进服务端的长度上限；都塞不下返回 null。 */
function fitPathD(cmds: ReturnType<typeof parsePathCommands>): string | null {
    for (const precision of [6, 2, 1, 0]) {
        const d = commandsToD(cmds, precision);
        if (d.length <= BACKEND_LIMITS.maxPathDLen) {
            // 坐标里混进 NaN / Infinity 时服务端必拒；这里当作没法用
            return /NaN|Infinity/.test(d) ? null : d;
        }
    }
    return null;
}

/**
 * bbox 收敛成服务端接受的整数矩形：x/y 取整且 |v| ≤ 10000，w/h 取整且 ≥1、≤10000。
 * 收不回来（坐标非有限 / 超范围 / 尺寸超上限）返回 null。
 */
function normalizeBox(
    x: number, y: number, w: number, h: number,
): { x: number; y: number; w: number; h: number } | null {
    if (![x, y, w, h].every(Number.isFinite)) return null;
    const rx = Math.round(x);
    const ry = Math.round(y);
    if (Math.abs(rx) > BACKEND_LIMITS.maxCoord || Math.abs(ry) > BACKEND_LIMITS.maxCoord) return null;
    const rw = Math.max(1, Math.round(w));
    const rh = Math.max(1, Math.round(h));
    if (rw > BACKEND_LIMITS.maxDim || rh > BACKEND_LIMITS.maxDim) return null;
    return { x: rx, y: ry, w: rw, h: rh };
}

/**
 * 线宽收敛：取整 + 钳到 [0, 128]。
 * 没有填充时至少给 1px —— 服务端要求"要么有填充、要么描边非 0"，
 * 而 `stroke-width="0.5"` 取整成 0 会让整条元素被拒。
 */
function normalizeStroke(stroke: Stroke | undefined, hasFill: boolean): Stroke | undefined {
    if (!stroke) return undefined;
    const raw = Number.isFinite(stroke.width) ? stroke.width : 1;
    let w = Math.min(BACKEND_LIMITS.maxStrokeWidth, Math.max(0, Math.round(raw)));
    if (w === 0) {
        if (hasFill) return undefined;   // 有填充就干脆不要这条看不见的描边
        w = 1;
    }
    return { color: stroke.color, width: w };
}
