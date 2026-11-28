// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { setActivePinia, createPinia } from 'pinia';

type ImportWarning = { kind: string; detail: string };
type ImportResult = { ok: boolean; warnings: ImportWarning[] };

const { importSpy } = vi.hoisted(() => ({
    importSpy: vi.fn<() => Promise<ImportResult>>(async () => ({
        ok: true,
        warnings: [{ kind: 'missing-font', detail: 'arial' }],
    })),
}));
vi.mock('@/composables/useProjectImport', () => ({ useProjectImport: () => ({ importProject: importSpy }) }));

import ImportProjectModal from '../ImportProjectModal.vue';
import { useUiStore } from '@/stores/ui';

describe('ImportProjectModal', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        useUiStore().locale = 'zh';
        importSpy.mockReset();
        importSpy.mockResolvedValue({
            ok: true,
            warnings: [{ kind: 'missing-font', detail: 'arial' }],
        });
    });

    it('renders title and a file picker', () => {
        const wrapper = mount(ImportProjectModal, { props: { open: true } });
        expect(wrapper.text()).toContain('导入工程');
        expect(wrapper.find('input[type="file"]').exists()).toBe(true);
    });

    it('after confirming, calls importProject and shows the warnings list', async () => {
        const wrapper = mount(ImportProjectModal, { props: { open: true } });
        await (wrapper.vm as unknown as { doImport: (f: File) => Promise<void> })
            .doImport(new File([new Uint8Array([1])], 'x.canvas'));
        expect(importSpy).toHaveBeenCalledOnce();
        expect(wrapper.text()).toContain('arial');
    });

    it('selecting or dropping a file enters the destructive-replace confirm step', async () => {
        const wrapper = mount(ImportProjectModal, { props: { open: true } });
        (wrapper.vm as unknown as { acceptFile: (f: File) => void })
            .acceptFile(new File([new Uint8Array([1])], 'sign.canvas'));
        await wrapper.vm.$nextTick();
        expect(wrapper.text()).toContain('替换');
    });

    it('file input has no accept filter (so macOS Finder can pick .canvas)', () => {
        const wrapper = mount(ImportProjectModal, { props: { open: true } });
        expect(wrapper.find('input[type="file"]').attributes('accept')).toBeUndefined();
    });
});

describe('ImportProjectModal warnings → 可读文案', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        importSpy.mockReset();
        importSpy.mockResolvedValue({
            ok: true,
            warnings: [
                { kind: 'missing-font', detail: 'Arial' },
                { kind: 'animation-flattened', detail: '' },
                { kind: 'asset-rehashed', detail: 'deadbeefdeadbeef' },
                { kind: 'script-command-blocked', detail: 'give-diamond' },
                { kind: 'future-unknown-warning', detail: '' },
            ],
        });
    });

    it('renders human-readable warning text (zh), not raw kind', async () => {
        useUiStore().locale = 'zh';
        const wrapper = mount(ImportProjectModal, { props: { open: true } });
        await (wrapper.vm as unknown as { doImport: (f: File) => Promise<void> })
            .doImport(new File([new Uint8Array([1])], 'x.canvas'));
        const text = wrapper.text();
        expect(text).toContain('已用默认字体代替');
        expect(text).toContain('Arial');
        expect(text).toContain('已按初始样子导入');
        expect(text).toContain('部分图片经安全重编码后已自动更新引用');
        expect(text).toContain('give-diamond');
        expect(text).toContain('future-unknown-warning');
        expect(text).not.toContain('missing-font');
        expect(text).not.toContain('animation-flattened');
        expect(text).not.toContain('asset-rehashed');
        expect(text).not.toContain('deadbeefdeadbeef');
        expect(text).not.toContain('script-command-blocked');
    });

    it('renders human-readable warning text (en)', async () => {
        useUiStore().locale = 'en';
        const wrapper = mount(ImportProjectModal, { props: { open: true } });
        await (wrapper.vm as unknown as { doImport: (f: File) => Promise<void> })
            .doImport(new File([new Uint8Array([1])], 'x.canvas'));
        const text = wrapper.text();
        expect(text).not.toContain('missing-font');
        expect(text).not.toContain('animation-flattened');
        expect(text).not.toContain('asset-rehashed');
        expect(text).not.toContain('deadbeefdeadbeef');
        expect(text).toContain('Some images were re-encoded for safety and their references were updated automatically');
        expect(text).toContain('future-unknown-warning');
        expect(text.toLowerCase()).toContain('default font');
    });
});
