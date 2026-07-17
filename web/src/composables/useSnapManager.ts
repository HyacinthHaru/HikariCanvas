// 智能对齐（smart guides）+ distribute & resize 扩展。
//
// 输入：拖动中元素的 bbox（左上 + 宽高）+ raw 位置 + 需排除 ID 集合。
// 输出：snap 后的 (x, y) + 命中的对齐轴 X / Y 数组（供 visualizer 画红线）+
// 可选的 distribute 间距标注（供 visualizer 画绿色间距段）。
//
// 候选轴来源（按 ui store 开关启用）：
//   1. canvas：left=0 / centerX=widthPx/2 / right=widthPx；top / centerY / bottom 同理；四角隐含在边的交集中
//   2. element：其他可见元素的 left / centerX / right + top / centerY / bottom
//   3. grid：rawX / rawY 附近的 floor / ceil 倍数（仅当 gridSize > 0）
//   4. distribute：两侧最近邻 A、C 决定"AB == BC"的理想中点；横纵向各算一次
//
// dragged 锚点：left = rawX，centerX = rawX + w/2，right = rawX + w（Y 同理）。
// 匹配距离阈值 ui.snapThreshold（默认 8px）。每个轴方向取最近 candidate 应用。
//
// distribute 限制（v2 简化）：仅匹配「两侧最近邻」，不做"任意三元素均分"。

import type { useProjectStore } from '@/stores/project';
import type { useUiStore } from '@/stores/ui';
import type { Element } from '@/types/protocol';

/** distribute 命中时的间距段标注（visualizer 画绿色线段 + 距离数字）。 */
export interface EqualGapX {
    /** A 元素的 right 边 X 坐标 */
    aRight: number;
    /** dragged B 的 left X 坐标 */
    bLeft: number;
    /** dragged B 的 right X 坐标 */
    bRight: number;
    /** C 元素的 left X 坐标 */
    cLeft: number;
    /** 两段共同的 Y 坐标（取 dragged B 的 centerY，仅用于绘制水平间距线）。 */
    yCenter: number;
}

export interface EqualGapY {
    aBottom: number;
    bTop: number;
    bBottom: number;
    cTop: number;
    xCenter: number;
}

export interface SnapHints {
    /** snap 后 X；若无命中 = rawX */
    snappedX: number;
    /** snap 后 Y；若无命中 = rawY */
    snappedY: number;
    /** 命中的 X 轴坐标列表（供 visualizer 画竖向红线）。空数组 = 未 snap。 */
    activeXAxes: number[];
    /** 命中的 Y 轴坐标列表（供 visualizer 画横向红线）。空数组 = 未 snap。 */
    activeYAxes: number[];
    /** 横向 distribute 命中时的标注；未命中 = undefined。 */
    equalGapX?: EqualGapX;
    /** 纵向 distribute 命中时的标注；未命中 = undefined。 */
    equalGapY?: EqualGapY;
}

export interface UseSnapManagerOpts {
    project: ReturnType<typeof useProjectStore>;
    ui: ReturnType<typeof useUiStore>;
    /** 可选 bypass：返 true 时本次 snap 跳过（如 shift 键临时禁用）。 */
    bypass?: () => boolean;
}

export interface SnapManager {
    snap(rawX: number, rawY: number, w: number, h: number, excludeIds: Set<string>): SnapHints;
    /**
     * 单边吸附——resize 时只对正在移动的那条边做 snap，避免静止锚点的 bestDelta
     * 被误用到动的边上（例如拖右手柄时 left 近候选轴的 delta 意外加到 width）。
     *
     * @param rawEdge   正在移动的边的当前绝对坐标（X 方向传 left 或 right；Y 方向传 top 或 bottom）
     * @param axis      'x' 或 'y'（决定用哪组候选轴）
     * @param excludeIds 排除自身（同 snap）
     * @returns delta（snap 后坐标 - rawEdge）；bypass / 无命中返 0
     */
    snapEdge(rawEdge: number, axis: 'x' | 'y', excludeIds: Set<string>): number;
}

interface CollectedElement {
    id: string;
    left: number;
    right: number;
    top: number;
    bottom: number;
    cx: number;
    cy: number;
}

export function useSnapManager(opts: UseSnapManagerOpts): SnapManager {
    const { project, ui } = opts;

    function noSnap(rawX: number, rawY: number): SnapHints {
        return { snappedX: rawX, snappedY: rawY, activeXAxes: [], activeYAxes: [] };
    }

    function collectElements(excludeIds: Set<string>): CollectedElement[] {
        const out: CollectedElement[] = [];
        const layers = project.state?.layers;
        if (!layers) return out;
        for (const layer of layers) {
            if (!layer.visible) continue;
            for (const el of layer.elements as Element[]) {
                if (excludeIds.has(el.id)) continue;
                if (!el.visible) continue;
                out.push({
                    id: el.id,
                    left: el.x,
                    right: el.x + el.w,
                    top: el.y,
                    bottom: el.y + el.h,
                    cx: el.x + el.w / 2,
                    cy: el.y + el.h / 2,
                });
            }
        }
        return out;
    }

    function collectElementAxes(elements: CollectedElement[]): { xs: number[]; ys: number[] } {
        const xs: number[] = [];
        const ys: number[] = [];
        for (const el of elements) {
            xs.push(el.left, el.cx, el.right);
            ys.push(el.top, el.cy, el.bottom);
        }
        return { xs, ys };
    }

    function collectCanvasAxes(): { xs: number[]; ys: number[] } {
        const wPx = project.canvasPixelWidth || 0;
        const hPx = project.canvasPixelHeight || 0;
        return {
            xs: [0, wPx / 2, wPx],
            ys: [0, hPx / 2, hPx],
        };
    }

    function pickGridCandidates(raw: number, gridSize: number): number[] {
        if (gridSize <= 0) return [];
        const lo = Math.floor(raw / gridSize) * gridSize;
        const hi = lo + gridSize;
        return [lo, hi];
    }

    /**
     * 对一个方向（X 或 Y），在 candidates 中找出与三个 dragged 锚点（left/center/right）
     * 最接近的 candidate。返回 delta（snap 后位置 - raw）+ 命中的 candidate 列表。
     *
     * 多个锚点同时命中同一 delta 时（如 element left 与另一 element right 距离都是 3）会被一起记入
     * activeAxes，让 visualizer 之后能多画几条线。
     */
    function snapAxis(
        raw: number,
        size: number,
        candidates: number[],
        threshold: number,
    ): { delta: number; axes: number[] } {
        // dragged 三锚点：left = raw, center = raw + size/2, right = raw + size
        const anchors = [raw, raw + size / 2, raw + size];
        let bestDist = threshold + 1;
        let bestDelta = 0;
        // 第一遍：找最近距离
        for (const anchor of anchors) {
            for (const cand of candidates) {
                const d = cand - anchor;
                const abs = Math.abs(d);
                if (abs < bestDist) {
                    bestDist = abs;
                    bestDelta = d;
                }
            }
        }
        if (bestDist > threshold) return { delta: 0, axes: [] };
        // 第二遍：收集所有"应用 bestDelta 后会精确命中"的 candidate 作为 active axis
        const axes: number[] = [];
        const EPS = 0.5;
        for (const anchor of anchors) {
            for (const cand of candidates) {
                if (Math.abs(cand - (anchor + bestDelta)) <= EPS) {
                    if (!axes.includes(cand)) axes.push(cand);
                }
            }
        }
        return { delta: bestDelta, axes };
    }

    /**
     * distribute 横向：找 dragged 左右两侧最近邻 A / C；
     * 若理想中点（B.left = A.right + ((C.left - A.right) - w) / 2）距 rawX 落在阈值内 → snap。
     *
     * 返回 delta（== idealLeft - rawX）+ 间距段标注（用 snap 后位置标注，让 visualizer 画线对齐）。
     */
    function findEqualGapX(
        rawX: number,
        rawY: number,
        w: number,
        h: number,
        elements: CollectedElement[],
        threshold: number,
    ): { delta: number; hint: EqualGapX } | null {
        const draggedLeft = rawX;
        const draggedRight = rawX + w;
        const cy = rawY + h / 2;
        // A = 左侧最近邻：right < draggedLeft 中 right 最大者
        let aRight = -Infinity;
        let aHit: CollectedElement | null = null;
        // C = 右侧最近邻：left > draggedRight 中 left 最小者
        let cLeft = Infinity;
        let cHit: CollectedElement | null = null;
        for (const el of elements) {
            if (el.right <= draggedLeft && el.right > aRight) {
                aRight = el.right;
                aHit = el;
            }
            if (el.left >= draggedRight && el.left < cLeft) {
                cLeft = el.left;
                cHit = el;
            }
        }
        if (!aHit || !cHit) return null;
        const span = cHit.left - aHit.right;
        if (span < w) return null;  // dragged 放不下两段相等间距
        const idealLeft = aHit.right + (span - w) / 2;
        const delta = idealLeft - rawX;
        if (Math.abs(delta) > threshold) return null;
        const snappedLeft = idealLeft;
        const snappedRight = snappedLeft + w;
        return {
            delta,
            hint: {
                aRight: aHit.right,
                bLeft: snappedLeft,
                bRight: snappedRight,
                cLeft: cHit.left,
                yCenter: cy,
            },
        };
    }

    /** 纵向 distribute，与 findEqualGapX 镜像。 */
    function findEqualGapY(
        rawX: number,
        rawY: number,
        w: number,
        h: number,
        elements: CollectedElement[],
        threshold: number,
    ): { delta: number; hint: EqualGapY } | null {
        const draggedTop = rawY;
        const draggedBottom = rawY + h;
        const cx = rawX + w / 2;
        let aBottom = -Infinity;
        let aHit: CollectedElement | null = null;
        let cTop = Infinity;
        let cHit: CollectedElement | null = null;
        for (const el of elements) {
            if (el.bottom <= draggedTop && el.bottom > aBottom) {
                aBottom = el.bottom;
                aHit = el;
            }
            if (el.top >= draggedBottom && el.top < cTop) {
                cTop = el.top;
                cHit = el;
            }
        }
        if (!aHit || !cHit) return null;
        const span = cHit.top - aHit.bottom;
        if (span < h) return null;
        const idealTop = aHit.bottom + (span - h) / 2;
        const delta = idealTop - rawY;
        if (Math.abs(delta) > threshold) return null;
        const snappedTop = idealTop;
        const snappedBottom = snappedTop + h;
        return {
            delta,
            hint: {
                aBottom: aHit.bottom,
                bTop: snappedTop,
                bBottom: snappedBottom,
                cTop: cHit.top,
                xCenter: cx,
            },
        };
    }

    function snap(
        rawX: number,
        rawY: number,
        w: number,
        h: number,
        excludeIds: Set<string>,
    ): SnapHints {
        if (!ui.snapEnabled) return noSnap(rawX, rawY);
        if (opts.bypass && opts.bypass()) return noSnap(rawX, rawY);

        const threshold = ui.snapThreshold;
        const xs: number[] = [];
        const ys: number[] = [];

        // 收集 element 一次，element axes + distribute 共享
        const otherElements = ui.snapToElement || ui.snapToDistribute
            ? collectElements(excludeIds)
            : [];

        if (ui.snapToCanvas) {
            const c = collectCanvasAxes();
            xs.push(...c.xs);
            ys.push(...c.ys);
        }
        if (ui.snapToElement) {
            const e = collectElementAxes(otherElements);
            xs.push(...e.xs);
            ys.push(...e.ys);
        }
        if (ui.snapToGrid) {
            const gridSize = project.state?.canvas.gridSize ?? 0;
            if (gridSize > 0) {
                // grid 候选轴覆盖 dragged 的三锚点附近——分别为 left / center / right 各取一组邻近格线
                for (const anchor of [rawX, rawX + w / 2, rawX + w]) {
                    xs.push(...pickGridCandidates(anchor, gridSize));
                }
                for (const anchor of [rawY, rawY + h / 2, rawY + h]) {
                    ys.push(...pickGridCandidates(anchor, gridSize));
                }
            }
        }

        const rx = (xs.length > 0)
            ? snapAxis(rawX, w, xs, threshold)
            : { delta: 0, axes: [] as number[] };
        const ry = (ys.length > 0)
            ? snapAxis(rawY, h, ys, threshold)
            : { delta: 0, axes: [] as number[] };

        // distribute：与 axis snap 同方向竞争——若 axis 已命中（delta != 0 或 axes 非空），
        // distribute 不再覆盖（避免两种 snap 互相抢夺导致跳变）。
        let equalGapX: EqualGapX | undefined;
        let equalGapY: EqualGapY | undefined;
        let dxDelta = rx.delta;
        let dyDelta = ry.delta;
        if (ui.snapToDistribute && otherElements.length >= 2) {
            if (rx.axes.length === 0) {
                const eg = findEqualGapX(rawX, rawY, w, h, otherElements, threshold);
                if (eg) {
                    dxDelta = eg.delta;
                    equalGapX = eg.hint;
                }
            }
            if (ry.axes.length === 0) {
                const eg = findEqualGapY(rawX, rawY, w, h, otherElements, threshold);
                if (eg) {
                    dyDelta = eg.delta;
                    equalGapY = eg.hint;
                }
            }
        }

        const noHit = rx.axes.length === 0 && ry.axes.length === 0
            && !equalGapX && !equalGapY;
        if (noHit) return noSnap(rawX, rawY);

        return {
            snappedX: rawX + dxDelta,
            snappedY: rawY + dyDelta,
            activeXAxes: rx.axes,
            activeYAxes: ry.axes,
            equalGapX,
            equalGapY,
        };
    }

    /**
     * 单边吸附实现。
     *
     * 只对 rawEdge 一个锚点（而非 left/center/right 三锚点）扫描 candidate，拿最近 delta。
     * 候选轴来源与 snap() 相同（canvas / element / grid），但 distribute 不适用于单边 resize 故跳过。
     */
    function snapEdge(rawEdge: number, axis: 'x' | 'y', excludeIds: Set<string>): number {
        if (!ui.snapEnabled) return 0;
        if (opts.bypass && opts.bypass()) return 0;

        const threshold = ui.snapThreshold;
        const coords: number[] = [];

        if (ui.snapToCanvas) {
            const c = collectCanvasAxes();
            coords.push(...(axis === 'x' ? c.xs : c.ys));
        }
        if (ui.snapToElement) {
            const otherElements = collectElements(excludeIds);
            const e = collectElementAxes(otherElements);
            coords.push(...(axis === 'x' ? e.xs : e.ys));
        }
        if (ui.snapToGrid) {
            const gridSize = project.state?.canvas.gridSize ?? 0;
            coords.push(...pickGridCandidates(rawEdge, gridSize));
        }

        if (coords.length === 0) return 0;

        // 单锚点扫描：找最近 candidate
        let bestDist = threshold + 1;
        let bestDelta = 0;
        for (const cand of coords) {
            const d = cand - rawEdge;
            const abs = Math.abs(d);
            if (abs < bestDist) {
                bestDist = abs;
                bestDelta = d;
            }
        }
        return bestDist <= threshold ? bestDelta : 0;
    }

    return { snap, snapEdge };
}
