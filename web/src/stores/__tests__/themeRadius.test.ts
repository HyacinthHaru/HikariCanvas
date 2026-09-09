// @vitest-environment happy-dom
/**
 * 主题圆角刻度：只保留小 / 中 / 大。
 *
 * <p>删掉的是 `xl` 与 `full`。`full` 会把输入框做成纯胶囊形，那不是可选风格而是坏掉的外观。</p>
 *
 * <p>这里守两件事：①选项集合就是三档，将来有人手滑加回来会红；
 * ②<b>存量用户的迁移</b> —— 旧偏好 `full` / `xl` 还躺在 localStorage 里，
 * 读出来必须回落到默认值 `md`，而不是把一个不存在的刻度写到 `<html data-radius>` 上
 * （那样 CSS 里没有对应规则，`--radius` 会掉回 :root 默认值，用户看到的是"设置丢了"
 * 而且再也换不回来——因为按钮列表里已经没有那一项可点）。</p>
 */
import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { RADIUS_OPTIONS, useThemeStore } from '../theme';

const RADIUS_KEY = 'hikari-canvas:theme.radius';

/**
 * 本仓的测试环境没有 localStorage（Node 26 起要显式 --localstorage-file），
 * 生产代码一律 try/catch 兜住所以照跑不误 —— 但这组用例测的就是"读存量偏好"，
 * 必须自带一个内存实现，否则 loadRadius 每次都走 catch 分支、迁移路径根本没被覆盖。
 */
function installMemoryLocalStorage(): Map<string, string> {
    const store = new Map<string, string>();
    vi.stubGlobal('localStorage', {
        getItem: (k: string) => (store.has(k) ? store.get(k)! : null),
        setItem: (k: string, v: string) => { store.set(k, String(v)); },
        removeItem: (k: string) => { store.delete(k); },
        clear: () => { store.clear(); },
        key: (i: number) => [...store.keys()][i] ?? null,
        get length() { return store.size; },
    });
    return store;
}

let mem: Map<string, string>;

beforeEach(() => {
    mem = installMemoryLocalStorage();
    document.documentElement.removeAttribute('data-radius');
    setActivePinia(createPinia());
});

afterEach(() => {
    vi.unstubAllGlobals();
});

describe('圆角刻度选项', () => {
    it('恰好三档：小 / 中 / 大', () => {
        expect(RADIUS_OPTIONS.map(r => r.id)).toEqual(['sm', 'md', 'lg']);
    });

    it('不再提供 full（纯圆形）与 xl', () => {
        const ids = RADIUS_OPTIONS.map(r => String(r.id));
        expect(ids).not.toContain('full');
        expect(ids).not.toContain('xl');
    });

    it('px 值随档位单调递增，且没有胶囊级数值', () => {
        const px = RADIUS_OPTIONS.map(r => r.px);
        expect(px).toEqual([...px].sort((a, b) => a - b));
        expect(Math.max(...px)).toBeLessThanOrEqual(16);
    });
});

describe('存量偏好迁移', () => {
    it.each(['full', 'xl'])('旧值 %s 回落到 md', (legacy) => {
        mem.set(RADIUS_KEY, legacy);
        expect(useThemeStore().radius).toBe('md');
    });

    it('仍然支持的值原样保留', () => {
        mem.set(RADIUS_KEY, 'lg');
        expect(useThemeStore().radius).toBe('lg');
    });

    it('没有存量偏好时用 md', () => {
        expect(useThemeStore().radius).toBe('md');
    });

    it('回落之后写到 <html data-radius> 的是有效刻度', () => {
        mem.set(RADIUS_KEY, 'full');
        useThemeStore();
        expect(document.documentElement.dataset.radius).toBe('md');
    });
});
