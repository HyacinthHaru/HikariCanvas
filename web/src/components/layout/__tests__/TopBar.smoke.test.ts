// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { setActivePinia, createPinia } from 'pinia';

const { exportSpy } = vi.hoisted(() => ({ exportSpy: vi.fn(async () => ({ ok: true })) }));
vi.mock('@/composables/useProjectExport', () => ({ useProjectExport: () => ({ exportProject: exportSpy }) }));
vi.mock('@/network/wsClient', () => ({ getWsClient: () => ({ send: vi.fn(), sendWithAck: vi.fn(async () => ({})) }) }));

import TopBar from '../TopBar.vue';
import { useUiStore } from '@/stores/ui';

describe('TopBar import / export entries', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        useUiStore().locale = 'zh';
        exportSpy.mockClear();
    });

    it('renders 导入工程 item that opens the modal', async () => {
        const wrapper = mount(TopBar);
        const moreBtn = wrapper.find('button[aria-label="更多"]');
        expect(moreBtn.exists()).toBe(true);
        await moreBtn.trigger('click');

        const btn = wrapper.findAll('button').find((b) => b.text().includes('导入工程'));
        expect(btn).toBeTruthy();
        await btn!.trigger('click');
        const modal = wrapper.findComponent({ name: 'ImportProjectModal' });
        expect(modal.exists()).toBe(true);
        expect(modal.props('open')).toBe(true);
    });

    it('renders an 导出工程 item that calls exportProject', async () => {
        const wrapper = mount(TopBar);
        const moreBtn = wrapper.find('button[aria-label="更多"]');
        expect(moreBtn.exists()).toBe(true);
        await moreBtn.trigger('click');

        const btn = wrapper.findAll('button').find((b) => b.text().includes('导出工程'));
        expect(btn).toBeTruthy();
        await btn!.trigger('click');
        expect(exportSpy).toHaveBeenCalledOnce();
    });
});
