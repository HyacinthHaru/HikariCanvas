// @vitest-environment happy-dom
/**
 * 0.7.1 积木画布右下角「本脚本变量实时预览」面板 smoke。
 *
 * <p>验证：① 未选规则 → 空状态文案；② 选中规则后列出它涉及的变量（trigger + 动作字段 + ${var:X}）；
 * ③ 实时值来自 VariableStore mirror（store 变 → 行值变）；④ 别名优先显示（VariableAliasStore）；
 * ⑤ user/X 注入 wallId 后能命中 store（resolveFullName 与渲染期一致）；⑥ 折叠按钮收起主体。</p>
 *
 * <p>直接 mount ScriptVariableWatch（不经 BlockCanvas，省 wsClient mock）。喂 project.wallId +
 * variables / aliases store。</p>
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';

import ScriptVariableWatch from '../ScriptVariableWatch.vue';
import { useUiStore } from '@/stores/ui';
import { useProjectStore } from '@/stores/project';
import { useScriptEditStore } from '@/stores/scriptEdit';
import { useScriptStore } from '@/stores/scripts';
import { useVariableStore } from '@/stores/variables';
import { useVariableAliasStore } from '@/stores/variableAliases';
import type { Variable } from '@/types/variable';
import type { ScriptRule, ScriptAction } from '@/types/protocol';

function makeRule(actions: ScriptAction[], id = 'sr-1'): ScriptRule {
    return {
        id, wallId: 'w-1', enabled: true, name: id, trigger: { type: 'wallReady' },
        actions, blockLayout: '{}',
    };
}

function userVar(wallId: string, key: string, value: string): Variable {
    return {
        namespace: `user:${wallId}`, key, type: 'STRING',
        defaultValue: null, currentValue: value, updatedAt: Date.now(), ttl: 0, source: 'manual',
    };
}

beforeEach(() => {
    setActivePinia(createPinia());
    useUiStore().locale = 'zh';
    const project = useProjectStore();
    project.wallId = 'w-1';
    try { localStorage.removeItem('hikari-canvas:script-var-watch-collapsed'); } catch { /* ignore */ }
});

describe('ScriptVariableWatch', () => {
    it('未选规则 → 空状态「先选一条规则」', async () => {
        const w = mount(ScriptVariableWatch);
        await nextTick();
        expect(w.find('.hc-var-watch-empty').exists()).toBe(true);
        expect(w.text()).toContain('先选一条规则');
        w.unmount();
    });

    it('规则无变量 → 「还没用到变量」空状态', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([makeRule([{ type: 'log', message: 'hi' }])]);
        useScriptEditStore().selectRule('sr-1');
        const w = mount(ScriptVariableWatch);
        await nextTick();
        expect(w.text()).toContain('还没用到变量');
        expect(w.findAll('.hc-var-watch-row').length).toBe(0);
        w.unmount();
    });

    it('列出 user 变量 + 实时值（resolveFullName 注入 wallId 命中 store）', async () => {
        const variables = useVariableStore();
        variables.set('user:w-1/红队比分', userVar('w-1', '红队比分', '7'));
        const scripts = useScriptStore();
        scripts.initScripts([makeRule([
            { type: 'setVariable', fullName: 'user/红队比分', value: '${var:user/红队比分}' },
        ])]);
        useScriptEditStore().selectRule('sr-1');
        const w = mount(ScriptVariableWatch);
        await nextTick();

        const rows = w.findAll('.hc-var-watch-row');
        expect(rows.length).toBe(1); // 去重：fullName 字段 + value 占位符是同一个变量
        expect(rows[0].find('.hc-var-watch-name').text()).toBe('user/红队比分'); // 无别名 → rawName
        expect(rows[0].find('.hc-var-watch-val').text()).toBe('7');
        w.unmount();
    });

    it('实时更新：store 改值 → 行值跟着变', async () => {
        const variables = useVariableStore();
        variables.set('user:w-1/score', userVar('w-1', 'score', '1'));
        const scripts = useScriptStore();
        scripts.initScripts([makeRule([{ type: 'incrementVariable', fullName: 'user/score', delta: 1 }])]);
        useScriptEditStore().selectRule('sr-1');
        const w = mount(ScriptVariableWatch);
        await nextTick();
        expect(w.find('.hc-var-watch-val').text()).toBe('1');

        variables.set('user:w-1/score', userVar('w-1', 'score', '42'));
        await nextTick();
        expect(w.find('.hc-var-watch-val').text()).toBe('42');
        w.unmount();
    });

    it('别名优先显示（VariableAliasStore）', async () => {
        const variables = useVariableStore();
        variables.set('user:w-1/red', userVar('w-1', 'red', '3'));
        const aliases = useVariableAliasStore();
        aliases.set('user:w-1/red', '红队');
        const scripts = useScriptStore();
        scripts.initScripts([makeRule([{ type: 'incrementVariable', fullName: 'user/red', delta: 1 }])]);
        useScriptEditStore().selectRule('sr-1');
        const w = mount(ScriptVariableWatch);
        await nextTick();
        expect(w.find('.hc-var-watch-name').text()).toBe('红队'); // 别名优先于 rawName
        w.unmount();
    });

    it('store 查不到的变量 → 标 missing + 显示 ??? ', async () => {
        const scripts = useScriptStore();
        scripts.initScripts([makeRule([{ type: 'setVariable', fullName: 'user/ghost', value: 'x' }])]);
        useScriptEditStore().selectRule('sr-1');
        const w = mount(ScriptVariableWatch);
        await nextTick();
        const row = w.find('.hc-var-watch-row');
        expect(row.classes()).toContain('hc-var-watch-row-missing');
        expect(row.find('.hc-var-watch-val').text()).toBe('???');
        w.unmount();
    });

    it('折叠按钮收起主体（计数仍显示）', async () => {
        const variables = useVariableStore();
        variables.set('user:w-1/a', userVar('w-1', 'a', '1'));
        const scripts = useScriptStore();
        scripts.initScripts([makeRule([{ type: 'incrementVariable', fullName: 'user/a', delta: 1 }])]);
        useScriptEditStore().selectRule('sr-1');
        const w = mount(ScriptVariableWatch);
        await nextTick();
        expect(w.find('.hc-var-watch-body').exists()).toBe(true);

        await w.find('.hc-var-watch-head').trigger('click');
        await nextTick();
        // 主体隐藏，标题条 + 计数仍在
        expect(w.find('.hc-var-watch-body').exists()).toBe(false);
        expect(w.find('.hc-var-watch-count').text()).toBe('1');
        w.unmount();
    });
});
