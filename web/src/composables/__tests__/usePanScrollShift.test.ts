/**
 * 0.7.4：usePanScroll — Shift+wheel 水平滚动分支单元测试。
 *
 * 覆盖：
 * - Shift+wheel（deltaY > 0）→ scrollLeft 增加 deltaY、调用 preventDefault
 * - Shift+wheel（deltaY < 0）→ scrollLeft 减少 |deltaY|（向左）
 * - Shift+wheel、outerRef=null → 不抛、不写 scrollLeft
 * - 普通 wheel（无 Shift、无 Ctrl）→ scrollLeft 不变（return 早退）
 * - Ctrl+wheel → 走缩放分支（不走 Shift 分支），scrollLeft 不被直接改写
 * - Shift+Ctrl+wheel → 走缩放分支（Ctrl 优先），不走 Shift 横滚
 *
 * 不引 happy-dom / jsdom：用纯 JS 对象 mock WheelEvent 和 HTMLElement。
 * 需要 Pinia 上下文（usePanScroll 内部调用 useUiStore()）。
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { ref } from 'vue';
import { createPinia, setActivePinia } from 'pinia';
import { usePanScroll } from '../usePanScroll';

// ---------- helpers ----------

/** 最小 HTMLElement mock：只记录 scrollLeft / scrollTop，提供 getBoundingClientRect。 */
function makeFakeOuter(initialScrollLeft = 0): HTMLElement {
    return {
        scrollLeft: initialScrollLeft,
        scrollTop: 0,
        clientWidth: 800,
        clientHeight: 600,
        scrollWidth: 3000,
        scrollHeight: 3000,
        getBoundingClientRect() { return { left: 0, top: 0, right: 800, bottom: 600, width: 800, height: 600 }; },
    } as unknown as HTMLElement;
}

/** 创建 WheelEvent-like 对象（纯 POJO，供 onWheel 直接调用）。 */
function makeWheelEvent(opts: {
    deltaX?: number;
    deltaY?: number;
    ctrlKey?: boolean;
    metaKey?: boolean;
    shiftKey?: boolean;
    clientX?: number;
    clientY?: number;
}): WheelEvent {
    const prevented = { called: false };
    return {
        deltaX: opts.deltaX ?? 0,
        deltaY: opts.deltaY ?? 0,
        ctrlKey: opts.ctrlKey ?? false,
        metaKey: opts.metaKey ?? false,
        shiftKey: opts.shiftKey ?? false,
        clientX: opts.clientX ?? 0,
        clientY: opts.clientY ?? 0,
        preventDefault: vi.fn(),
        __prevented: prevented,
    } as unknown as WheelEvent;
}

/** 创建 MouseEvent-like 对象（纯 POJO，供 onMouseDown 直接调用）。 */
function makeMouseEvent(opts: { button?: number; altKey?: boolean }): MouseEvent {
    return {
        button: opts.button ?? 0,
        altKey: opts.altKey ?? false,
        clientX: 100,
        clientY: 100,
        preventDefault: vi.fn(),
    } as unknown as MouseEvent;
}

// ---------- test setup ----------

beforeEach(() => {
    setActivePinia(createPinia());
});

// ---------- tests ----------

describe('usePanScroll — Shift+wheel 水平滚动', () => {
    it('Shift+wheel deltaY=120 → scrollLeft += 120，调 preventDefault', () => {
        const outer = makeFakeOuter(0);
        const outerRef = ref<HTMLElement | null>(outer);
        const { onWheel } = usePanScroll({ outerRef, widthPx: () => 512, heightPx: () => 512 });

        const e = makeWheelEvent({ shiftKey: true, deltaY: 120 });
        onWheel(e);

        expect(outer.scrollLeft).toBe(120);
        expect(e.preventDefault).toHaveBeenCalledOnce();
    });

    it('Shift+wheel deltaY=-100 → scrollLeft -= 100（向左）', () => {
        const outer = makeFakeOuter(200);
        const outerRef = ref<HTMLElement | null>(outer);
        const { onWheel } = usePanScroll({ outerRef, widthPx: () => 512, heightPx: () => 512 });

        const e = makeWheelEvent({ shiftKey: true, deltaY: -100 });
        onWheel(e);

        expect(outer.scrollLeft).toBe(100);
        expect(e.preventDefault).toHaveBeenCalledOnce();
    });

    it('Shift+wheel、outerRef=null → 不抛异常，preventDefault 仍被调用', () => {
        const outerRef = ref<HTMLElement | null>(null);
        const { onWheel } = usePanScroll({ outerRef, widthPx: () => 512, heightPx: () => 512 });

        const e = makeWheelEvent({ shiftKey: true, deltaY: 80 });
        // 不应抛
        expect(() => onWheel(e)).not.toThrow();
        expect(e.preventDefault).toHaveBeenCalledOnce();
    });

    it('普通 wheel（无 Shift、无 Ctrl）→ scrollLeft 不变（浏览器默认纵滚）', () => {
        const outer = makeFakeOuter(50);
        const outerRef = ref<HTMLElement | null>(outer);
        const { onWheel } = usePanScroll({ outerRef, widthPx: () => 512, heightPx: () => 512 });

        const e = makeWheelEvent({ deltaY: 120 });
        onWheel(e);

        expect(outer.scrollLeft).toBe(50);   // 未改动
        expect(e.preventDefault).not.toHaveBeenCalled();
    });

    it('Ctrl+wheel → 走缩放分支，不走 Shift 横滚（scrollLeft 不被直接 += deltaY）', () => {
        const outer = makeFakeOuter(0);
        const outerRef = ref<HTMLElement | null>(outer);
        const { onWheel } = usePanScroll({ outerRef, widthPx: () => 512, heightPx: () => 512 });

        const e = makeWheelEvent({ ctrlKey: true, deltaY: 100 });
        onWheel(e);

        // 缩放分支走 nextTick 修 scrollLeft（此处 nextTick 未 flush），
        // 但不会在同步路径里直接 += 100；断言 scrollLeft 仍为 0。
        expect(outer.scrollLeft).toBe(0);
        expect(e.preventDefault).toHaveBeenCalledOnce();
    });

    it('Shift+Ctrl+wheel → Ctrl 优先走缩放分支，不走 Shift 横滚', () => {
        const outer = makeFakeOuter(0);
        const outerRef = ref<HTMLElement | null>(outer);
        const { onWheel } = usePanScroll({ outerRef, widthPx: () => 512, heightPx: () => 512 });

        const e = makeWheelEvent({ ctrlKey: true, shiftKey: true, deltaY: 100 });
        onWheel(e);

        expect(outer.scrollLeft).toBe(0);
        expect(e.preventDefault).toHaveBeenCalledOnce();
    });

    // macOS 的 Chrome / Safari 在事件层就把 Shift+滚轮换成了 deltaX（deltaY 恒为 0）。
    // 只看 deltaY 的话这里加 0 等于没滚，却又把浏览器自己的横滚 preventDefault 掉了，
    // 横向彻底动不了。
    it('Shift+wheel 只给 deltaX（macOS）→ 按 deltaX 横滚', () => {
        const outer = makeFakeOuter(300);
        const outerRef = ref<HTMLElement | null>(outer);
        const { onWheel } = usePanScroll({ outerRef, widthPx: () => 512, heightPx: () => 512 });

        const e = makeWheelEvent({ shiftKey: true, deltaX: -120, deltaY: 0 });
        onWheel(e);

        expect(outer.scrollLeft).toBe(180);
        expect(e.preventDefault).toHaveBeenCalledOnce();
    });

    it('Shift+wheel 两个增量都有 → deltaX 优先（不叠加）', () => {
        const outer = makeFakeOuter(0);
        const outerRef = ref<HTMLElement | null>(outer);
        const { onWheel } = usePanScroll({ outerRef, widthPx: () => 512, heightPx: () => 512 });

        const e = makeWheelEvent({ shiftKey: true, deltaX: 30, deltaY: 120 });
        onWheel(e);

        expect(outer.scrollLeft).toBe(30);
    });

    it('Shift+wheel 但两个增量都是 0 → 不拦浏览器默认行为', () => {
        const outer = makeFakeOuter(50);
        const outerRef = ref<HTMLElement | null>(outer);
        const { onWheel } = usePanScroll({ outerRef, widthPx: () => 512, heightPx: () => 512 });

        const e = makeWheelEvent({ shiftKey: true, deltaX: 0, deltaY: 0 });
        onWheel(e);

        expect(outer.scrollLeft).toBe(50);
        expect(e.preventDefault).not.toHaveBeenCalled();
    });
});

// Alt+左键既是"平移画布"也是"画套索蒙版"的手势。套索开画之后平移必须让路，
// 否则画布一边滚一边采样，套索点全飘（采样是按容器位置换算的）。
describe('usePanScroll — 手势被别人接管时不起平移', () => {
    it('blockPan 返 true 时 Alt+左键不起平移', () => {
        const outer = makeFakeOuter(500);
        const outerRef = ref<HTMLElement | null>(outer);
        const { onMouseDown, onMouseMove, isPanning } = usePanScroll({
            outerRef, widthPx: () => 512, heightPx: () => 512,
            blockPan: () => true,
        });

        const down = makeMouseEvent({ button: 0, altKey: true });
        onMouseDown(down);
        onMouseMove({ clientX: 200, clientY: 200 } as MouseEvent);

        expect(isPanning.value).toBe(false);
        expect(outer.scrollLeft).toBe(500);
        expect(down.preventDefault).not.toHaveBeenCalled();
    });

    it('blockPan 返 false 时 Alt+左键照常平移（回归守卫）', () => {
        const outer = makeFakeOuter(500);
        const outerRef = ref<HTMLElement | null>(outer);
        const { onMouseDown, onMouseMove, isPanning } = usePanScroll({
            outerRef, widthPx: () => 512, heightPx: () => 512,
            blockPan: () => false,
        });

        onMouseDown(makeMouseEvent({ button: 0, altKey: true }));
        onMouseMove({ clientX: 60, clientY: 100 } as MouseEvent);

        expect(isPanning.value).toBe(true);
        expect(outer.scrollLeft).toBe(540);
    });
});
