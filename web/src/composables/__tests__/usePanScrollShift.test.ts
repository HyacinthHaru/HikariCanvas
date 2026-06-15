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
    deltaY?: number;
    ctrlKey?: boolean;
    metaKey?: boolean;
    shiftKey?: boolean;
    clientX?: number;
    clientY?: number;
}): WheelEvent {
    const prevented = { called: false };
    return {
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
});
