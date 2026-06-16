// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { setActivePinia, createPinia } from 'pinia';

const { exportSpy } = vi.hoisted(() => ({ exportSpy: vi.fn(async () => ({ ok: true })) }));
vi.mock('@/composables/useProjectExport', () => ({ useProjectExport: () => ({ exportProject: exportSpy }) }));
vi.mock('@/network/wsClient', () => ({ getWsClient: () => ({ send: vi.fn(), sendWithAck: vi.fn(async () => ({})) }) }));

import TopBar from '../TopBar.vue';
import { useUiStore } from '@/stores/ui';

describe('TopBar export entry', () => {
    beforeEach(() => { setActivePinia(createPinia()); useUiStore().locale = 'zh'; });

    it('renders an 导出工程 item that calls exportProject', async () => {
        const wrapper = mount(TopBar);
        // OverflowMenu 的 slot 内容只在 popover 打开后渲染（v-if="open"）；先点 … 打开。
        const moreBtn = wrapper.find('button[aria-label="更多"]');
        expect(moreBtn.exists()).toBe(true);
        await moreBtn.trigger('click');

        const btn = wrapper.findAll('button').find((b) => b.text().includes('导出工程'));
        expect(btn).toBeTruthy();
        await btn!.trigger('click');
        expect(exportSpy).toHaveBeenCalledOnce();
    });
});
