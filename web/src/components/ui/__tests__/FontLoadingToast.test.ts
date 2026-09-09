// @vitest-environment happy-dom
/**
 * 字体加载提示条的时序。
 *
 * <p>这条提示的全部价值都在时序上，做错任一头都比不做还差：</p>
 * <ul>
 *   <li>不等待就弹 → 命中缓存的切换每次闪一下，变成噪音；</li>
 *   <li>不设最短显示时长 → 卡在阈值附近的加载"闪现一帧"，比没提示更让人以为出了错；</li>
 *   <li>只在成功时收起 → 加载失败后提示永远转下去（该分支由 FontLoader 的 end 信号保证，
 *       这里再从组件侧确认一遍）。</li>
 * </ul>
 */
import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import type { VueWrapper } from '@vue/test-utils';
import { useUiStore } from '@/stores/ui';
import { messages } from '@/i18n/messages';

/** 与组件内常量对齐；改了那边这里会红，提醒同步。 */
const SHOW_DELAY_MS = 200;
const MIN_VISIBLE_MS = 1000;

const startHandlers: ((id: string) => void)[] = [];
const endHandlers: ((id: string) => void)[] = [];

vi.mock('@/render/FontLoader', () => ({
    onFontLoadStart: (fn: (id: string) => void) => {
        startHandlers.push(fn);
        return () => { startHandlers.splice(startHandlers.indexOf(fn), 1); };
    },
    onFontLoadEnd: (fn: (id: string) => void) => {
        endHandlers.push(fn);
        return () => { endHandlers.splice(endHandlers.indexOf(fn), 1); };
    },
}));

const emitStart = (id: string) => startHandlers.forEach(fn => fn(id));
const emitEnd = (id: string) => endHandlers.forEach(fn => fn(id));

import FontLoadingToast from '../FontLoadingToast.vue';

async function tick(ms: number) {
    vi.advanceTimersByTime(ms);
    await Promise.resolve();
}

/** 只问"在不在"，不问文案是哪国语言——测试环境的默认 locale 不该影响时序断言。 */
function shown(w: VueWrapper): boolean {
    return w.find('[role="status"]').exists();
}

beforeEach(() => {
    startHandlers.length = 0;
    endHandlers.length = 0;
    setActivePinia(createPinia());
    vi.useFakeTimers();
});

afterEach(() => {
    vi.useRealTimers();
});

describe('FontLoadingToast', () => {
    it('初始不显示', () => {
        const w = mount(FontLoadingToast);
        expect(shown(w)).toBe(false);
        w.unmount();
    });

    it('加载够快（早于延迟阈值结束）就一次都不弹', async () => {
        const w = mount(FontLoadingToast);
        emitStart('inter');

        // 只推进 1ms —— 任何 >0 的延迟阈值都还没到，此刻绝不该已经弹出来。
        // 用绝对值而非 SHOW_DELAY_MS-50：后者在阈值被改小时会算成负数，
        // 于是这条用例对"阈值被去掉"就不敏感了（变异测试实测踩到过）。
        await tick(1);
        expect(shown(w)).toBe(false);

        emitEnd('inter');
        await tick(SHOW_DELAY_MS + MIN_VISIBLE_MS + 50);
        expect(shown(w)).toBe(false);
        w.unmount();
    });

    it('加载够慢就弹出来，文案取自 i18n 表（键缺了这里会红）', async () => {
        const w = mount(FontLoadingToast);
        const expected = messages[useUiStore().locale].canvas.fontLoading;
        expect(expected).toBeTruthy();

        emitStart('source_han_sans');
        await tick(SHOW_DELAY_MS + 1);
        expect(shown(w)).toBe(true);
        expect(w.text()).toContain(expected);
        w.unmount();
    });

    it('弹出后立刻加载完，仍至少显示 MIN_VISIBLE_MS（不闪一帧）', async () => {
        const w = mount(FontLoadingToast);
        emitStart('source_han_sans');
        await tick(SHOW_DELAY_MS + 1);
        expect(shown(w)).toBe(true);

        emitEnd('source_han_sans');
        await tick(MIN_VISIBLE_MS - 100);
        expect(shown(w)).toBe(true);

        await tick(200);
        expect(shown(w)).toBe(false);
        w.unmount();
    });

    it('加载失败（end 照常来）也会收起', async () => {
        const w = mount(FontLoadingToast);
        emitStart('broken_font');
        await tick(SHOW_DELAY_MS + 1);
        expect(shown(w)).toBe(true);

        emitEnd('broken_font'); // 失败路径也报 end
        await tick(MIN_VISIBLE_MS + 50);
        expect(shown(w)).toBe(false);
        w.unmount();
    });

    it('多字体并发：只弹一条，且要等全部结束才收', async () => {
        const w = mount(FontLoadingToast);
        emitStart('inter');
        emitStart('lobster');
        await tick(SHOW_DELAY_MS + 1);
        expect(w.findAll('[role="status"]')).toHaveLength(1);

        emitEnd('inter');
        await tick(MIN_VISIBLE_MS + 50);
        expect(shown(w)).toBe(true); // lobster 还在加载

        emitEnd('lobster');
        await tick(MIN_VISIBLE_MS + 50);
        expect(shown(w)).toBe(false);
        w.unmount();
    });

    it('同一 fontId 重复报 end 不会把状态减穿', async () => {
        const w = mount(FontLoadingToast);
        emitStart('inter');
        emitStart('lobster');
        await tick(SHOW_DELAY_MS + 1);

        emitEnd('inter');
        emitEnd('inter');
        await tick(MIN_VISIBLE_MS + 50);
        expect(shown(w)).toBe(true); // lobster 仍未结束
        w.unmount();
    });

    it('卸载后不再持有订阅（避免多次挂载堆积回调）', () => {
        const w = mount(FontLoadingToast);
        expect(startHandlers).toHaveLength(1);
        expect(endHandlers).toHaveLength(1);
        w.unmount();
        expect(startHandlers).toHaveLength(0);
        expect(endHandlers).toHaveLength(0);
    });
});
