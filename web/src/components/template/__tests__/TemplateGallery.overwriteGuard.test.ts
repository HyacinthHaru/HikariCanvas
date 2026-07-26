// @vitest-environment happy-dom
/**
 * 套用模板前的二次确认要看<b>所有图层</b>。
 *
 * <p>套用模板是整个工程被换掉，不是只换当前图层。判断"画布上有没有东西"时只看当前图层的
 * 话，站在一个空图层上套模板就一句话不问、直接把别的图层画的全清了。</p>
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';

import TemplateGallery from '../TemplateGallery.vue';
import { useTemplatesStore } from '@/stores/templates';
import { useProjectStore } from '@/stores/project';
import { useNetworkStore } from '@/stores/network';
import { useUiStore } from '@/stores/ui';
import type { TemplateSpec } from '@/types/template';
import type { ProjectState } from '@/types/protocol';

const sendWithAck = vi.fn(async (_op: string, _payload?: unknown, _timeoutMs?: number) => ({}));
vi.mock('@/network/wsClient', () => ({
    getWsClient: () => ({ sendWithAck }),
}));

function makeTemplate(): TemplateSpec {
    return { id: 'tpl-1', name: '测试模板', params: {} } as unknown as TemplateSpec;
}

/** 两层工程：第 0 层（激活层）空，第 1 层有一个元素。 */
function twoLayerState(activeLayerEmpty: boolean): ProjectState {
    return {
        version: 1,
        canvas: { widthMaps: 1, heightMaps: 1, background: '#000000' },
        activeLayerId: 'l-1',
        layers: [
            {
                id: 'l-1', name: '图层 1', visible: true, locked: false, opacity: 1,
                elements: activeLayerEmpty ? [] : [{ id: 'e-0', type: 'rect' }],
            },
            {
                id: 'l-2', name: '图层 2', visible: true, locked: false, opacity: 1,
                elements: [{ id: 'e-1', type: 'rect' }],
            },
        ],
    } as unknown as ProjectState;
}

beforeEach(() => {
    setActivePinia(createPinia());
    useUiStore().locale = 'zh';
    sendWithAck.mockClear();
});

function mountGallery(state: ProjectState | null) {
    const templates = useTemplatesStore();
    templates.setTemplates([makeTemplate()]);
    templates.openGallery();
    const project = useProjectStore();
    if (state) project.setSnapshot(state);
    const net = useNetworkStore();
    net.authenticated = true;
    return mount(TemplateGallery, { attachTo: document.body });
}

function applyButton(w: ReturnType<typeof mountGallery>) {
    return w.findAll('button').find((b) => b.text().includes('应用模板'));
}

/** 二次确认阶段的「确认覆盖」按钮。 */
function confirmButton(w: ReturnType<typeof mountGallery>) {
    return w.findAll('button').find((b) => b.text().includes('确认覆盖'));
}

describe('TemplateGallery — 覆盖确认', () => {
    it('激活层是空的、别的图层有内容 → 仍然要二次确认，不直接发包', async () => {
        const w = mountGallery(twoLayerState(true));
        await nextTick();

        await applyButton(w)!.trigger('click');
        await nextTick();

        expect(sendWithAck).not.toHaveBeenCalled();
        expect(w.text()).toContain('应用模板会清空当前所有元素');
        w.unmount();
    });

    it('确认之后才真正发 template.apply', async () => {
        const w = mountGallery(twoLayerState(true));
        await nextTick();

        await applyButton(w)!.trigger('click');
        await nextTick();
        await confirmButton(w)!.trigger('click');
        await nextTick();

        expect(sendWithAck).toHaveBeenCalledTimes(1);
        expect(sendWithAck.mock.calls[0][0]).toBe('template.apply');
        w.unmount();
    });

    it('所有图层都空 → 不拦，直接发包', async () => {
        const empty = twoLayerState(true);
        empty.layers[1].elements = [];
        const w = mountGallery(empty);
        await nextTick();

        await applyButton(w)!.trigger('click');
        await nextTick();

        expect(sendWithAck).toHaveBeenCalledTimes(1);
        w.unmount();
    });
});
