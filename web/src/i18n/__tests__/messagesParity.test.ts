/**
 * 中英文案表必须一一对应。
 *
 * 起因：油漆桶面板的 toleranceLabel / toleranceTip 两个键谁都没写，界面上滑块标签是空白。
 * `Messages = typeof messages['zh']` 意味着漏键在 vue-tsc 下会报错，但 CI 只跑测试 + 打包、
 * 不跑 vue-tsc，所以漏键能一路带到线上。这条测试把这一类"加了一边忘了另一边"当场拦下。
 *
 * 失败信息直接列出缺哪些键，照着补上即可。
 */
import { describe, it, expect } from 'vitest';
import { messages } from '../messages';

/** 递归展开成 'a.b.c' 形式的叶子路径集合（函数与字符串都算叶子）。 */
function leafKeys(node: unknown, prefix = ''): string[] {
    if (node === null || typeof node !== 'object') return [prefix];
    return Object.entries(node as Record<string, unknown>)
        .flatMap(([k, v]) => leafKeys(v, prefix ? `${prefix}.${k}` : k));
}

describe('i18n 中英键对齐', () => {
    it('zh 与 en 的键集合完全一致', () => {
        const zh = new Set(leafKeys(messages.zh));
        const en = new Set(leafKeys(messages.en));
        expect({
            英文缺的键: [...zh].filter(k => !en.has(k)),
            中文缺的键: [...en].filter(k => !zh.has(k)),
        }).toEqual({ 英文缺的键: [], 中文缺的键: [] });
    });

    it('油漆桶的滑块标签两边都在（回归守卫）', () => {
        expect(messages.zh.livePaint.toleranceLabel).toBeTruthy();
        expect(messages.zh.livePaint.toleranceTip).toBeTruthy();
        expect(messages.en.livePaint.toleranceLabel).toBeTruthy();
        expect(messages.en.livePaint.toleranceTip).toBeTruthy();
    });
});
