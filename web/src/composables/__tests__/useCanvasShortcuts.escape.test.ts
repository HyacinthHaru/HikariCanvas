// @vitest-environment happy-dom
/**
 * Esc 快捷键的输入框守卫。
 *
 * <p>Esc 在输入框 / 富文本里是"退出这次输入"，不该顺手把画布选中也清了。组件自己的 Esc
 * 处理（变量 chip 编辑器、变量选择器、图标库）只 preventDefault 不 stopPropagation，
 * window 这层必然也会收到——守卫只能写在 window 这层。</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { effectScope } from 'vue';
import { createPinia, setActivePinia } from 'pinia';

vi.mock('@/network/wsClient', () => ({ getWsClient: () => ({ send: vi.fn(() => 'c-1') }) }));

import { useCanvasShortcuts } from '../useCanvasShortcuts';
import { useUiStore } from '@/stores/ui';

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

describe('Esc 快捷键', () => {
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
