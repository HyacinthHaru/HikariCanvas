// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { setActivePinia, createPinia } from 'pinia';
vi.mock('@/network/wsClient', () => ({ getWsClient: () => ({ send: vi.fn(), sendWithAck: vi.fn(async () => ({})) }) }));
import TopBar from '../TopBar.vue';
import { useUiStore } from '@/stores/ui';

describe('TopBar import entry', () => {
    beforeEach(() => { setActivePinia(createPinia()); useUiStore().locale = 'zh'; });

    it('renders 导入工程 item that opens the modal', async () => {
        const wrapper = mount(TopBar);
        // OverflowMenu 的 slot 内容只在 popover 打开后渲染（v-if="open"）；先点 … 打开。
        const moreBtn = wrapper.find('button[aria-label="更多"]');
        expect(moreBtn.exists()).toBe(true);
        await moreBtn.trigger('click');

        const btn = wrapper.findAll('button').find((b) => b.text().includes('导入工程'));
        expect(btn).toBeTruthy();
        await btn!.trigger('click');
        // ImportProjectModal 始终挂在模板里（:open 控制内容渲染）；点击后 open 为 true → 内容可见。
        const modal = wrapper.findComponent({ name: 'ImportProjectModal' });
        expect(modal.exists()).toBe(true);
        expect(modal.props('open')).toBe(true);
    });
});
