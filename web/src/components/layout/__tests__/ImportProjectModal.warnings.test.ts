// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { setActivePinia, createPinia } from 'pinia';

const { importSpy } = vi.hoisted(() => ({
    importSpy: vi.fn(async () => ({
        ok: true,
        warnings: [
            { kind: 'missing-font', detail: 'Arial' },
            { kind: 'animation-flattened', detail: '' },
            { kind: 'script-command-blocked', detail: 'give-diamond' },
        ],
    })),
}));
vi.mock('@/composables/useProjectImport', () => ({ useProjectImport: () => ({ importProject: importSpy }) }));

import ImportProjectModal from '../ImportProjectModal.vue';
import { useUiStore } from '@/stores/ui';

describe('ImportProjectModal warnings → 大白话', () => {
    beforeEach(() => { setActivePinia(createPinia()); importSpy.mockClear(); });

    it('renders human-readable warning text (zh), not raw kind', async () => {
        useUiStore().locale = 'zh';
        const wrapper = mount(ImportProjectModal, { props: { open: true } });
        await (wrapper.vm as unknown as { doImport: (f: File) => Promise<void> })
            .doImport(new File([new Uint8Array([1])], 'x.canvas'));
        const text = wrapper.text();
        expect(text).toContain('已用默认字体代替');     // missing-font(d) 大白话
        expect(text).toContain('Arial');                // detail 仍嵌进文案
        expect(text).toContain('已按初始样子导入');     // animation-flattened 大白话（无参）
        expect(text).toContain('give-diamond');         // script-command-blocked detail
        // 不暴露原始 kind 码
        expect(text).not.toContain('missing-font');
        expect(text).not.toContain('animation-flattened');
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
        // en 文案存在且非原始 kind
        expect(text.toLowerCase()).toContain('default font');
    });
});
