// @vitest-environment happy-dom
/**
 * 变量面板的 ESC 分层。
 *
 * <p>行内输入框 / 子弹窗里按 ESC 只该退出那一层。事件会一路冒到窗口上的全局监听，不拦的话
 * 一次 ESC 连退两层，整个面板跟着关掉，用户刚点开的编辑框和面板一起没了。</p>
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';

import VariablePanel from '../VariablePanel.vue';
import { useUiStore } from '@/stores/ui';
import { useProjectStore } from '@/stores/project';
import { useVariableStore } from '@/stores/variables';
import type { Variable } from '@/types/variable';

vi.mock('@/network/wsClient', () => ({
    getWsClient: () => ({
        sendVariableSet: vi.fn(),
        sendVariableDelete: vi.fn(),
        sendVariableAliasSet: vi.fn(),
        sendVariableAliasClear: vi.fn(),
    }),
}));

const FULL_NAME = 'user:w-abc/count';

function mkVar(): Variable {
    return {
        namespace: 'user:w-abc', key: 'count', type: 'NUMBER',
        defaultValue: '0', currentValue: '5', updatedAt: 0, ttl: 0, source: 'manual',
    };
}

beforeEach(() => {
    setActivePinia(createPinia());
    useUiStore().locale = 'zh';
    useUiStore().variablePanelOpen = true;
});

function mountPanel() {
    useProjectStore().setWallMeta('w-abc', null, null, null, null);
    useVariableStore().set(FULL_NAME, mkVar());
    return mount(VariablePanel, { attachTo: document.body });
}

function findByTitle(w: ReturnType<typeof mountPanel>, title: string) {
    return w.findAll('button').find((b) => b.attributes('title') === title);
}

function aliasInput(w: ReturnType<typeof mountPanel>) {
    return w.findAll('input').find((i) => i.attributes('placeholder')?.includes('起一个短名'));
}

describe('VariablePanel — ESC 分层', () => {
    it('别名编辑框里按 ESC：只收起编辑框，面板还开着', async () => {
        const w = mountPanel();
        await nextTick();

        await findByTitle(w, '改别名')!.trigger('click');
        await nextTick();
        expect(aliasInput(w)).toBeTruthy();

        await aliasInput(w)!.trigger('keydown', { key: 'Escape' });
        await nextTick();

        expect(aliasInput(w)).toBeFalsy();
        expect(useUiStore().variablePanelOpen).toBe(true);
        w.unmount();
    });

    it('焦点不在编辑框上时按 ESC：先退掉编辑态，面板仍开着；再按一次才关面板', async () => {
        const w = mountPanel();
        await nextTick();

        await findByTitle(w, '改别名')!.trigger('click');
        await nextTick();

        window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
        await nextTick();
        expect(aliasInput(w)).toBeFalsy();
        expect(useUiStore().variablePanelOpen).toBe(true);

        window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
        await nextTick();
        expect(useUiStore().variablePanelOpen).toBe(false);
        w.unmount();
    });

    it('删除确认展开时按 ESC：只收起确认，面板还开着', async () => {
        const w = mountPanel();
        await nextTick();

        await findByTitle(w, '删除')!.trigger('click');
        await nextTick();
        expect(w.text()).toContain('确定要删除变量');

        window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
        await nextTick();
        expect(w.text()).not.toContain('确定要删除变量');
        expect(useUiStore().variablePanelOpen).toBe(true);
        w.unmount();
    });

    it('什么都没展开时按 ESC 照常关面板', async () => {
        const w = mountPanel();
        await nextTick();

        window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
        await nextTick();
        expect(useUiStore().variablePanelOpen).toBe(false);
        w.unmount();
    });
});
