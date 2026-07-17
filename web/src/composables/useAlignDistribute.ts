/**
 * 对齐 / 分布工具。
 *
 * <p>纯计算 composable —— 输入选中 element 数组 + 操作类型，输出每个 element 的新 (x, y) patch。
 * 不直接 dispatch WS op，调用方（AlignDistributeBar）负责 element.update 批量发送。</p>
 *
 * <p><b>设计要点：</b></p>
 * <ul>
 *   <li>locked element 跳过（同 element-locked 在拖动 / transformer 的处理一致）</li>
 *   <li>distribute 至少需 3 element；2 element 不做（无中间项可分布）</li>
 *   <li>distribute 按"中心点"等间距，与 Figma 行为一致；首尾保持不动</li>
 *   <li>align 仅改 x 或 y 单维；不改 w/h/rotation</li>
 * </ul>
 */

import type { Element } from '@/types/protocol';

export type AlignAxis = 'x' | 'y';
export type AlignMode = 'start' | 'center' | 'end' | 'distribute-equal';

export interface ElementPatch {
    id: string;
    x?: number;
    y?: number;
}

/**
 * 计算给定操作的 patch 数组。
 *
 * @param elements 候选元素（caller 已过滤 visible / 已确认是多选场景）。会 in-place 跳过 locked。
 * @param axis     'x' = 水平方向（左/中/右、distribute-horizontal）；'y' = 垂直方向
 * @param mode     'start' = left/top；'center' = h/v center；'end' = right/bottom；'distribute-equal'
 * @returns        仅含发生变化的 element 的 patch；空数组 = 无需 dispatch
 */
export function computeAlignDistributePatches(
    elements: Element[],
    axis: AlignAxis,
    mode: AlignMode,
): ElementPatch[] {
    const writable = elements.filter((e) => !e.locked);
    if (writable.length < 2) return []; // 单选无意义

    if (mode === 'distribute-equal') {
        return computeDistributePatches(writable, axis);
    }
    return computeAlignPatches(writable, axis, mode);
}

/**
 * align 公式：
 *   - start  → 所有元素的 minEdge 对齐到 min(minEdge)
 *   - center → 所有元素中心对齐到 avg(center)
 *   - end    → 所有元素的 maxEdge 对齐到 max(maxEdge)
 *
 * <p>注意"center = avg(centers)"是行业惯例（与 Figma / PS 一致），不是 bbox center。</p>
 */
function computeAlignPatches(
    elements: Element[],
    axis: AlignAxis,
    mode: Exclude<AlignMode, 'distribute-equal'>,
): ElementPatch[] {
    const sizeField = axis === 'x' ? 'w' : 'h';
    const coordField = axis === 'x' ? 'x' : 'y';

    let target: number;
    if (mode === 'start') {
        target = Math.min(...elements.map((e) => e[coordField]));
    } else if (mode === 'end') {
        target = Math.max(...elements.map((e) => e[coordField] + e[sizeField]));
    } else {
        // center
        const centers = elements.map((e) => e[coordField] + e[sizeField] / 2);
        target = centers.reduce((a, b) => a + b, 0) / centers.length;
    }

    const out: ElementPatch[] = [];
    for (const e of elements) {
        let newCoord: number;
        if (mode === 'start') {
            newCoord = target;
        } else if (mode === 'end') {
            newCoord = target - e[sizeField];
        } else {
            newCoord = target - e[sizeField] / 2;
        }
        // 整数化（与 element.transform 的 round 行为一致；亚像素无意义）
        newCoord = Math.round(newCoord);
        if (newCoord !== e[coordField]) {
            out.push({ id: e.id, [coordField]: newCoord } as ElementPatch);
        }
    }
    return out;
}

/**
 * distribute-equal 公式（≥ 3 元素）：
 *   1. 按 axis 方向的 center 排序
 *   2. 首尾元素不动
 *   3. 中间元素按"中心点等间距"重新分布在 [firstCenter, lastCenter] 之间
 *
 * <p>少于 3 元素直接返空（UI 也应 disable 按钮）。</p>
 */
function computeDistributePatches(elements: Element[], axis: AlignAxis): ElementPatch[] {
    if (elements.length < 3) return [];

    const sizeField = axis === 'x' ? 'w' : 'h';
    const coordField = axis === 'x' ? 'x' : 'y';

    // sort by center
    const sorted = [...elements].sort((a, b) => {
        const ca = a[coordField] + a[sizeField] / 2;
        const cb = b[coordField] + b[sizeField] / 2;
        return ca - cb;
    });

    const firstCenter = sorted[0][coordField] + sorted[0][sizeField] / 2;
    const lastCenter =
        sorted[sorted.length - 1][coordField] + sorted[sorted.length - 1][sizeField] / 2;
    const step = (lastCenter - firstCenter) / (sorted.length - 1);

    const out: ElementPatch[] = [];
    for (let i = 1; i < sorted.length - 1; i++) {
        const el = sorted[i];
        const targetCenter = firstCenter + step * i;
        const newCoord = Math.round(targetCenter - el[sizeField] / 2);
        if (newCoord !== el[coordField]) {
            out.push({ id: el.id, [coordField]: newCoord } as ElementPatch);
        }
    }
    return out;
}

/**
 * 统计被跳过的 locked 元素数量，供 UI 提示"跳过了 N 个锁定元素"。
 * 不是 patch 计算的副产物，UI 想用就单独调一次。
 */
export function countLockedSkipped(elements: Element[]): number {
    let n = 0;
    for (const e of elements) if (e.locked) n++;
    return n;
}
