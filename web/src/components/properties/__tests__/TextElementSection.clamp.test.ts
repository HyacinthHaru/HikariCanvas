// @vitest-environment happy-dom
/**
 * 文本属性段：字号 / 行高 / 字距 / 描边 / 阴影 / 光晕的越界输入在发出去之前夹回范围内。
 *
 * <p>这些字段此前完全不夹（输入框连 max 都没有），超界的帧被后端整条拒收，本地却已经
 * 乐观改过：编辑器显示 999 号字、游戏里还是原样，而且没有任何提示。</p>
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';

import TextElementSection from '../TextElementSection.vue';
import { useUiStore } from '@/stores/ui';
import {
    MAX_FONT_SIZE, MAX_GLOW_RADIUS, MAX_LETTER_SPACING, MAX_LINE_HEIGHT,
    MAX_SHADOW_OFFSET, MAX_STROKE_WIDTH, MIN_LETTER_SPACING, MIN_LINE_HEIGHT,
} from '@/constants/elementLimits';
import type { TextElement } from '@/types/protocol';

vi.mock('@/network/wsClient', () => ({ getWsClient: () => ({ send: vi.fn() }) }));

function makeText(): TextElement {
    return {
        id: 't1', type: 'text', x: 0, y: 0, w: 100, h: 20, rotation: 0,
        visible: true, locked: false,
        text: 'hi', fontId: 'inter', fontSize: 16, align: 'left', color: '#000000',
        letterSpacing: 0, lineHeight: 1.2, vertical: false,
        effects: {
            stroke: { width: 2, color: '#000000' },
            shadow: { dx: 2, dy: 2, color: '#000000' },
            glow: { radius: 3, color: '#33CCFF' },
        },
    } as unknown as TextElement;
}

function mountSection() {
    return mount(TextElementSection, {
        props: { element: makeText(), locked: false },
        global: {
            stubs: {
                VariableChipEditor: true,
                VariablePicker: true,
                TextElementVariableHints: true,
                ColorInput: true,
            },
        },
    });
}

/** 按 label 文案找到对应的数字输入框（模板里 label 与 input 同在一个 <label> 内）。 */
function inputAfterLabel(w: ReturnType<typeof mountSection>, label: string): HTMLInputElement {
    const labels = w.findAll('label');
    const hit = labels.find((l) => l.text().includes(label) && l.find('input[type="number"]').exists());
    expect(hit, `找不到含「${label}」的数字输入框`).toBeTruthy();
    return hit!.find('input[type="number"]').element as HTMLInputElement;
}

function emitOf(w: ReturnType<typeof mountSection>, name: 'update' | 'updateDebounced') {
    const events = w.emitted(name) as unknown[][] | undefined;
    expect(events, `expected ${name} emit`).toBeTruthy();
    return events![events!.length - 1][0] as Record<string, unknown>;
}

async function typeInto(el: HTMLInputElement, value: string) {
    el.value = value;
    el.dispatchEvent(new Event('input'));
}

beforeEach(() => {
    setActivePinia(createPinia());
    useUiStore().locale = 'zh';
    // onMounted 会拉字体列表；测试里直接给个空表
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => ({ fonts: [] }) })));
});

describe('TextElementSection — 越界输入钳位', () => {
    it('字号超上限 → 夹到 512；负数 → 夹到 1', async () => {
        const w = mountSection();
        await typeInto(inputAfterLabel(w, '字号'), '9999');
        expect(emitOf(w, 'updateDebounced')).toEqual({ fontSize: MAX_FONT_SIZE });
        await typeInto(inputAfterLabel(w, '字号'), '-8');
        expect(emitOf(w, 'updateDebounced')).toEqual({ fontSize: 1 });
    });

    it('行高夹到 [0.5, 4]', async () => {
        const w = mountSection();
        await typeInto(inputAfterLabel(w, '行高'), '99');
        expect(emitOf(w, 'updateDebounced')).toEqual({ lineHeight: MAX_LINE_HEIGHT });
        await typeInto(inputAfterLabel(w, '行高'), '0.1');
        expect(emitOf(w, 'updateDebounced')).toEqual({ lineHeight: MIN_LINE_HEIGHT });
    });

    it('字距夹到 [-32, 128]', async () => {
        const w = mountSection();
        await typeInto(inputAfterLabel(w, '字间距'), '999');
        expect(emitOf(w, 'updateDebounced')).toEqual({ letterSpacing: MAX_LETTER_SPACING });
        await typeInto(inputAfterLabel(w, '字间距'), '-999');
        expect(emitOf(w, 'updateDebounced')).toEqual({ letterSpacing: MIN_LETTER_SPACING });
    });

    it('描边宽度夹到 [0, 128]（原来 parseInt(...)||0 放行负数）', async () => {
        const w = mountSection();
        await typeInto(inputAfterLabel(w, 'width'), '-5');
        expect((emitOf(w, 'update').effects as { stroke: { width: number } }).stroke.width).toBe(0);
        await typeInto(inputAfterLabel(w, 'width'), '9999');
        expect((emitOf(w, 'update').effects as { stroke: { width: number } }).stroke.width)
            .toBe(MAX_STROKE_WIDTH);
    });

    it('阴影偏移夹到 ±128、光晕半径夹到 [0, 64]', async () => {
        const w = mountSection();
        await typeInto(inputAfterLabel(w, 'dx'), '9999');
        expect((emitOf(w, 'update').effects as { shadow: { dx: number } }).shadow.dx)
            .toBe(MAX_SHADOW_OFFSET);
        await typeInto(inputAfterLabel(w, 'radius'), '9999');
        expect((emitOf(w, 'update').effects as { glow: { radius: number } }).glow.radius)
            .toBe(MAX_GLOW_RADIUS);
    });

    it('范围内的值原样通过', async () => {
        const w = mountSection();
        await typeInto(inputAfterLabel(w, '字号'), '24');
        expect(emitOf(w, 'updateDebounced')).toEqual({ fontSize: 24 });
    });
});
