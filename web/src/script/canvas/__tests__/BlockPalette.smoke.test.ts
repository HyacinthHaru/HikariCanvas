// @vitest-environment happy-dom
/**
 * 0.7.0-P4-D2：BlockPalette 渲染 smoke。
 *
 * <p>验证：按 category 分组渲染 9 个可拖动作积木（含 if）；触发器不在 palette；
 * 项 pointerdown 触发 emit('paletteDown', kind, ev)；lock 态加 hc-palette-locked 类
 * （禁拖）。锁 locale=zh 断言中文分组标题。</p>
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';

import BlockPalette from '../BlockPalette.vue';
import { useUiStore } from '@/stores/ui';
import { useProjectStore } from '@/stores/project';
import { ACTION_DEFS } from '../../model/blockDefs';

describe('BlockPalette 渲染 smoke', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        useUiStore().locale = 'zh';
    });

    it('挂载不崩 + 渲染全部 9 个动作积木项', async () => {
        const w = mount(BlockPalette);
        await nextTick();
        const items = w.findAll('.hc-palette-item');
        expect(items.length).toBe(Object.keys(ACTION_DEFS).length); // 9
        // 各 kind 都有对应 data-block-kind
        for (const kind of Object.keys(ACTION_DEFS)) {
            expect(w.find(`[data-block-kind="${kind}"]`).exists()).toBe(true);
        }
    });

    it('触发器不在 palette（无 data-block-kind="wallReady"）', async () => {
        const w = mount(BlockPalette);
        await nextTick();
        expect(w.find('[data-block-kind="wallReady"]').exists()).toBe(false);
        expect(w.find('[data-block-kind="timer"]').exists()).toBe(false);
    });

    it('分组标题渲染（中文）', async () => {
        const w = mount(BlockPalette);
        await nextTick();
        const text = w.text();
        expect(text).toContain('动作');
        expect(text).toContain('时间轴');
        expect(text).toContain('控制');
        expect(text).toContain('命令');
    });

    it('项 pointerdown（左键）→ emit paletteDown(kind, ev)', async () => {
        const w = mount(BlockPalette);
        await nextTick();
        const logItem = w.find('[data-block-kind="log"]');
        await logItem.trigger('pointerdown', { button: 0 });
        const emitted = w.emitted('paletteDown');
        expect(emitted).toBeTruthy();
        expect(emitted![0][0]).toBe('log');
    });

    it('中键 pointerdown 不 emit（留给 pan）', async () => {
        const w = mount(BlockPalette);
        await nextTick();
        await w.find('[data-block-kind="log"]').trigger('pointerdown', { button: 1 });
        expect(w.emitted('paletteDown')).toBeFalsy();
    });

    it('lock 态：加 hc-palette-locked 类 + 不 emit', async () => {
        useProjectStore().lockedAt = Date.now();
        const w = mount(BlockPalette);
        await nextTick();
        expect(w.find('.hc-palette-locked').exists()).toBe(true);
        await w.find('[data-block-kind="log"]').trigger('pointerdown', { button: 0 });
        expect(w.emitted('paletteDown')).toBeFalsy();
    });
});
