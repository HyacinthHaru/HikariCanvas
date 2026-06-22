import { describe, it, expect } from 'vitest';
import { messages } from '@/i18n/messages';

function collectKeys(obj: any, prefix = ''): Set<string> {
    const keys = new Set<string>();
    for (const [k, v] of Object.entries(obj)) {
        const path = prefix ? `${prefix}.${k}` : k;
        if (v !== null && typeof v === 'object' && !Array.isArray(v)) {
            collectKeys(v, path).forEach((kk) => keys.add(kk));
        } else {
            keys.add(path); // string | function 叶子
        }
    }
    return keys;
}

describe('i18n/messages', () => {
    it('zh and en have identical key sets', () => {
        const zh = collectKeys(messages.zh);
        const en = collectKeys(messages.en);
        const zhOnly = [...zh].filter((k) => !en.has(k));
        const enOnly = [...en].filter((k) => !zh.has(k));
        expect(zhOnly, `zh-only: ${zhOnly.join(', ')}`).toEqual([]);
        expect(enOnly, `en-only: ${enOnly.join(', ')}`).toEqual([]);
    });

    it('no Chinese chars in en string leaves', () => {
        const cjk = /[一-鿿]/;
        const bad: string[] = [];
        const walk = (obj: any, prefix = '') => {
            for (const [k, v] of Object.entries(obj)) {
                const path = prefix ? `${prefix}.${k}` : k;
                if (typeof v === 'string' && cjk.test(v)) bad.push(`${path}: ${v}`);
                else if (v !== null && typeof v === 'object' && !Array.isArray(v)) walk(v, path);
            }
        };
        walk(messages.en);
        expect(bad, `en has Chinese: ${bad.join(' | ')}`).toEqual([]);
    });
});
