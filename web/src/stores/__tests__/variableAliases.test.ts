/**
 * 0.4.2 variableAliasStore 单测。
 *
 * <p>纯 Pinia store 逻辑（无 Vue 组件 / DOM），用 vitest node 跑。覆盖 get/set/remove
 * /clear/reset/initAliases/has + size computed。</p>
 */
import { describe, expect, it, beforeEach } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useVariableAliasStore } from '../variableAliases';

beforeEach(() => {
    setActivePinia(createPinia());
});

describe('variableAliasStore', () => {
    it('初始为空 Map', () => {
        const s = useVariableAliasStore();
        expect(s.aliases.size).toBe(0);
        expect(s.size).toBe(0);
        expect(s.get('any')).toBe(null);
        expect(s.has('any')).toBe(false);
    });

    it('set + get 往返', () => {
        const s = useVariableAliasStore();
        s.set('user:w-abc/red', '红队');
        expect(s.get('user:w-abc/red')).toBe('红队');
        expect(s.has('user:w-abc/red')).toBe(true);
        expect(s.size).toBe(1);
    });

    it('set 覆盖已有别名', () => {
        const s = useVariableAliasStore();
        s.set('k1', 'First');
        s.set('k1', 'Second');
        expect(s.get('k1')).toBe('Second');
        expect(s.size).toBe(1);
    });

    it('支持跨 namespace 的 fullName 形态', () => {
        const s = useVariableAliasStore();
        s.set('user:w-abc/u', 'User');
        s.set('schedule:w-abc/eta_seconds', 'ETA');
        s.set('system/server.time', '时间');
        s.set('papi/%player_name%', '玩家名');
        expect(s.size).toBe(4);
        expect(s.get('user:w-abc/u')).toBe('User');
        expect(s.get('schedule:w-abc/eta_seconds')).toBe('ETA');
        expect(s.get('system/server.time')).toBe('时间');
        expect(s.get('papi/%player_name%')).toBe('玩家名');
    });

    it('remove 已存在键 → 删除', () => {
        const s = useVariableAliasStore();
        s.set('a', 'A');
        s.set('b', 'B');
        s.remove('a');
        expect(s.has('a')).toBe(false);
        expect(s.has('b')).toBe(true);
        expect(s.size).toBe(1);
    });

    it('remove 不存在键 → noop', () => {
        const s = useVariableAliasStore();
        s.set('a', 'A');
        const before = s.aliases;
        s.remove('nonexistent');
        // 不存在时 Map 实例不重建（保持 ref 引用）
        expect(s.aliases).toBe(before);
        expect(s.size).toBe(1);
    });

    it('clear 清空所有别名', () => {
        const s = useVariableAliasStore();
        s.set('a', 'A');
        s.set('b', 'B');
        s.clear();
        expect(s.size).toBe(0);
        expect(s.get('a')).toBe(null);
    });

    it('reset == clear', () => {
        const s = useVariableAliasStore();
        s.set('a', 'A');
        s.reset();
        expect(s.size).toBe(0);
    });

    it('initAliases 批量初始化（覆盖旧内容）', () => {
        const s = useVariableAliasStore();
        s.set('stale', 'OldValue');
        s.initAliases({
            'user:w-abc/red': '红队',
            'user:w-abc/blue': '蓝队',
        });
        expect(s.size).toBe(2);
        expect(s.get('stale')).toBe(null);
        expect(s.get('user:w-abc/red')).toBe('红队');
        expect(s.get('user:w-abc/blue')).toBe('蓝队');
    });

    it('initAliases(null) / initAliases(undefined) 清空', () => {
        const s = useVariableAliasStore();
        s.set('x', 'X');
        s.initAliases(null);
        expect(s.size).toBe(0);
        s.set('x', 'X');
        s.initAliases(undefined);
        expect(s.size).toBe(0);
    });

    it('initAliases({}) 等同 clear', () => {
        const s = useVariableAliasStore();
        s.set('x', 'X');
        s.initAliases({});
        expect(s.size).toBe(0);
    });

    it('set 改变 ref 引用以触发 Vue 响应（Map mutation alone 不触发）', () => {
        const s = useVariableAliasStore();
        const initial = s.aliases;
        s.set('a', 'A');
        // 重要：set 必须重建 Map 实例（赋新 ref 才能触发响应）
        expect(s.aliases).not.toBe(initial);
    });

    it('remove 改变 ref 引用以触发 Vue 响应', () => {
        const s = useVariableAliasStore();
        s.set('a', 'A');
        const before = s.aliases;
        s.remove('a');
        expect(s.aliases).not.toBe(before);
    });
});
