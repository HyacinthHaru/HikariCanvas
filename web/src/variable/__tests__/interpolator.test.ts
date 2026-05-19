/**
 * 0.4.0-P2-H interpolator 单测。
 *
 * 用 Pinia + 真 useVariableStore 实例（store 不依赖 DOM，纯逻辑）。
 *
 * 覆盖：
 * 1. 纯文本短路 passthrough
 * 2. 单 ${var:user/X} + wallId 注入
 * 3. 变量不存在 + 无 fallback → "???"
 * 4. 变量不存在 + |fallback=N/A
 * 5. currentValue 空 + fallback → fallback
 * 6. currentValue 空 + 无 fallback + defaultValue → defaultValue
 * 7. currentValue 空 + 无 fallback + defaultValue 空 → "???"
 * 8. 多占位符全部替换
 * 9. missingFullNames 集合
 * 10. referencedFullNames 集合
 * 11. wallId 为 null 时 user/X 走字面查询
 * 12. wallId 注入后内部 fullName 形式正确
 * 13. 空 / null / undefined 输入
 * 14. 显式空 fallback ({@code |fallback=}) 应当用空字符串
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useVariableStore } from '@/stores/variables';
import { interpolate, resolveFullName, UNRESOLVED } from '../interpolator';
import type { Variable } from '@/types/variable';

function mkVar(namespace: string, key: string, current: string | null, def: string | null = null): Variable {
    return {
        namespace,
        key,
        type: 'STRING',
        defaultValue: def,
        currentValue: current,
        updatedAt: Date.now(),
        ttl: 0,
        source: 'manual',
    };
}

describe('interpolator.resolveFullName', () => {
    it('user/X + wallId → user:<wallId>/X', () => {
        expect(resolveFullName('user/红队比分', 'w-abc')).toBe('user:w-abc/红队比分');
    });
    it('user/X + wallId null → 字面 user/X', () => {
        expect(resolveFullName('user/X', null)).toBe('user/X');
    });
    it('bedwars/score + wallId → 不动 bedwars/score', () => {
        expect(resolveFullName('bedwars/score', 'w-abc')).toBe('bedwars/score');
    });
    it('system.time + wallId → 字面 system.time', () => {
        expect(resolveFullName('server.time', 'w-abc')).toBe('server.time');
    });
    // 0.4.0-P3-J：wall.* 注入
    it('wall.id + wallId → system:<wallId>/wall.id', () => {
        expect(resolveFullName('wall.id', 'w-abc')).toBe('system:w-abc/wall.id');
    });
    it('wall.alias + wallId → system:<wallId>/wall.alias', () => {
        expect(resolveFullName('wall.alias', 'w-1')).toBe('system:w-1/wall.alias');
    });
    it('wall.X + wallId null → 字面 wall.X', () => {
        expect(resolveFullName('wall.id', null)).toBe('wall.id');
    });
    // 0.4.0-P3-L：schedule.* 注入
    it('schedule.next_departure + wallId → schedule:<wallId>/next_departure', () => {
        expect(resolveFullName('schedule.next_departure', 'w-abc'))
            .toBe('schedule:w-abc/next_departure');
    });
    it('schedule.eta_minutes + wallId → schedule:<wallId>/eta_minutes', () => {
        expect(resolveFullName('schedule.eta_minutes', 'w-1'))
            .toBe('schedule:w-1/eta_minutes');
    });
    it('schedule.X + wallId null → 字面 schedule.X', () => {
        expect(resolveFullName('schedule.next_departure', null))
            .toBe('schedule.next_departure');
    });
    // 0.4.0 bugfix3（Bug A）：用户直觉的 namespace/key 斜杠语法
    it('schedule/X + wallId → schedule:<wallId>/X (slash 语法)', () => {
        expect(resolveFullName('schedule/eta_seconds', 'w-abc'))
            .toBe('schedule:w-abc/eta_seconds');
    });
    it('schedule 斜杠与点号语法产同 fullName', () => {
        expect(resolveFullName('schedule/next_departure', 'w-1'))
            .toBe(resolveFullName('schedule.next_departure', 'w-1'));
    });
    it('wall/X + wallId → system:<wallId>/wall.X (slash 语法)', () => {
        expect(resolveFullName('wall/id', 'w-abc'))
            .toBe('system:w-abc/wall.id');
    });
    it('slash 语法 + wallId null → 字面查询', () => {
        expect(resolveFullName('schedule/eta_seconds', null))
            .toBe('schedule/eta_seconds');
        expect(resolveFullName('wall/id', null))
            .toBe('wall/id');
    });
});

describe('interpolator.interpolate', () => {
    let store: ReturnType<typeof useVariableStore>;

    beforeEach(() => {
        setActivePinia(createPinia());
        store = useVariableStore();
    });

    it('1. 纯文本无 ${var:} 子串 → 原样返回 + 空集合', () => {
        const r = interpolate('Hello world', 'w-abc', store);
        expect(r.text).toBe('Hello world');
        expect(r.referencedFullNames.size).toBe(0);
        expect(r.missingFullNames.size).toBe(0);
    });

    it('2. 单 ${var:user/X} + wallId 注入 + currentValue 命中', () => {
        store.set('user:w-abc/红队比分', mkVar('user:w-abc', '红队比分', '5'));
        const r = interpolate('比分 ${var:user/红队比分} 分', 'w-abc', store);
        expect(r.text).toBe('比分 5 分');
        expect(r.referencedFullNames.has('user:w-abc/红队比分')).toBe(true);
        expect(r.missingFullNames.size).toBe(0);
    });

    it('3. 变量不存在 + 无 fallback → "???"', () => {
        const r = interpolate('${var:user/ghost}', 'w-abc', store);
        expect(r.text).toBe(UNRESOLVED);
        expect(r.missingFullNames.has('user:w-abc/ghost')).toBe(true);
    });

    it('4. 变量不存在 + |fallback=N/A → "N/A"', () => {
        const r = interpolate('${var:bedwars/none|fallback=N/A}', 'w-abc', store);
        expect(r.text).toBe('N/A');
        expect(r.missingFullNames.has('bedwars/none')).toBe(true);
    });

    it('5. currentValue 空字符串 + |fallback=F → "F"', () => {
        store.set('user:w-abc/empty', mkVar('user:w-abc', 'empty', ''));
        const r = interpolate('${var:user/empty|fallback=F}', 'w-abc', store);
        expect(r.text).toBe('F');
    });

    it('6. currentValue null + 无 fallback + defaultValue → defaultValue', () => {
        store.set('user:w-abc/def', mkVar('user:w-abc', 'def', null, 'D'));
        const r = interpolate('${var:user/def}', 'w-abc', store);
        expect(r.text).toBe('D');
    });

    it('7. currentValue null + 无 fallback + defaultValue null → "???"', () => {
        store.set('user:w-abc/blank', mkVar('user:w-abc', 'blank', null, null));
        const r = interpolate('${var:user/blank}', 'w-abc', store);
        expect(r.text).toBe(UNRESOLVED);
    });

    it('8. 多占位符全部替换', () => {
        store.set('user:w-abc/red', mkVar('user:w-abc', 'red', '5'));
        store.set('user:w-abc/blue', mkVar('user:w-abc', 'blue', '3'));
        const r = interpolate('红 ${var:user/red} 蓝 ${var:user/blue}', 'w-abc', store);
        expect(r.text).toBe('红 5 蓝 3');
        expect(r.referencedFullNames.size).toBe(2);
    });

    it('9. missingFullNames 仅包含 store 没有的引用', () => {
        store.set('user:w-abc/exists', mkVar('user:w-abc', 'exists', '1'));
        const r = interpolate('${var:user/exists} ${var:user/ghost}', 'w-abc', store);
        expect(r.missingFullNames.has('user:w-abc/ghost')).toBe(true);
        expect(r.missingFullNames.has('user:w-abc/exists')).toBe(false);
    });

    it('10. referencedFullNames 精确匹配实际引用', () => {
        store.set('user:w-abc/a', mkVar('user:w-abc', 'a', '1'));
        const r = interpolate('${var:user/a} ${var:user/a} ${var:bedwars/b|fallback=0}', 'w-abc', store);
        expect(r.referencedFullNames.has('user:w-abc/a')).toBe(true);
        expect(r.referencedFullNames.has('bedwars/b')).toBe(true);
        expect(r.referencedFullNames.size).toBe(2);  // Set 去重
    });

    it('11. wallId null 时 user/X 走字面查询 + missing 标记', () => {
        const r = interpolate('${var:user/X}', null, store);
        expect(r.text).toBe(UNRESOLVED);
        expect(r.missingFullNames.has('user/X')).toBe(true);
    });

    it('12. 插件命名空间不受 wallId 注入影响', () => {
        store.set('bedwars/score', mkVar('bedwars', 'score', '42'));
        const r = interpolate('${var:bedwars/score}', 'w-abc', store);
        expect(r.text).toBe('42');
        expect(r.referencedFullNames.has('bedwars/score')).toBe(true);
    });

    it('13. null / undefined / 空字符串 输入 → 空结果 + 空集合', () => {
        const r1 = interpolate(null, 'w-abc', store);
        expect(r1.text).toBe('');
        expect(r1.referencedFullNames.size).toBe(0);
        const r2 = interpolate(undefined, 'w-abc', store);
        expect(r2.text).toBe('');
        const r3 = interpolate('', 'w-abc', store);
        expect(r3.text).toBe('');
    });

    it('14. 显式空 fallback (|fallback=) → 空字符串替换', () => {
        const r = interpolate('${var:user/x|fallback=}', 'w-abc', store);
        expect(r.text).toBe('');
        expect(r.missingFullNames.has('user:w-abc/x')).toBe(true);
    });

    it('15. quoteReplacement-equivalent：替换值含 $ / \\ 不当反向引用', () => {
        store.set('user:w-abc/k', mkVar('user:w-abc', 'k', '$1\\back'));
        const r = interpolate('${var:user/k}', 'w-abc', store);
        expect(r.text).toBe('$1\\back');
    });

    // 0.4.0-P3-J：wall.* 注入端到端
    it('16. ${var:wall.id} + wallId 注入命中 system:<wallId>/wall.id', () => {
        store.set('system:w-abc/wall.id', mkVar('system:w-abc', 'wall.id', 'w-abc'));
        const r = interpolate('Wall=${var:wall.id}', 'w-abc', store);
        expect(r.text).toBe('Wall=w-abc');
        expect(r.referencedFullNames.has('system:w-abc/wall.id')).toBe(true);
    });

    it('17. 混合 ${var:user/X} + ${var:wall.id} 都正确注入替换', () => {
        store.set('user:w-1/score', mkVar('user:w-1', 'score', '42'));
        store.set('system:w-1/wall.id', mkVar('system:w-1', 'wall.id', 'w-1'));
        const r = interpolate(
            '${var:user/score} 分 @ ${var:wall.id}',
            'w-1',
            store,
        );
        expect(r.text).toBe('42 分 @ w-1');
        expect(r.referencedFullNames.size).toBe(2);
        expect(r.referencedFullNames.has('user:w-1/score')).toBe(true);
        expect(r.referencedFullNames.has('system:w-1/wall.id')).toBe(true);
    });

    it('18. wallId null 时 wall.X 字面查询 missing', () => {
        const r = interpolate('${var:wall.id}', null, store);
        expect(r.text).toBe(UNRESOLVED);
        expect(r.missingFullNames.has('wall.id')).toBe(true);
    });
});
