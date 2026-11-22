// @vitest-environment happy-dom
/**
 * useCanvasShortcuts 单元测试：Esc 输入框守卫 + Ctrl+V 让路原生 paste 事件 + 工具切换键。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, defineComponent, effectScope, h } from 'vue';
import { createPinia, setActivePinia } from 'pinia';

const wsSendMock = vi.fn().mockReturnValue('sent-id');
vi.mock('@/network/wsClient', () => ({
    getWsClient: () => ({ send: wsSendMock }),
    createWsClient: () => ({ send: wsSendMock }),
}));

import { useCanvasShortcuts } from '../useCanvasShortcuts';
import { useUiStore } from '@/stores/ui';

describe('Esc 快捷键', () => {
    let scope: ReturnType<typeof effectScope>;

    beforeEach(() => {
        setActivePinia(createPinia());
        document.body.innerHTML = '';
        scope = effectScope();
        scope.run(() => useCanvasShortcuts());
    });

    afterEach(() => {
        scope.stop();
    });

    function pressEscape(): void {
        window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    }

    it('焦点在画布上时清空选中（原有行为）', () => {
        const ui = useUiStore();
        ui.setTool('select');
        ui.selectMany(['e-a', 'e-b']);

        pressEscape();

        expect(ui.selectedCount).toBe(0);
    });

    it('焦点在输入框里时不动选中', () => {
        const ui = useUiStore();
        ui.setTool('select');
        ui.selectMany(['e-a', 'e-b']);

        const input = document.createElement('input');
        document.body.appendChild(input);
        input.focus();
        expect(document.activeElement).toBe(input);

        pressEscape();

        expect(ui.selectedCount).toBe(2);
    });

    it('焦点在富文本（contenteditable）里时不动选中', () => {
        const ui = useUiStore();
        ui.setTool('select');
        ui.selectMany(['e-a']);

        const div = document.createElement('div');
        div.setAttribute('contenteditable', 'true');
        document.body.appendChild(div);
        div.focus();

        pressEscape();

        expect(ui.selectedCount).toBe(1);
    });

    it('焦点在输入框里时也不把绘制工具切回选择工具', () => {
        const ui = useUiStore();
        ui.setTool('circle');

        const input = document.createElement('input');
        document.body.appendChild(input);
        input.focus();

        pressEscape();

        expect(ui.activeTool).toBe('circle');
    });
});

describe('useCanvasShortcuts — Ctrl+V 不再拦截 paste 与工具快捷键', () => {
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
        document.body.innerHTML = '';
        wsSendMock.mockClear();
        wsSendMock.mockReturnValue('sent-id');
    });

    it('Ctrl+V keydown：不调用 preventDefault（让 native paste event 自然 fire）', () => {
        const h1 = mountShortcuts();
        const e = pressKey({ key: 'v', ctrl: true });
        expect(e.defaultPrevented).toBe(false);
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

    it('裸 C → circle 工具', () => {
        const h1 = mountShortcuts();
        pressKey({ key: 'c' });
        expect(h1.ui.activeTool).toBe('circle');
        h1.app.unmount();
    });

    it('裸 H → hand 工具', () => {
        const h1 = mountShortcuts();
        pressKey({ key: 'h' });
        expect(h1.ui.activeTool).toBe('hand');
        h1.app.unmount();
    });
});
