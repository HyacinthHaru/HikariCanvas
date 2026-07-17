/**
 * 长按累加 composable。
 *
 * <p>用例：变量管理面板的 [-1] [+1] 按钮——单击 +1，长按 300ms 起以 50ms / tick 持续 +N。
 * 通用足以接其它"递增 / 递减"按钮（例如 SizeInput 微调）。</p>
 *
 * <p>API：返回 4 个事件 handler，组件用 {@code @pointerdown / pointerup / pointerleave /
 * pointercancel} 绑上即可。{@link isPressing} 可用于视觉反馈（按下态 highlight）。</p>
 *
 * <pre>
 *   const inc = useLongPressIncrement({ onTick: () => setValue(value.value + 1) });
 *   &lt;button @pointerdown="inc.onPointerDown" @pointerup="inc.onPointerUp"
 *           @pointerleave="inc.onPointerLeave" @pointercancel="inc.onPointerCancel"&gt;
 * </pre>
 *
 * <p>时序：</p>
 * <ul>
 *   <li>{@code onPointerDown} 立即触发 1 次 {@code onTick}（这就是 "+1 单击" 行为）；</li>
 *   <li>启动 {@code initialDelay}（默认 300ms）定时器；</li>
 *   <li>超时后切换到 {@code interval}（默认 50ms）的 setInterval 持续 {@code onTick}；</li>
 *   <li>任意 up / leave / cancel → 清两层 timer；</li>
 *   <li>window blur / document visibilitychange → 终止（按钮未 setPointerCapture，
 *       长按中切窗口浏览器不保证补发 pointerup/cancel，否则 repeatTimer 后台空转使值暴涨；
 *       参照 {@code useBrushHost} 同款防护）；</li>
 *   <li>{@link onBeforeUnmount} 兜底清理（防组件销毁时残留 timer 泄漏）。</li>
 * </ul>
 */
import { computed, onBeforeUnmount, ref, type ComputedRef } from 'vue';

export interface UseLongPressIncrementOptions {
    /** 每次 tick 触发的回调。pointerDown 立即跑 1 次，长按时按 interval 周期跑。 */
    onTick: () => void;
    /** 长按从单击进入连续模式的延迟（ms）。默认 300。 */
    initialDelay?: number;
    /** 连续模式下每 tick 的间隔（ms）。默认 50。 */
    interval?: number;
}

export interface UseLongPressIncrementReturn {
    onPointerDown: (e: PointerEvent) => void;
    onPointerUp: () => void;
    onPointerLeave: () => void;
    onPointerCancel: () => void;
    /** 当前是否处于按下态（pointerDown 后 / up 前）；可用于按钮 active style。 */
    isPressing: ComputedRef<boolean>;
}

export function useLongPressIncrement(opts: UseLongPressIncrementOptions): UseLongPressIncrementReturn {
    const initialDelay = opts.initialDelay ?? 300;
    const interval = opts.interval ?? 50;

    const pressing = ref(false);
    let initialTimer: ReturnType<typeof setTimeout> | null = null;
    let repeatTimer: ReturnType<typeof setInterval> | null = null;

    function clearTimers(): void {
        if (initialTimer !== null) {
            clearTimeout(initialTimer);
            initialTimer = null;
        }
        if (repeatTimer !== null) {
            clearInterval(repeatTimer);
            repeatTimer = null;
        }
    }

    function stop(): void {
        if (!pressing.value && initialTimer === null && repeatTimer === null) return;
        clearTimers();
        pressing.value = false;
    }

    function onPointerDown(_e: PointerEvent): void {
        // 防多指 / 重复 down 时叠加 timer
        if (pressing.value) return;
        pressing.value = true;
        // 1) 立即触发 1 次（单击 +1 行为）
        opts.onTick();
        // 2) 起 initialDelay 定时器，到点切到 interval 周期
        initialTimer = setTimeout(() => {
            initialTimer = null;
            repeatTimer = setInterval(() => {
                opts.onTick();
            }, interval);
        }, initialDelay);
    }

    function onPointerUp(): void {
        stop();
    }

    function onPointerLeave(): void {
        stop();
    }

    function onPointerCancel(): void {
        stop();
    }

    // 长按期间切走窗口 / 标签页隐藏时，浏览器可能不补发 pointerup/leave/cancel，
    // 导致 repeatTimer 持续后台 onTick 使变量值无人值守暴涨。监听 window blur +
    // document visibilitychange 强制 stop()（idempotent，未按下时 no-op）。
    function onWindowBlur(): void {
        stop();
    }
    function onVisibilityChange(): void {
        if (document.hidden) stop();
    }
    window.addEventListener('blur', onWindowBlur);
    document.addEventListener('visibilitychange', onVisibilityChange);

    onBeforeUnmount(() => {
        // 兜底：组件销毁时残留 timer 会继续跑 onTick 引用 stale closure 上的 reactive ref，
        // 严重时触发 "writing to undefined" warning。stop() 同时清两层 timer + 重置 pressing。
        clearTimers();
        pressing.value = false;
        window.removeEventListener('blur', onWindowBlur);
        document.removeEventListener('visibilitychange', onVisibilityChange);
    });

    return {
        onPointerDown,
        onPointerUp,
        onPointerLeave,
        onPointerCancel,
        isPressing: computed(() => pressing.value),
    };
}
