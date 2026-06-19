// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { setActivePinia, createPinia } from 'pinia';

const { importSpy } = vi.hoisted(() => ({
    importSpy: vi.fn(async () => ({ ok: true, warnings: [{ kind: 'missing-font', detail: 'arial' }] })),
}));
vi.mock('@/composables/useProjectImport', () => ({ useProjectImport: () => ({ importProject: importSpy }) }));

import ImportProjectModal from '../ImportProjectModal.vue';
import { useUiStore } from '@/stores/ui';

describe('ImportProjectModal', () => {
    beforeEach(() => { setActivePinia(createPinia()); useUiStore().locale = 'zh'; importSpy.mockClear(); });

    it('renders title and a file picker', () => {
        const wrapper = mount(ImportProjectModal, { props: { open: true } });
        expect(wrapper.text()).toContain('导入工程');
        expect(wrapper.find('input[type="file"]').exists()).toBe(true);
    });

    it('after confirming, calls importProject and shows the warnings list', async () => {
        const wrapper = mount(ImportProjectModal, { props: { open: true } });
        // 模拟选文件 + 确认（实现期按真实交互触发；此处直接调用暴露的处理函数）
        await (wrapper.vm as unknown as { doImport: (f: File) => Promise<void> })
            .doImport(new File([new Uint8Array([1])], 'x.canvas'));
        expect(importSpy).toHaveBeenCalledOnce();
        expect(wrapper.text()).toContain('arial');   // warning detail 渲染
    });

    it('selecting or dropping a file enters the destructive-replace confirm step', async () => {
        const wrapper = mount(ImportProjectModal, { props: { open: true } });
        (wrapper.vm as unknown as { acceptFile: (f: File) => void })
            .acceptFile(new File([new Uint8Array([1])], 'sign.canvas'));
        await wrapper.vm.$nextTick();
        expect(wrapper.text()).toContain('替换');   // importConfirmReplace 含"替换"
    });

    it('file input has no accept filter (so macOS Finder can pick .canvas)', () => {
        const wrapper = mount(ImportProjectModal, { props: { open: true } });
        expect(wrapper.find('input[type="file"]').attributes('accept')).toBeUndefined();
    });
});
