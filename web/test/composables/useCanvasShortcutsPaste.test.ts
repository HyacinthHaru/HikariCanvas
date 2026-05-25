/**
 * @vitest-environment happy-dom
 *
 * 2026-05-25 bugfix：Ctrl+V keydown 不再 preventDefault。
 *
 * <p>规范：useCanvasShortcuts 不再持有 Ctrl/Cmd+V handler——paste 路径完全
 * 让给 native `paste` event（由 useCanvasUpload.onPasteImage 分发）。
 * Ctrl+C 复制 / Esc / 工具键（V/M/H/L 等）行为不变。</p>
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, defineComponent, h } from 'vue';
import { createPinia, setActivePinia } from 'pinia';

// WsClient mock：useClipboard 调用 getWsClient()
const wsSendMock = vi.fn().mockReturnValue('sent-id');
vi.mock('@/network/wsClient', () => ({
    getWsClient: () => ({ send: wsSendMock }),
    createWsClient: () => ({ send: wsSendMock }),
}));

import { useCanvasShortcuts } from '@/composables/useCanvasShortcuts';
import { useUiStore } from '@/stores/ui';

function mountShortcuts() {
    let ui: ReturnType<typeof useUiStore> | null = null;
    const Comp = defineComponent({
        setup() {
            useCanvasShortcuts();
            ui = useUiStore();
            return () => h('div');
        },
    });
    const container = document.createElement('div');
    const app = createApp(Comp);
    app.mount(container);
    return { ui: ui!, app, container };
}

function pressKey(opts: { key: string; ctrl?: boolean; meta?: boolean }) {
    const e = new KeyboardEvent('keydown', {
        key: opts.key,
        ctrlKey: !!opts.ctrl,
        metaKey: !!opts.meta,
        bubbles: true,
        cancelable: true,
    });
    window.dispatchEvent(e);
    return e;
}

beforeEach(() => {
    setActivePinia(createPinia());
    wsSendMock.mockClear();
});

afterEach(() => {
    vi.restoreAllMocks();
});

describe('useCanvasShortcuts — Ctrl+V 不再拦截 paste', () => {
    it('Ctrl+V keydown：不调用 preventDefault（让 native paste event 自然 fire）', () => {
        const h1 = mountShortcuts();
        const e = pressKey({ key: 'v', ctrl: true });
        expect(e.defaultPrevented).toBe(false);
        // 也不应调用 ws.send（不再走 element.add 路径）
        expect(wsSendMock).not.toHaveBeenCalled();
        h1.app.unmount();
    });

    it('Cmd+V (mac) keydown：同样不 preventDefault', () => {
        const h1 = mountShortcuts();
        const e = pressKey({ key: 'v', meta: true });
        expect(e.defaultPrevented).toBe(false);
        expect(wsSendMock).not.toHaveBeenCalled();
        h1.app.unmount();
    });

    it('裸 V：不切到 paste，而是切到 select 工具（PS 行为）', () => {
        const h1 = mountShortcuts();
        h1.ui.setTool('brush');
        expect(h1.ui.activeTool).toBe('brush');
        pressKey({ key: 'v' });
        expect(h1.ui.activeTool).toBe('select');
        h1.app.unmount();
    });

    it('Ctrl+C 仍 preventDefault（复制不冲突保留）', () => {
        const h1 = mountShortcuts();
        const e = pressKey({ key: 'c', ctrl: true });
        expect(e.defaultPrevented).toBe(true);
        h1.app.unmount();
    });

    it('裸 C → circle 工具（PS 风格保持）', () => {
        const h1 = mountShortcuts();
        pressKey({ key: 'c' });
        expect(h1.ui.activeTool).toBe('circle');
        h1.app.unmount();
    });

    it('裸 H → hand 工具（M17 F4 保持）', () => {
        const h1 = mountShortcuts();
        pressKey({ key: 'h' });
        expect(h1.ui.activeTool).toBe('hand');
        h1.app.unmount();
    });
});
