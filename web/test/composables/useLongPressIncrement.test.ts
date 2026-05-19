/**
 * @vitest-environment happy-dom
 *
 * 0.4.0-P2-G：useLongPressIncrement 单元测试。
 *
 * 覆盖：
 * - 单击 onPointerDown 后立即触发 1 次
 * - 长按 350ms：触发 1（initial click）+ 1（350ms > 300ms initialDelay 后 1 tick）
 * - pointerleave 立即清除：不再 tick
 * - pointercancel 清除：不再 tick
 * - 组件 unmount 后 timer 清理（不再触发 onTick）
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, defineComponent, h } from 'vue';
import { useLongPressIncrement } from '@/composables/useLongPressIncrement';

/**
 * 把 composable mount 在一个最小 Vue app 里，方便测 onBeforeUnmount 行为。
 * 返 { tick, unmount, handlers }；用法见下方 describe 块。
 */
function mountWithComposable(opts: { onTick: () => void; initialDelay?: number; interval?: number }) {
    let handlers: ReturnType<typeof useLongPressIncrement> | null = null;
    const Comp = defineComponent({
        setup() {
            handlers = useLongPressIncrement(opts);
            return () => h('button');
        },
    });
    const container = document.createElement('div');
    const app = createApp(Comp);
    app.mount(container);
    return {
        handlers: handlers!,
        unmount: () => app.unmount(),
    };
}

beforeEach(() => {
    vi.useFakeTimers();
});

afterEach(() => {
    vi.useRealTimers();
});

const fakePointerDown = {} as PointerEvent;

describe('useLongPressIncrement', () => {
    it('单击：onPointerDown 立即触发 1 次 onTick', () => {
        const onTick = vi.fn();
        const { handlers, unmount } = mountWithComposable({ onTick });
        handlers.onPointerDown(fakePointerDown);
        // pointerdown 同步触发 1 次
        expect(onTick).toHaveBeenCalledTimes(1);
        // 没等到 initialDelay 就 up，不应再 tick
        handlers.onPointerUp();
        vi.advanceTimersByTime(1000);
        expect(onTick).toHaveBeenCalledTimes(1);
        unmount();
    });

    it('长按 350ms：initial click + 1 个 interval tick', () => {
        const onTick = vi.fn();
        const { handlers, unmount } = mountWithComposable({
            onTick, initialDelay: 300, interval: 50,
        });
        handlers.onPointerDown(fakePointerDown);
        expect(onTick).toHaveBeenCalledTimes(1); // 立即
        // 等 300ms initialDelay 触发，再过 50ms tick 一次（共 350ms 时间线）
        vi.advanceTimersByTime(300);
        // 300ms 时 initialTimer 触发，启动 setInterval；但 setInterval 第一次 tick 要再等 50ms
        expect(onTick).toHaveBeenCalledTimes(1);
        vi.advanceTimersByTime(50);
        // 现在到了 350ms：第 1 次 interval tick
        expect(onTick).toHaveBeenCalledTimes(2);
        handlers.onPointerUp();
        unmount();
    });

    it('pointerleave 立即清除：不再 tick', () => {
        const onTick = vi.fn();
        const { handlers, unmount } = mountWithComposable({
            onTick, initialDelay: 300, interval: 50,
        });
        handlers.onPointerDown(fakePointerDown);
        expect(onTick).toHaveBeenCalledTimes(1);
        // 200ms 后 leave（还没到 initialDelay）
        vi.advanceTimersByTime(200);
        handlers.onPointerLeave();
        // 再过 500ms 也不应该 tick
        vi.advanceTimersByTime(500);
        expect(onTick).toHaveBeenCalledTimes(1);
        unmount();
    });

    it('pointercancel 清除：不再 tick', () => {
        const onTick = vi.fn();
        const { handlers, unmount } = mountWithComposable({
            onTick, initialDelay: 300, interval: 50,
        });
        handlers.onPointerDown(fakePointerDown);
        // 进入 interval mode 后 cancel
        vi.advanceTimersByTime(350); // 1 + 1 ticks
        expect(onTick).toHaveBeenCalledTimes(2);
        handlers.onPointerCancel();
        vi.advanceTimersByTime(500);
        // cancel 后再过 500ms 也不增加
        expect(onTick).toHaveBeenCalledTimes(2);
        unmount();
    });

    it('onBeforeUnmount 清理：组件销毁后 timer 不再 tick', () => {
        const onTick = vi.fn();
        const { handlers, unmount } = mountWithComposable({
            onTick, initialDelay: 300, interval: 50,
        });
        handlers.onPointerDown(fakePointerDown);
        expect(onTick).toHaveBeenCalledTimes(1);
        // 进入 interval mode
        vi.advanceTimersByTime(350);
        expect(onTick).toHaveBeenCalledTimes(2);
        // 不调 pointerUp 直接 unmount——onBeforeUnmount 必须兜底清 timer
        unmount();
        vi.advanceTimersByTime(2000);
        // unmount 之后无论过多久都不应再 tick
        expect(onTick).toHaveBeenCalledTimes(2);
    });
});
