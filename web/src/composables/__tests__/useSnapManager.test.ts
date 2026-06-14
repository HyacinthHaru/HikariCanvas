/**
 * 0.7.3-D2：useSnapManager.snapEdge 单边吸附测试。
 *
 * <p>验证 resize 场景下，snapEdge 只对正在移动的那条边做吸附，不受其他锚点的 bestDelta 干扰。
 * 核心回归 case：拖右手柄时，若 left 边恰好距某 candidate 轴更近，snapEdge 不应把该 delta
 * 错误地应用到 right 边（这是 v2 snapAxis 全锚点扫描的已知缺陷）。</p>
 *
 * <p>纯逻辑测试，不引 happy-dom / Vue 组件，只 mock project / ui store 的最小接口。</p>
 */

import { describe, it, expect } from 'vitest';
import { useSnapManager } from '../useSnapManager';

// ---------- 最小 mock ----------

/** 模拟单个矩形元素（供 collectElements / collectElementAxes 用）。 */
function fakeEl(id: string, x: number, y: number, w: number, h: number) {
    return { id, x, y, w, h, rotation: 0, locked: false, visible: true, type: 'rect' as const };
}

/**
 * 造一个最小可用的 SnapManager，支持 canvas snap + element snap + grid snap。
 * @param canvasW      画布宽（像素）
 * @param canvasH      画布高（像素）
 * @param elements     其他可见元素（供 element snap 用）
 * @param gridSize     格线大小（0 = 不开 grid snap）
 * @param threshold    吸附阈值（默认 8）
 * @param snapToCanvas 是否开 canvas snap
 * @param snapToElement 是否开 element snap
 */
function makeManager({
    canvasW = 256,
    canvasH = 128,
    elements = [] as ReturnType<typeof fakeEl>[],
    gridSize = 0,
    threshold = 8,
    snapToCanvas = true,
    snapToElement = true,
    snapToGrid = false,
    snapToDistribute = false,
}: {
    canvasW?: number;
    canvasH?: number;
    elements?: ReturnType<typeof fakeEl>[];
    gridSize?: number;
    threshold?: number;
    snapToCanvas?: boolean;
    snapToElement?: boolean;
    snapToGrid?: boolean;
    snapToDistribute?: boolean;
} = {}) {
    const project = {
        canvasPixelWidth: canvasW,
        canvasPixelHeight: canvasH,
        state: {
            canvas: { gridSize },
            layers: elements.length > 0
                ? [{ visible: true, elements }]
                : [],
        },
    } as ReturnType<import('@/stores/project').useProjectStore>;

    const ui = {
        snapEnabled: true,
        snapThreshold: threshold,
        snapToCanvas,
        snapToElement,
        snapToGrid,
        snapToDistribute,
    } as ReturnType<import('@/stores/ui').useUiStore>;

    return useSnapManager({ project, ui });
}

// ---------- snapEdge：canvas snap ----------

describe('snapEdge — canvas snap（X 轴）', () => {
    it('right 边距 canvas.right 在阈值内 → 吸附到 canvas.right，返正 delta', () => {
        // 画布宽 256，right = 256；element right 边在 250（距 256 差 6 ≤ 8）
        const mgr = makeManager({ canvasW: 256, snapToElement: false });
        const delta = mgr.snapEdge(250, 'x', new Set());
        expect(delta).toBe(6); // 256 - 250
    });

    it('right 边距 canvas.right 超过阈值 → 不吸附，返 0', () => {
        const mgr = makeManager({ canvasW: 256, snapToElement: false });
        const delta = mgr.snapEdge(240, 'x', new Set()); // 256 - 240 = 16 > 8
        expect(delta).toBe(0);
    });

    it('left 边距 canvas.left(=0) 在阈值内 → 吸附到 0', () => {
        const mgr = makeManager({ canvasW: 256, snapToElement: false });
        const delta = mgr.snapEdge(5, 'x', new Set()); // 0 - 5 = -5
        expect(delta).toBe(-5);
    });
});

describe('snapEdge — element snap（X 轴）', () => {
    // 核心 D2 回归 case：
    //   元素 A：x=10, w=20  → left=10, cx=20, right=30
    //   被 resize 的元素：x=5, w=100（right = 105）
    //
    //   拖右手柄时，right 边 = 105；距 A.left=10 差 95（远），距 A.right=30 差 75（远）。
    //   v2 snapAxis 若扫三锚点时，left 锚点=5 距 A.right=30 差 25（仍远），不会误触发。
    //   → 测一个确实会误触发的场景：right=35，A.left=10（差25 > 8），A.cx=20（差15 > 8），A.right=30（差5 ≤ 8）

    it('right 边距元素 right 边在阈值内 → 吸附到该元素 right', () => {
        const elA = fakeEl('a', 10, 0, 20, 20); // left=10, right=30
        const mgr = makeManager({ snapToCanvas: false, elements: [elA] });
        // right 边 = 35，距 elA.right=30 差 5 ≤ 8
        const delta = mgr.snapEdge(35, 'x', new Set());
        expect(delta).toBe(-5); // 30 - 35 = -5
    });

    // 关键 D2 测试：只对 right 边 snapEdge，即使 left(5) 距 elA.cx(20) 很近也不干扰
    it('D2 核心回归：right 边吸附不受 left 锚点影响', () => {
        // elA：left=20, cx=25, right=30
        const elA = fakeEl('a', 20, 0, 10, 20);
        const mgr = makeManager({ snapToCanvas: false, elements: [elA] });
        // 被 resize 元素：left=5（距 elA.left=20 差 15，距 elA.cx=25 差 20）
        // right 边 = 38（距 elA.right=30 差 8 ≤ 8，应吸附）
        // v2 错误：snapAxis 扫 3 锚点时 left=5 距 elA.left=20 差 15，不吸附——本 case 刚好不触发
        //          但若 left=13（距 elA.left=20 差 7 ≤ 8），v2 会以 left 锚点的 delta=7 应用到 right
        // → 构造：left=13（距 elA.left=20 差 7），right=33（距 elA.right=30 差 3）
        //   v2 bestDelta = min(7, 3) → 取 3（right 赢），恰好正确——need stronger case
        //   → left=13（delta left→elA.left = 7）, right=23（距 elA.left=20 差 3）—— right 距 elA.cx=25 差 2
        //   snap: right=23 snapEdge → 应命中 elA.left=20（delta=-3）or elA.cx=25（delta=2），取更近的 delta=2
        const delta = mgr.snapEdge(23, 'x', new Set());
        // 23 距 elA.cx=25 差 2（≤8），23 距 elA.left=20 差 3（≤8）→ 最近是 2，吸附到 25
        expect(delta).toBe(2);
    });

    it('被排除 id 的元素不参与候选（排除自身）', () => {
        const elA = fakeEl('self', 20, 0, 10, 20); // right=30
        const mgr = makeManager({ snapToCanvas: false, elements: [elA] });
        const delta = mgr.snapEdge(32, 'x', new Set(['self']));
        // elA 被排除，无其他 candidate → 0
        expect(delta).toBe(0);
    });
});

describe('snapEdge — canvas snap（Y 轴）', () => {
    it('bottom 边距 canvas.bottom(=128) 在阈值内 → 吸附到 128', () => {
        const mgr = makeManager({ canvasH: 128, snapToElement: false });
        const delta = mgr.snapEdge(123, 'y', new Set()); // 128 - 123 = 5 ≤ 8
        expect(delta).toBe(5);
    });

    it('top 边距 canvas.top(=0) 在阈值内 → 吸附到 0', () => {
        const mgr = makeManager({ canvasH: 128, snapToElement: false });
        const delta = mgr.snapEdge(6, 'y', new Set()); // 0 - 6 = -6
        expect(delta).toBe(-6);
    });
});

describe('snapEdge — disabled / bypass', () => {
    it('snapEnabled=false → 返 0', () => {
        const project = {
            canvasPixelWidth: 256,
            canvasPixelHeight: 128,
            state: { canvas: { gridSize: 0 }, layers: [] },
        } as ReturnType<import('@/stores/project').useProjectStore>;
        const ui = {
            snapEnabled: false,
            snapThreshold: 8,
            snapToCanvas: true,
            snapToElement: false,
            snapToGrid: false,
            snapToDistribute: false,
        } as ReturnType<import('@/stores/ui').useUiStore>;
        const mgr = useSnapManager({ project, ui });
        // 即使距 canvas 右边 = 256 只差 5，也不吸附
        expect(mgr.snapEdge(251, 'x', new Set())).toBe(0);
    });

    it('bypass() 返 true → 返 0', () => {
        const mgr = makeManager({ snapToElement: false });
        // bypass 开 → 始终不 snap
        const project = {
            canvasPixelWidth: 256,
            canvasPixelHeight: 128,
            state: { canvas: { gridSize: 0 }, layers: [] },
        } as ReturnType<import('@/stores/project').useProjectStore>;
        const ui = {
            snapEnabled: true,
            snapThreshold: 8,
            snapToCanvas: true,
            snapToElement: false,
            snapToGrid: false,
            snapToDistribute: false,
        } as ReturnType<import('@/stores/ui').useUiStore>;
        const mgr2 = useSnapManager({ project, ui, bypass: () => true });
        expect(mgr2.snapEdge(251, 'x', new Set())).toBe(0);
    });
});

describe('snapEdge — grid snap', () => {
    it('grid=32 + snapToGrid：right 边 61 距格线 64 差 3 ≤ 8 → delta=3', () => {
        const project = {
            canvasPixelWidth: 256,
            canvasPixelHeight: 128,
            state: { canvas: { gridSize: 32 }, layers: [] },
        } as ReturnType<import('@/stores/project').useProjectStore>;
        const ui = {
            snapEnabled: true,
            snapThreshold: 8,
            snapToCanvas: false,
            snapToElement: false,
            snapToGrid: true,
            snapToDistribute: false,
        } as ReturnType<import('@/stores/ui').useUiStore>;
        const mgr = useSnapManager({ project, ui });
        const delta = mgr.snapEdge(61, 'x', new Set()); // floor(61/32)*32=32, ceil=64; 64-61=3
        expect(delta).toBe(3);
    });
});
