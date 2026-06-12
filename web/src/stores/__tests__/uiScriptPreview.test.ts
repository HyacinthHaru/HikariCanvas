// @vitest-environment happy-dom
/**
 * 0.7.1-P3-T1：ui store 预览框折叠 / 宽度持久化。
 *
 * <p>照 SNAP_KEY 持久化范式：默认值兜底 + clamp [20,70] + localStorage 写入 + 重载恢复。
 * happy-dom 提供 localStorage。每个用例前清 localStorage + 重建 pinia，避免跨用例污染。</p>
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';
import { useUiStore } from '@/stores/ui';

const KEY = 'hikari-canvas:script-preview';

// happy-dom 此版本的 localStorage 是残桩（仅 removeItem 可用），直接 get/set 抛错。
// 用一个内存版 Storage 替换全局，让持久化往返可断言。
function makeMemoryStorage(): Storage {
    const m = new Map<string, string>();
    return {
        get length() { return m.size; },
        clear: () => m.clear(),
        getItem: (k: string) => (m.has(k) ? m.get(k)! : null),
        key: (i: number) => Array.from(m.keys())[i] ?? null,
        removeItem: (k: string) => { m.delete(k); },
        setItem: (k: string, v: string) => { m.set(k, String(v)); },
    } as Storage;
}

describe('ui store 预览框折叠 / 宽度持久化（0.7.1-P3-T1）', () => {
    beforeEach(() => {
        vi.stubGlobal('localStorage', makeMemoryStorage());
        setActivePinia(createPinia());
    });

    it('默认：未折叠 + 宽度 35%', () => {
        const ui = useUiStore();
        expect(ui.scriptPreviewCollapsed).toBe(false);
        expect(ui.scriptPreviewWidthPct).toBe(35);
    });

    it('toggleScriptPreview 翻转折叠态', () => {
        const ui = useUiStore();
        expect(ui.scriptPreviewCollapsed).toBe(false);
        ui.toggleScriptPreview();
        expect(ui.scriptPreviewCollapsed).toBe(true);
        ui.toggleScriptPreview();
        expect(ui.scriptPreviewCollapsed).toBe(false);
    });

    it('setScriptPreviewWidthPct clamp 到 [20,70]', () => {
        const ui = useUiStore();
        ui.setScriptPreviewWidthPct(50);
        expect(ui.scriptPreviewWidthPct).toBe(50);
        ui.setScriptPreviewWidthPct(5);   // 下越界 → 20
        expect(ui.scriptPreviewWidthPct).toBe(20);
        ui.setScriptPreviewWidthPct(99);  // 上越界 → 70
        expect(ui.scriptPreviewWidthPct).toBe(70);
    });

    it('改动写入 localStorage', async () => {
        const ui = useUiStore();
        ui.toggleScriptPreview();
        ui.setScriptPreviewWidthPct(42);
        await nextTick();
        const raw = localStorage.getItem(KEY);
        expect(raw).toBeTruthy();
        const parsed = JSON.parse(raw!);
        expect(parsed.collapsed).toBe(true);
        expect(parsed.widthPct).toBe(42);
    });

    it('重载从 localStorage 恢复', () => {
        localStorage.setItem(KEY, JSON.stringify({ collapsed: true, widthPct: 60 }));
        setActivePinia(createPinia());
        const ui = useUiStore();
        expect(ui.scriptPreviewCollapsed).toBe(true);
        expect(ui.scriptPreviewWidthPct).toBe(60);
    });

    it('损坏 / 越界的持久化值用默认兜底', () => {
        // 越界宽度被 clamp，非法 collapsed 退默认 false
        localStorage.setItem(KEY, JSON.stringify({ collapsed: 'nope', widthPct: 999 }));
        setActivePinia(createPinia());
        const ui = useUiStore();
        expect(ui.scriptPreviewCollapsed).toBe(false);
        expect(ui.scriptPreviewWidthPct).toBe(70);   // clamp 上界

        // 完全坏的 JSON
        localStorage.setItem(KEY, '{not json');
        setActivePinia(createPinia());
        const ui2 = useUiStore();
        expect(ui2.scriptPreviewCollapsed).toBe(false);
        expect(ui2.scriptPreviewWidthPct).toBe(35);
    });
});
