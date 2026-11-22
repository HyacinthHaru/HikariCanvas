/**
 * 中英文案表完整性校验：键集合一一对应，且英文文案不含中文字符。
 */
import { describe, it, expect } from 'vitest';
import { messages } from '../messages';

/** 递归展开成 'a.b.c' 形式的叶子路径集合（函数与字符串都算叶子）。 */
function leafKeys(node: unknown, prefix = ''): string[] {
    if (node === null || typeof node !== 'object') return [prefix];
    return Object.entries(node as Record<string, unknown>)
        .flatMap(([k, v]) => leafKeys(v, prefix ? `${prefix}.${k}` : k));
}

describe('i18n/messages', () => {
    it('zh 与 en 的键集合完全一致', () => {
        const zh = new Set(leafKeys(messages.zh));
        const en = new Set(leafKeys(messages.en));
        expect({
            英文缺的键: [...zh].filter((k) => !en.has(k)),
            中文缺的键: [...en].filter((k) => !zh.has(k)),
        }).toEqual({ 英文缺的键: [], 中文缺的键: [] });
    });

    it('油漆桶的滑块标签两边都在', () => {
        expect(messages.zh.livePaint.toleranceLabel).toBeTruthy();
        expect(messages.zh.livePaint.toleranceTip).toBeTruthy();
        expect(messages.en.livePaint.toleranceLabel).toBeTruthy();
        expect(messages.en.livePaint.toleranceTip).toBeTruthy();
    });

    it('no Chinese chars in en string leaves', () => {
        const cjk = /[\u4e00-\u9fff]/;
        const bad: string[] = [];
        const walk = (obj: Record<string, unknown>, prefix = ''): void => {
            for (const [k, v] of Object.entries(obj)) {
                const path = prefix ? `${prefix}.${k}` : k;
                if (typeof v === 'string' && cjk.test(v)) {
                    bad.push(`${path}: ${v}`);
                } else if (v !== null && typeof v === 'object' && !Array.isArray(v)) {
                    walk(v as Record<string, unknown>, path);
                }
            }
        };
        walk(messages.en as unknown as Record<string, unknown>);
        expect(bad, `en has Chinese: ${bad.join(' | ')}`).toEqual([]);
    });
});
