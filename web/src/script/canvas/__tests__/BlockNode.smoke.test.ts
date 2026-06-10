// @vitest-environment happy-dom
/**
 * 0.7.0-P4-C：BlockNode 递归渲染 smoke。
 *
 * <p>核心验证：① 各类 action 渲染不崩 + 标题 / 参数占位文案出现；② if 嵌套递归渲染；
 * ③ {@code data-block-path} <b>与后端 trace blockId 同构</b>（嵌套子块 path 精确）。
 * 照 ScriptEditorOverlay.smoke 范式：happy-dom + pinia + 锁中文 locale。</p>
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';

import BlockNode from '../BlockNode.vue';
import { useUiStore } from '@/stores/ui';
import type { ScriptAction } from '@/types/protocol';

function mountNode(action: ScriptAction, path = 'actions/0') {
    return mount(BlockNode, { props: { action, path } });
}

describe('BlockNode 渲染 smoke', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        useUiStore().locale = 'zh';
    });

    it('setVariable 渲染：标题 + 字段占位（变量名 + 值）', async () => {
        const w = mountNode({ type: 'setVariable', fullName: 'user/score', value: '42' });
        await nextTick();
        expect(w.text()).toContain('设置变量');
        expect(w.text()).toContain('变量:');
        expect(w.text()).toContain('user/score');
        expect(w.text()).toContain('42');
    });

    it('playTimeline 渲染：含 op 原始值 + seekMs 缺省占位 —', async () => {
        const w = mountNode({ type: 'playTimeline', timelineId: 'tl-1', op: 'play' });
        await nextTick();
        expect(w.text()).toContain('播放时间轴');
        expect(w.text()).toContain('tl-1');
        expect(w.text()).toContain('play');
        // seekMs 未携带 → 占位 —
        expect(w.text()).toContain('—');
    });

    it('runCommand 渲染：params 对象 JSON 占位不崩', async () => {
        const w = mountNode({
            type: 'runCommand',
            templateId: 'tpl-x',
            params: { who: 'red', n: '1' },
        });
        await nextTick();
        expect(w.text()).toContain('执行命令');
        expect(w.text()).toContain('tpl-x');
        // params 对象 → JSON 文本占位
        expect(w.text()).toContain('red');
    });

    it('playSound 渲染：四字段全显（声音/音量/音调/范围）', async () => {
        const w = mountNode({
            type: 'playSound',
            soundId: 'block.note_block.harp',
            volume: 1,
            pitch: 1,
            scope: 'near',
        });
        await nextTick();
        expect(w.text()).toContain('播放声音');
        expect(w.text()).toContain('block.note_block.harp');
        expect(w.text()).toContain('音量:');
        expect(w.text()).toContain('音调:');
        expect(w.text()).toContain('范围:');
    });

    it('未知 kind → 兜底未知积木文案不崩', async () => {
        const w = mountNode({ type: 'totallyUnknown' } as unknown as ScriptAction);
        await nextTick();
        expect(w.text()).toContain('未知积木');
        expect(w.text()).toContain('totallyUnknown');
    });

    it('data-block-path = props.path（顶层）', async () => {
        const w = mountNode({ type: 'log', message: 'hi' }, 'actions/3');
        await nextTick();
        expect(w.find('[data-block-path="actions/3"]').exists()).toBe(true);
    });
});

describe('BlockNode if 递归 + path 同构', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        useUiStore().locale = 'zh';
    });

    it('if 嵌套：then 子块 path = actions/0/then/0（与后端 trace blockId 同构）', async () => {
        const ifAction: ScriptAction = {
            type: 'if',
            condition: 'var("user/score") > 5',
            then: [{ type: 'log', message: 'in-then' }],
            else: [{ type: 'wait', ms: 100 }],
        };
        const w = mountNode(ifAction, 'actions/0');
        await nextTick();

        // 条件串占位渲染
        expect(w.text()).toContain('var("user/score") > 5');
        // then 子块精确 path
        expect(w.find('[data-block-path="actions/0/then/0"]').exists()).toBe(true);
        // else 子块精确 path
        expect(w.find('[data-block-path="actions/0/else/0"]').exists()).toBe(true);
        // 子块内容渲染
        expect(w.text()).toContain('in-then');
    });

    it('深层嵌套 if-in-if：path 逐层拼接 actions/0/then/0/then/0', async () => {
        const inner: ScriptAction = {
            type: 'if',
            condition: 'true',
            then: [{ type: 'log', message: 'deep' }],
            else: [],
        };
        const outer: ScriptAction = {
            type: 'if',
            condition: 'true',
            then: [inner],
            else: [],
        };
        const w = mountNode(outer, 'actions/0');
        await nextTick();
        // 内层 if 自身 path
        expect(w.find('[data-block-path="actions/0/then/0"]').exists()).toBe(true);
        // 内层 if 的 then 第 0 块
        expect(w.find('[data-block-path="actions/0/then/0/then/0"]').exists()).toBe(true);
        expect(w.text()).toContain('deep');
    });

    it('if 空分支：显示空槽占位提示', async () => {
        const ifAction: ScriptAction = {
            type: 'if',
            condition: 'true',
            then: [],
            else: [],
        };
        const w = mountNode(ifAction, 'actions/1');
        await nextTick();
        // 两个空槽 → 至少出现占位文案
        expect(w.text()).toContain('把积木拖到这里');
    });
});
