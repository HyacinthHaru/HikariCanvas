// @vitest-environment happy-dom
/**
 * 0.7.0-P4-B：useBlockCanvas pan/zoom 纯逻辑 + composable 行为单测。
 *
 * <p>核心是缩放锚点数学：ctrl+wheel 以光标为锚缩放，<b>光标下 world 点缩放前后不变</b>。
 * 纯函数（{@link computeZoomPan} / {@link screenToWorldPure} / {@link clampZoom}）逐项测；
 * composable 用 {@link effectScope} 提供 onScopeDispose 上下文，验证 pan 累加 / wheel 缩放 /
 * resetView。happy-dom 环境（composable 注册 window/document 监听）。</p>
 */
import { describe, expect, it } from 'vitest';
import { effectScope, ref } from 'vue';
import {
    clampZoom,
    computeZoomPan,
    screenToWorldPure,
    useBlockCanvas,
    ZOOM_MIN,
    ZOOM_MAX,
    type ViewState,
} from '../useBlockCanvas';

describe('clampZoom', () => {
    it('钳到下限 0.4', () => {
        expect(clampZoom(0.1)).toBe(ZOOM_MIN);
        expect(clampZoom(0.4)).toBe(0.4);
    });
    it('钳到上限 2', () => {
        expect(clampZoom(5)).toBe(ZOOM_MAX);
        expect(clampZoom(2)).toBe(2);
    });
    it('中间值原样', () => {
        expect(clampZoom(1)).toBe(1);
        expect(clampZoom(1.5)).toBe(1.5);
    });
    it('非有限值（NaN / Infinity）兜底为 1', () => {
        expect(clampZoom(NaN)).toBe(1);
        expect(clampZoom(Infinity)).toBe(1);   // !Number.isFinite → 安全兜底 1
        expect(clampZoom(-Infinity)).toBe(1);
    });
});

describe('computeZoomPan 缩放锚点', () => {
    it('放大后光标下 world 坐标不变', () => {
        const prev: ViewState = { panX: 100, panY: 50, zoom: 1 };
        const anchorX = 300;
        const anchorY = 200;
        // 缩放前锚点 world
        const wBefore = screenToWorldPure(prev, anchorX, anchorY);
        const next = computeZoomPan(prev, anchorX, anchorY, 1.5);
        expect(next.zoom).toBeCloseTo(1.5, 6);
        // 缩放后同一屏幕锚点的 world
        const wAfter = screenToWorldPure(next, anchorX, anchorY);
        expect(wAfter.x).toBeCloseTo(wBefore.x, 6);
        expect(wAfter.y).toBeCloseTo(wBefore.y, 6);
    });

    it('缩小后光标下 world 坐标不变', () => {
        const prev: ViewState = { panX: -40, panY: 80, zoom: 1.6 };
        const anchorX = 150;
        const anchorY = 90;
        const wBefore = screenToWorldPure(prev, anchorX, anchorY);
        const next = computeZoomPan(prev, anchorX, anchorY, 1 / 1.1);
        const wAfter = screenToWorldPure(next, anchorX, anchorY);
        expect(wAfter.x).toBeCloseTo(wBefore.x, 6);
        expect(wAfter.y).toBeCloseTo(wBefore.y, 6);
    });

    it('已到上限再放大 → 返回原 view（无变化，避免边界抖动）', () => {
        const prev: ViewState = { panX: 10, panY: 20, zoom: ZOOM_MAX };
        const next = computeZoomPan(prev, 100, 100, 1.2);
        expect(next).toBe(prev);   // 同一对象引用 = 完全无变化
    });

    it('已到下限再缩小 → 返回原 view', () => {
        const prev: ViewState = { panX: 10, panY: 20, zoom: ZOOM_MIN };
        const next = computeZoomPan(prev, 100, 100, 0.5);
        expect(next).toBe(prev);
    });

    it('锚点在原点（0,0）时 panX/panY 按 ratio 缩放', () => {
        const prev: ViewState = { panX: 60, panY: 60, zoom: 1 };
        const next = computeZoomPan(prev, 0, 0, 2);
        // pan' = 0 - (0 - pan) * ratio = pan * ratio
        expect(next.zoom).toBe(2);
        expect(next.panX).toBeCloseTo(120, 6);
        expect(next.panY).toBeCloseTo(120, 6);
    });
});

describe('screenToWorldPure 往返', () => {
    it('world → screen → world 一致（多组 pan/zoom）', () => {
        const cases: ViewState[] = [
            { panX: 0, panY: 0, zoom: 1 },
            { panX: 120, panY: -30, zoom: 0.4 },
            { panX: -200, panY: 400, zoom: 2 },
            { panX: 17, panY: 23, zoom: 1.37 },
        ];
        for (const view of cases) {
            const worldX = 256;
            const worldY = -64;
            // world → screen（viewport 内坐标）
            const sx = view.panX + worldX * view.zoom;
            const sy = view.panY + worldY * view.zoom;
            const back = screenToWorldPure(view, sx, sy);
            expect(back.x).toBeCloseTo(worldX, 6);
            expect(back.y).toBeCloseTo(worldY, 6);
        }
    });
});

describe('useBlockCanvas composable', () => {
    function makeViewport(): HTMLElement {
        const el = document.createElement('div');
        // happy-dom 无真实布局：stub getBoundingClientRect 返固定 rect。
        el.getBoundingClientRect = () => ({
            left: 0, top: 0, right: 800, bottom: 600, width: 800, height: 600, x: 0, y: 0,
            toJSON: () => ({}),
        }) as DOMRect;
        return el;
    }

    /** 伪 PointerEvent：真实 Event.target 是只读 getter 无法 assign，handler 只读这几个字段，
     *  故构造鸭子对象。target 带 set/releasePointerCapture stub（happy-dom 无 capture）。 */
    function fakePointer(fields: Partial<PointerEvent>): PointerEvent {
        const target = document.createElement('div');
        target.setPointerCapture = () => {};
        target.releasePointerCapture = () => {};
        return { preventDefault() {}, target, ...fields } as unknown as PointerEvent;
    }

    it('pan 累加：pointerdown(中键) + move 改 panX/panY', () => {
        const scope = effectScope();
        scope.run(() => {
            const viewportRef = ref<HTMLElement | null>(makeViewport());
            const c = useBlockCanvas({ viewportRef, isSpaceDown: () => false });
            expect(c.panX.value).toBe(0);
            expect(c.isPanning.value).toBe(false);

            const accepted = c.onPanPointerDown(fakePointer({ button: 1, pointerId: 1, clientX: 100, clientY: 100 }));
            expect(accepted).toBe(true);
            expect(c.isPanning.value).toBe(true);

            c.onPanPointerMove(fakePointer({ clientX: 140, clientY: 175 }));
            expect(c.panX.value).toBe(40);   // 140 - 100
            expect(c.panY.value).toBe(75);   // 175 - 100

            c.onPanPointerUp();
            expect(c.isPanning.value).toBe(false);
        });
        scope.stop();
    });

    it('左键非空格非空白不接管 pan', () => {
        const scope = effectScope();
        scope.run(() => {
            const viewportRef = ref<HTMLElement | null>(makeViewport());
            const c = useBlockCanvas({ viewportRef, isSpaceDown: () => false });
            expect(c.onPanPointerDown(fakePointer({ button: 0, pointerId: 1, clientX: 0, clientY: 0 }), false)).toBe(false);
            // 空格按住则接管
            const c2 = useBlockCanvas({ viewportRef, isSpaceDown: () => true });
            expect(c2.onPanPointerDown(fakePointer({ button: 0, pointerId: 1, clientX: 0, clientY: 0 }), false)).toBe(true);
        });
        scope.stop();
    });

    it('onWheel(ctrl) 以光标锚缩放，world 坐标守恒', () => {
        const scope = effectScope();
        scope.run(() => {
            const viewportRef = ref<HTMLElement | null>(makeViewport());
            const c = useBlockCanvas({ viewportRef, isSpaceDown: () => false });
            // 缩放前锚点(300,200) world
            const wBefore = c.screenToWorld(300, 200);
            c.onWheel({ ctrlKey: true, metaKey: false, deltaY: -100, clientX: 300, clientY: 200, preventDefault() {} } as unknown as WheelEvent);
            expect(c.zoom.value).toBeGreaterThan(1);
            const wAfter = c.screenToWorld(300, 200);
            expect(wAfter.x).toBeCloseTo(wBefore.x, 4);
            expect(wAfter.y).toBeCloseTo(wBefore.y, 4);
        });
        scope.stop();
    });

    it('onWheel 无 ctrl/meta 不缩放', () => {
        const scope = effectScope();
        scope.run(() => {
            const viewportRef = ref<HTMLElement | null>(makeViewport());
            const c = useBlockCanvas({ viewportRef, isSpaceDown: () => false });
            c.onWheel({ ctrlKey: false, metaKey: false, deltaY: -100, clientX: 0, clientY: 0, preventDefault() {} } as unknown as WheelEvent);
            expect(c.zoom.value).toBe(1);
        });
        scope.stop();
    });

    it('resetView 归零 pan + zoom', () => {
        const scope = effectScope();
        scope.run(() => {
            const viewportRef = ref<HTMLElement | null>(makeViewport());
            const c = useBlockCanvas({ viewportRef, isSpaceDown: () => false });
            c.panX.value = 123;
            c.panY.value = -45;
            c.zoom.value = 1.8;
            c.resetView();
            expect(c.panX.value).toBe(0);
            expect(c.panY.value).toBe(0);
            expect(c.zoom.value).toBe(1);
        });
        scope.stop();
    });

    it('worldStyle 反映 transform translate+scale', () => {
        const scope = effectScope();
        scope.run(() => {
            const viewportRef = ref<HTMLElement | null>(makeViewport());
            const c = useBlockCanvas({ viewportRef, isSpaceDown: () => false });
            c.panX.value = 10;
            c.panY.value = 20;
            c.zoom.value = 1.5;
            expect(c.worldStyle.value.transform).toBe('translate(10px, 20px) scale(1.5)');
            expect(c.worldStyle.value.transformOrigin).toBe('0 0');
        });
        scope.stop();
    });
});
