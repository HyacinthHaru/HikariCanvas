// @vitest-environment happy-dom
/**
 * 属性面板 transform 段：越界输入在发出去之前就夹回范围内 + 笔刷宽高不可编辑。
 *
 * <p>面板是"先改本地再发帧"。值超出后端 ElementValidator 范围时整帧被拒收，而本地已经按新值
 * 画了：编辑器一个样、游戏里另一个样，且此前连提示都没有。HTML 的 min/max 只管上下箭头，
 * 手输和粘贴都绕得过去，所以必须在 JS 这层夹。</p>
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';

import TransformSection from '../TransformSection.vue';
import { useUiStore } from '@/stores/ui';
import { MAX_COORD, MAX_DIM } from '@/constants/elementLimits';
import type { Element } from '@/types/protocol';

function makeElement(type = 'rect'): Element {
    return {
        id: 'e1', type, x: 10, y: 10, w: 20, h: 20, rotation: 0,
        visible: true, locked: false,
    } as unknown as Element;
}

function mountSection(element: Element) {
    return mount(TransformSection, { props: { element, locked: false } });
}

/** 按顺序取 x / y / w / h / rotation 五个数字输入框。 */
function numberInputs(w: ReturnType<typeof mountSection>) {
    return w.findAll('input[type="number"]');
}

/** 取最近一次 updateDebounced 的 patch。 */
function lastDebounced(w: ReturnType<typeof mountSection>): Record<string, unknown> {
    const events = w.emitted('updateDebounced') as unknown[][] | undefined;
    expect(events, 'expected an updateDebounced emit').toBeTruthy();
    return events![events!.length - 1][0] as Record<string, unknown>;
}

beforeEach(() => {
    setActivePinia(createPinia());
    useUiStore().locale = 'zh';
});

describe('TransformSection — 越界输入钳位', () => {
    it('x / y 超过 ±10000 → 夹到边界', async () => {
        const w = mountSection(makeElement());
        await numberInputs(w)[0].setValue('999999');
        expect(lastDebounced(w)).toEqual({ x: MAX_COORD });
        await numberInputs(w)[1].setValue('-999999');
        expect(lastDebounced(w)).toEqual({ y: -MAX_COORD });
    });

    it('w / h 手输负数 / 0 → 夹到 1（负半径会让画布 API 直接抛错）', async () => {
        const w = mountSection(makeElement());
        await numberInputs(w)[2].setValue('-5');
        expect(lastDebounced(w)).toEqual({ w: 1 });
        await numberInputs(w)[3].setValue('0');
        expect(lastDebounced(w)).toEqual({ h: 1 });
    });

    it('w / h 超上限 → 夹到 10000', async () => {
        const w = mountSection(makeElement());
        await numberInputs(w)[2].setValue('88888');
        expect(lastDebounced(w)).toEqual({ w: MAX_DIM });
    });

    it('范围内的值原样通过', async () => {
        const w = mountSection(makeElement());
        await numberInputs(w)[0].setValue('123');
        expect(lastDebounced(w)).toEqual({ x: 123 });
    });
});

describe('TransformSection — 笔刷宽高只读', () => {
    it('brush 元素的 w / h 输入框禁用（后端不接受 brush 的 w/h，发了必被拒）', () => {
        const w = mountSection(makeElement('brush'));
        const inputs = numberInputs(w);
        expect((inputs[2].element as HTMLInputElement).disabled).toBe(true);
        expect((inputs[3].element as HTMLInputElement).disabled).toBe(true);
        // x / y / rotation 后端是支持的，照常可改
        expect((inputs[0].element as HTMLInputElement).disabled).toBe(false);
        expect((inputs[1].element as HTMLInputElement).disabled).toBe(false);
        expect((inputs[4].element as HTMLInputElement).disabled).toBe(false);
    });

    it('非 brush 元素的 w / h 正常可编辑', () => {
        const w = mountSection(makeElement('rect'));
        const inputs = numberInputs(w);
        expect((inputs[2].element as HTMLInputElement).disabled).toBe(false);
        expect((inputs[3].element as HTMLInputElement).disabled).toBe(false);
    });
});
