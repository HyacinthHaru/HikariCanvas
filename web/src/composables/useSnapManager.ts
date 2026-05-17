// M17 P3 / F3 智能对齐 v1（smart guides）。
//
// 输入：拖动中元素的 bbox（左上 + 宽高）+ raw 位置 + 需排除 ID 集合。
// 输出：snap 后的 (x, y) + 命中的对齐轴 X / Y 数组（供 M17.4 visualizer 画红线）。
//
// 候选轴来源（按 ui store 开关启用）：
//   1. canvas：left=0 / centerX=widthPx/2 / right=widthPx；top / centerY / bottom 同理；四角隐含在边的交集中
//   2. element：其他可见元素的 left / centerX / right + top / centerY / bottom
//   3. grid：rawX / rawY 附近的 floor / ceil 倍数（仅当 gridSize > 0）
//
// dragged 锚点：left = rawX，centerX = rawX + w/2，right = rawX + w（Y 同理）。
// 匹配距离阈值 ui.snapThreshold（默认 8px）。每个轴方向取最近 candidate 应用。
//
// v1 仅打底：visualizer / popover / distribute / resize snap 留 M17.4。

import type { useProjectStore } from '@/stores/project';
import type { useUiStore } from '@/stores/ui';
import type { Element } from '@/types/protocol';

export interface SnapHints {
    /** snap 后 X；若无命中 = rawX */
    snappedX: number;
    /** snap 后 Y；若无命中 = rawY */
    snappedY: number;
    /** 命中的 X 轴坐标列表（供 visualizer 画竖向红线）。空数组 = 未 snap。 */
    activeXAxes: number[];
    /** 命中的 Y 轴坐标列表（供 visualizer 画横向红线）。空数组 = 未 snap。 */
    activeYAxes: number[];
}

export interface UseSnapManagerOpts {
    project: ReturnType<typeof useProjectStore>;
    ui: ReturnType<typeof useUiStore>;
    /** 可选 bypass：返 true 时本次 snap 跳过（如 shift 键临时禁用）。 */
    bypass?: () => boolean;
}

export interface SnapManager {
    snap(rawX: number, rawY: number, w: number, h: number, excludeIds: Set<string>): SnapHints;
}

export function useSnapManager(opts: UseSnapManagerOpts): SnapManager {
    const { project, ui } = opts;

    function noSnap(rawX: number, rawY: number): SnapHints {
        return { snappedX: rawX, snappedY: rawY, activeXAxes: [], activeYAxes: [] };
    }

    function collectElementAxes(excludeIds: Set<string>): { xs: number[]; ys: number[] } {
        const xs: number[] = [];
        const ys: number[] = [];
        const layers = project.state?.layers;
        if (!layers) return { xs, ys };
        for (const layer of layers) {
            if (!layer.visible) continue;
            for (const el of layer.elements as Element[]) {
                if (excludeIds.has(el.id)) continue;
                if (!el.visible) continue;
                const left = el.x;
                const right = el.x + el.w;
                const cx = el.x + el.w / 2;
                const top = el.y;
                const bottom = el.y + el.h;
                const cy = el.y + el.h / 2;
                xs.push(left, cx, right);
                ys.push(top, cy, bottom);
            }
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

        if (ui.snapToCanvas) {
            const c = collectCanvasAxes();
            xs.push(...c.xs);
            ys.push(...c.ys);
        }
        if (ui.snapToElement) {
            const e = collectElementAxes(excludeIds);
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

        if (xs.length === 0 && ys.length === 0) return noSnap(rawX, rawY);

        const rx = snapAxis(rawX, w, xs, threshold);
        const ry = snapAxis(rawY, h, ys, threshold);

        return {
            snappedX: rawX + rx.delta,
            snappedY: rawY + ry.delta,
            activeXAxes: rx.axes,
            activeYAxes: ry.axes,
        };
    }

    return { snap };
}
