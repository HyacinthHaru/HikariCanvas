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
    declaredKeyToVariable,
    displayName,
    flattenGroups,
    isDynamicNamespace,
    mergeMetadata,
    nextActiveIndex,
    totalCount,
    type NamespaceMetadata,
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

// ---------- P3-M metadata 合并 ----------

describe('declaredKeyToVariable (P3-M)', () => {
    it('生成完整 Variable 骨架（cached/default 留 null）', () => {
        const v = declaredKeyToVariable('system', {
            key: 'server.time',
            type: 'STRING',
            description: '当前服务器本地时间 HH:mm',
            ttlMs: 60000,
        });
        expect(v.namespace).toBe('system');
        expect(v.key).toBe('server.time');
        expect(v.type).toBe('STRING');
        expect(v.defaultValue).toBeNull();
        expect(v.currentValue).toBeNull();
        expect(v.ttl).toBe(60000);
        expect(v.source).toBeNull();
    });

    it('未知 type 退化为 STRING', () => {
        const v = declaredKeyToVariable('foo', { key: 'bar', type: 'WEIRD', ttlMs: 0 });
        expect(v.type).toBe('STRING');
    });
});

describe('mergeMetadata (P3-M)', () => {
    const metadata: NamespaceMetadata[] = [
        {
            namespace: 'user:w-abc',
            displayName: '我的变量',
            dynamic: false,
            keys: [{ key: 'red', type: 'NUMBER', ttlMs: 0 }],
        },
        {
            namespace: 'system',
            displayName: '系统变量',
            dynamic: false,
            keys: [
                { key: 'server.time', type: 'STRING', ttlMs: 60000 },
                { key: 'server.online', type: 'NUMBER', ttlMs: 30000 },
            ],
        },
        {
            namespace: 'scoreboard',
            displayName: '记分板',
            dynamic: true,
            keys: [],
        },
    ];

    it('metadata declared keys 全部出现（即使 store 无 cached value）', () => {
        const merged = mergeMetadata([], metadata);
        expect(merged.length).toBe(3);
        const fullNames = merged.map((v) => `${v.namespace}/${v.key}`);
        expect(fullNames).toEqual(['user:w-abc/red', 'system/server.time', 'system/server.online']);
        // cached value 留空
        for (const v of merged) expect(v.currentValue).toBeNull();
    });

    it('store cached value 覆盖骨架（同 fullName）', () => {
        const stored: Variable[] = [
            {
                namespace: 'system',
                key: 'server.time',
                type: 'STRING',
                defaultValue: null,
                currentValue: '14:35',
                updatedAt: 1000,
                ttl: 60000,
                source: 'system',
            },
        ];
        const merged = mergeMetadata(stored, metadata);
        const t = merged.find((v) => v.namespace === 'system' && v.key === 'server.time');
        expect(t?.currentValue).toBe('14:35');
        expect(t?.updatedAt).toBe(1000);
    });

    it('store-only 变量（metadata 没声明）append 到末尾', () => {
        const stored: Variable[] = [
            {
                namespace: 'scoreboard',
                key: 'kill.Steve',  // 动态 namespace 已注册的具体 key
                type: 'NUMBER',
                defaultValue: null,
                currentValue: '5',
                updatedAt: 100,
                ttl: 5000,
                source: 'scoreboard',
            },
        ];
        const merged = mergeMetadata(stored, metadata);
        // 3 declared + 1 store-only = 4
        expect(merged.length).toBe(4);
        // store-only 在末尾
        const last = merged[merged.length - 1];
        expect(last.namespace).toBe('scoreboard');
        expect(last.key).toBe('kill.Steve');
        expect(last.currentValue).toBe('5');
    });

    it('动态 namespace（keys 空）不污染骨架；其上动态注册的 key 走 store-only 路径', () => {
        // scoreboard dynamic - keys 空 → 仅当 store 有具体 key 才出现
        const merged = mergeMetadata([], metadata);
        expect(merged.some((v) => v.namespace === 'scoreboard')).toBe(false);
    });

    it('空 metadata + 空 store → 空数组', () => {
        const merged = mergeMetadata([], []);
        expect(merged).toEqual([]);
    });
});

describe('isDynamicNamespace (P3-M)', () => {
    const metadata: NamespaceMetadata[] = [
        { namespace: 'system', displayName: 'System', dynamic: false, keys: [] },
        { namespace: 'scoreboard', displayName: 'Scoreboard', dynamic: true, keys: [] },
        { namespace: 'papi', displayName: 'PAPI', dynamic: true, keys: [] },
    ];

    it('动态 namespace 返 true', () => {
        expect(isDynamicNamespace(metadata, 'scoreboard')).toBe(true);
        expect(isDynamicNamespace(metadata, 'papi')).toBe(true);
    });

    it('静态 namespace 返 false', () => {
        expect(isDynamicNamespace(metadata, 'system')).toBe(false);
    });

    it('未声明 namespace 返 false', () => {
        expect(isDynamicNamespace(metadata, 'bedwars')).toBe(false);
        expect(isDynamicNamespace([], 'system')).toBe(false);
    });
});

// ---------- buildGroups 接入 mergeMetadata 后行为不变 ----------

describe('buildGroups + merged metadata (P3-M)', () => {
    it('用 mergeMetadata 输出走 buildGroups 仍 4 组分类正确', () => {
        const metadata: NamespaceMetadata[] = [
            {
                namespace: 'user:w-abc',
                displayName: '我的变量',
                dynamic: false,
                keys: [{ key: 'red', type: 'NUMBER', ttlMs: 0 }],
            },
            {
                namespace: 'system',
                displayName: '系统变量',
                dynamic: false,
                keys: [{ key: 'server.time', type: 'STRING', ttlMs: 60000 }],
            },
            {
                namespace: 'papi',
                displayName: 'PAPI',
                dynamic: true,
                keys: [],
            },
        ];
        const merged = mergeMetadata([], metadata);
        const groups = buildGroups(merged, 'w-abc', '');
        // mine: 1, plugin: 0, system: 1, papi: 0
        expect(groups[0].items.map((v) => v.key)).toEqual(['red']);
        expect(groups[1].items.length).toBe(0);
        expect(groups[2].items.map((v) => v.key)).toEqual(['server.time']);
        expect(groups[3].items.length).toBe(0);
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
