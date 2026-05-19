/**
 * 0.4.0-P2-H pickerLogic 单测。
 *
 * 纯函数逻辑（无 Vue / DOM），直接 vitest node 跑。
 *
 * 覆盖：
 * - displayName 对 user / 插件 / system / papi 返正确短名
 * - buildGroups 4 组分类 + wallId 注入 + 跨 wall user 隔离
 * - buildGroups keyword filter
 * - flattenGroups / totalCount 平铺正确性
 * - nextActiveIndex 循环回绕 / 边界
 */
import { describe, expect, it } from 'vitest';
import {
    buildGroups,
    displayName,
    flattenGroups,
    nextActiveIndex,
    totalCount,
} from '../pickerLogic';
import type { Variable } from '@/types/variable';

function mkVar(namespace: string, key: string, current: string | null = '1'): Variable {
    return {
        namespace,
        key,
        type: 'STRING',
        defaultValue: null,
        currentValue: current,
        updatedAt: 0,
        ttl: 0,
        source: null,
    };
}

describe('displayName', () => {
    it('user:<wallId>/X → "user/X"（隐藏 wallId）', () => {
        expect(displayName(mkVar('user:w-abc', 'red'))).toBe('user/red');
    });
    it('bedwars/X → "bedwars/X"', () => {
        expect(displayName(mkVar('bedwars', 'score'))).toBe('bedwars/score');
    });
    it('system/server.time → "system/server.time"', () => {
        expect(displayName(mkVar('system', 'server.time'))).toBe('system/server.time');
    });
});

describe('buildGroups', () => {
    const vars: Variable[] = [
        mkVar('user:w-abc', 'my_red'),
        mkVar('user:w-xyz', 'other_wall_var'),  // 跨 wall → 应被过滤
        mkVar('bedwars', 'score'),
        mkVar('system', 'server.time'),
        mkVar('papi', '%player_name%'),
    ];

    it('4 组分类 + 跨 wall user 隔离', () => {
        const groups = buildGroups(vars, 'w-abc', '');
        expect(groups.map((g) => g.id)).toEqual(['mine', 'plugin', 'system', 'papi']);
        expect(groups[0].items.map((v) => v.key)).toEqual(['my_red']);
        expect(groups[1].items.map((v) => v.key)).toEqual(['score']);
        expect(groups[2].items.map((v) => v.key)).toEqual(['server.time']);
        expect(groups[3].items.map((v) => v.key)).toEqual(['%player_name%']);
    });

    it('wallId 为 null → mine 组为空（user 变量需要 wallId 上下文）', () => {
        const groups = buildGroups(vars, null, '');
        expect(groups[0].items.length).toBe(0);
        expect(groups[1].items.length).toBe(1);  // plugin 仍有
    });

    it('keyword filter 模糊匹配 namespace / key', () => {
        const groups = buildGroups(vars, 'w-abc', 'score');
        // 只 plugin 组的 "score" 命中
        expect(totalCount(groups)).toBe(1);
        expect(groups[1].items[0].key).toBe('score');
    });

    it('keyword 大小写不敏感', () => {
        const groups = buildGroups(vars, 'w-abc', 'SERVER');
        expect(totalCount(groups)).toBe(1);
        expect(groups[2].items[0].key).toBe('server.time');
    });

    it('keyword 空白 trim', () => {
        const groups = buildGroups(vars, 'w-abc', '   ');
        expect(totalCount(groups)).toBe(4);  // 不过滤
    });
});

describe('flattenGroups / totalCount', () => {
    const vars: Variable[] = [
        mkVar('user:w-abc', 'a'),
        mkVar('bedwars', 'b'),
        mkVar('system', 'c'),
    ];
    const groups = buildGroups(vars, 'w-abc', '');

    it('flattenGroups 顺序 = mine → plugin → system → papi', () => {
        const flat = flattenGroups(groups);
        expect(flat.map((v) => v.key)).toEqual(['a', 'b', 'c']);
    });

    it('totalCount = 总变量数', () => {
        expect(totalCount(groups)).toBe(3);
    });
});

describe('nextActiveIndex', () => {
    it('total = 0 → -1', () => {
        expect(nextActiveIndex(0, 1, 0)).toBe(-1);
    });
    it('current = -1, delta = 1 → 0', () => {
        expect(nextActiveIndex(-1, 1, 5)).toBe(0);
    });
    it('current = -1, delta = -1 → 末尾', () => {
        expect(nextActiveIndex(-1, -1, 5)).toBe(4);
    });
    it('循环回绕：last + 1 → 0', () => {
        expect(nextActiveIndex(4, 1, 5)).toBe(0);
    });
    it('循环回绕：0 - 1 → last', () => {
        expect(nextActiveIndex(0, -1, 5)).toBe(4);
    });
    it('普通 +1', () => {
        expect(nextActiveIndex(2, 1, 5)).toBe(3);
    });
});
